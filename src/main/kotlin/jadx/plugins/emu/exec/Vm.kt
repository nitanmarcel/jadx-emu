package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.model.MethodRef
import jadx.plugins.emu.exec.runtime.ClinitState
import jadx.plugins.emu.exec.runtime.DvmThrowable
import jadx.plugins.emu.exec.runtime.UNKNOWN
import jadx.plugins.emu.exec.runtime.UnknownVal

/**
 * Thrown to stop the current emulation run (limits exceeded, detached debugger, invalid pc).
 */
class VmAbort(message: String) : RuntimeException(message)

/**
 * Executes `native` methods on behalf of the emulator, e.g. by delegating to a native emulator.
 */
interface NativeBridge {
    /**
     * Invoke a native method.
     *
     * @param className descriptor of the declaring class
     * @param signature method signature in `(args)ret` form
     * @param receiver the `this` value, or null for static natives
     * @return the result, or an [UnknownVal] if the call cannot be performed
     */
    fun call(className: String, methodName: String, signature: String, args: List<Any?>, receiver: Any? = null): Any?
}

/**
 * Dalvik bytecode emulator.
 *
 * A `Vm` executes methods from [source], running host (JVM) code for classes allowed by [host] and
 * Android framework calls through [android]. Values that cannot be computed become [UnknownVal]s
 * rather than errors. Instances are not thread-safe.
 *
 * @property source where classes and method bodies come from
 * @property host policy deciding which JVM classes may execute on the host
 * @property limits step, time and depth limits
 * @property hook optional instruction tracer
 * @property nativeBridge optional handler for `native` methods; without one they yield unknown values
 * @property hooks optional method interceptors
 * @property ctx optional shared context supplying cached static-initializer state
 * @property android framework stubs and device environment
 * @property statics static field storage, shared with the caller so state can outlive the VM
 * @property androidEnvUnknown when true, `Build.*` fields from [AndroidEnv] read as unknown so that
 *   results do not depend on a particular device; set false for concrete emulation
 * @property diagnostics sinks for calls this VM could not execute
 */
class Vm(
    val source: MethodSource,
    val host: HostBoundary = HostBoundary(),
    val limits: ExecLimits = ExecLimits(),
    val hook: ExecHook? = null,
    val nativeBridge: NativeBridge? = null,
    val hooks: HookRegistry? = null,
    val ctx: EngineContext? = null,
    val android: AndroidStubs = AndroidStubs(),
    val statics: HashMap<String, HashMap<String, Any?>> = HashMap(),
    val androidEnvUnknown: Boolean = true,
    val diagnostics: List<DiagnosticSink> = emptyList(),
) {
    /**
     * Executor for host-class calls under the [host] policy.
     */
    val hostExec = HostExec(host, android) { kind, ref, reason -> diagnose(kind, ref, reason) }
    internal var curMethod: DexMethod? = null
    internal var curOffset = -1

    internal fun diagnose(kind: Diagnostic.Kind, ref: MethodRef, detail: String) {
        if (diagnostics.isEmpty()) return
        val d = Diagnostic(kind, ref, curMethod, curOffset, detail)
        for (sink in diagnostics) sink.report(d)
    }
    private val clinitState = HashMap<String, ClinitState>()
    private val interp = Interpreter(this)
    private var depth = 0
    private var deadline = 0L

    /**
     * Run [method] as a top-level call.
     *
     * Initializes the declaring class first, applies [limits], and never throws: any abort or
     * uncaught emulated exception yields [UNKNOWN].
     *
     * @param args argument values in declaration order (wide values as a single `Long`/`Double`)
     * @param receiver the `this` value for instance methods
     */
    fun invoke(method: DexMethod, args: List<Any?> = emptyList(), receiver: Any? = null): Any? {
        deadline = if (limits.maxMillis <= 0) Long.MAX_VALUE else System.nanoTime() + limits.maxMillis * 1_000_000
        runCatching { ensureClinit(method.declClass) }
        deadline = if (limits.maxMillis <= 0) Long.MAX_VALUE else System.nanoTime() + limits.maxMillis * 1_000_000
        return try {
            interp.run(method, args, receiver)
        } catch (e: VmAbort) {
            diagnose(Diagnostic.Kind.ABORTED, method.ref, e.message ?: "aborted")
            UNKNOWN
        } catch (t: DvmThrowable) {
            diagnose(Diagnostic.Kind.UNCAUGHT, method.ref, "uncaught ${t.type}")
            UNKNOWN
        } catch (t: Throwable) {
            UNKNOWN
        }
    }

    /**
     * Run [method] as a nested call within the current run.
     *
     * Unlike [invoke] this shares the caller's deadline, enforces [ExecLimits.maxDepth], and lets
     * emulated exceptions propagate to the caller.
     */
    fun call(method: DexMethod, args: List<Any?>, receiver: Any?): Any? {
        if (depth >= limits.maxDepth) {
            diagnose(Diagnostic.Kind.ABORTED, method.ref, "depth limit")
            return UnknownVal(method.ref.returnType)
        }
        depth++
        try {
            return interp.run(method, args, receiver)
        } catch (e: VmAbort) {
            diagnose(Diagnostic.Kind.ABORTED, method.ref, e.message ?: "aborted")
            return UnknownVal(method.ref.returnType)
        } finally {
            depth--
        }
    }

    /**
     * Static field storage of [declClass], created on first access. Keys are [jadx.plugins.emu.exec.model.FieldRef.key].
     */
    fun staticsOf(declClass: String): HashMap<String, Any?> = statics.getOrPut(declClass) { HashMap() }

    /**
     * Descriptors of classes whose static initializer has started or completed in this VM.
     */
    fun initialized(): Set<String> = clinitState.keys

    /**
     * Read a static field of a host or framework class.
     *
     * @return the value, or [NotHandled] if the field is stubbed as unknown, blocked by policy, or unreadable
     */
    fun hostStaticField(declClass: String, name: String): Any? {
        if (androidEnvUnknown && android.env.field(declClass, name) !== NotHandled) return NotHandled
        val stub = android.field(declClass, name)
        if (stub !== NotHandled) return stub
        return runCatching {
            val f = hostClass(declClass).getField(name)
            if (!host.canRead(f)) NotHandled else f.get(null)
        }.getOrDefault(NotHandled)
    }

    /**
     * Initialize class [desc] if it has not been initialized in this VM.
     *
     * Superclasses are initialized first. Static state comes from [ctx]'s snapshot when available,
     * otherwise from constant field initializers followed by running `<clinit>`. Unknown classes are ignored.
     */
    fun ensureClinit(desc: String) {
        if (deadline == 0L) deadline = if (limits.maxMillis <= 0) Long.MAX_VALUE else System.nanoTime() + limits.maxMillis * 1_000_000
        if (clinitState[desc] != null) return
        val info = source.classInfo(desc) ?: return
        clinitState[desc] = ClinitState.RUNNING
        info.superType?.let { ensureClinit(it) }
        val snap = ctx?.clinitStaticsFor(desc)
        if (snap != null) {
            staticsOf(desc).putAll(snap)
            clinitState[desc] = ClinitState.DONE
            return
        }
        if (info.staticInits.isNotEmpty()) staticsOf(desc).putAll(info.staticInits)
        source.method(desc, "<clinit>()V")?.let { call(it, emptyList(), null) }
        clinitState[desc] = ClinitState.DONE
    }

    /**
     * Whether the [ExecLimits.maxMillis] deadline of the current run has passed.
     */
    fun deadlineExceeded(): Boolean = System.nanoTime() >= deadline
}
