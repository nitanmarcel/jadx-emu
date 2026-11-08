package jadx.plugins.emu.exec

import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.model.MethodRef
import jadx.plugins.emu.exec.runtime.ClinitState
import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.DvmThrowable
import jadx.plugins.emu.exec.runtime.UNKNOWN
import jadx.plugins.emu.exec.runtime.UnknownVal
import jadx.plugins.emu.exec.runtime.defaultValueOf

/**
 * Thrown to stop the current emulation run (limits exceeded, detached debugger, invalid pc).
 */
public class VmAbort(message: String) : RuntimeException(message)

/**
 * Executes `native` methods on behalf of the emulator, e.g. by delegating to a native emulator.
 */
public interface NativeBridge {
    /**
     * Invoke a native method.
     *
     * @param className descriptor of the declaring class
     * @param signature method signature in `(args)ret` form
     * @param receiver the `this` value, or null for static natives
     * @return the result, or an [UnknownVal] if the call cannot be performed
     */
    public fun call(className: String, methodName: String, signature: String, args: List<Any?>, receiver: Any?): Any?
}

/**
 * Dalvik bytecode emulator.
 *
 * A `Vm` executes methods from [source], running host (JVM) code for classes allowed by [host] and
 * Android framework calls through the stubs in [hooks]. Values that cannot be computed become [UnknownVal]s
 * rather than errors. Instances are not thread-safe.
 *
 * @property source where classes and method bodies come from
 * @property host policy deciding which JVM classes may execute on the host
 * @property limits step, time and depth limits
 * @property hook optional instruction tracer
 * @property nativeBridge optional handler for `native` methods; without one they yield unknown values
 * @property hooks method hooks, framework stubs and device environment
 * @property ctx optional shared context supplying cached static-initializer state
 * @property statics static field storage, shared with the caller so state can outlive the VM
 * @property androidEnvUnknown when true, `Build.*` fields from [jadx.plugins.emu.api.AndroidEnv] read as unknown so that
 *   results do not depend on a particular device; set false for concrete emulation
 * @property diagnostics sinks for calls this VM could not execute
 * @property syntheticCaller class name reported by `getStackTrace()` frames, or null
 */
public class Vm @JvmOverloads constructor(
    public val source: MethodSource,
    public val host: HostBoundary = HostBoundary(),
    public val limits: ExecLimits = ExecLimits(),
    public val hook: ExecHook? = null,
    public val nativeBridge: NativeBridge? = null,
    public val hooks: Hooks = Hooks(),
    public val ctx: EngineContext? = null,
    public val statics: HashMap<String, HashMap<String, Any?>> = HashMap(),
    public val androidEnvUnknown: Boolean = true,
    public val diagnostics: List<DiagnosticSink> = emptyList(),
) {
    public var syntheticCaller: String? = hooks.env.syntheticCaller

    /**
     * Executor for host-class calls under the [host] policy.
     */
    internal val hostExec: HostExec = HostExec(host, this) { kind, ref, reason -> diagnose(kind, ref, reason) }
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
    private var active = 0
    private var deadline = 0L

    private fun newDeadline(): Long = if (limits.maxMillis <= 0) Long.MAX_VALUE else System.nanoTime() + limits.maxMillis * 1_000_000

    internal fun startDeadline() {
        if (active == 0) deadline = newDeadline()
    }

    /**
     * Run [method] as a top-level call.
     *
     * Initializes the declaring class first, applies [limits], and never throws: any abort or
     * uncaught emulated exception yields [UNKNOWN]. Called from a hook, it shares the outer run's time limit.
     *
     * @param args argument values in declaration order (wide values as a single `Long`/`Double`)
     * @param receiver the `this` value for instance methods
     */
    @JvmOverloads
    public fun invoke(method: DexMethod, args: List<Any?> = emptyList(), receiver: Any? = null): Any? {
        val outer = active == 0
        active++
        try {
            if (outer) deadline = newDeadline()
            runCatching { ensureClinit(method.declClass) }
            if (outer) deadline = newDeadline()
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
        } finally {
            active--
        }
    }

    /**
     * Run [method] as a nested call within the current run.
     *
     * Unlike [invoke] this shares the caller's deadline, enforces [ExecLimits.maxDepth], and lets
     * emulated exceptions propagate to the caller. Works outside a run too.
     */
    public fun call(method: DexMethod, args: List<Any?>, receiver: Any?): Any? {
        if (depth >= limits.maxDepth) {
            diagnose(Diagnostic.Kind.ABORTED, method.ref, "depth limit")
            return UnknownVal(method.ref.returnType)
        }
        if (active == 0) deadline = newDeadline()
        depth++
        active++
        try {
            return interp.run(method, args, receiver)
        } catch (e: VmAbort) {
            diagnose(Diagnostic.Kind.ABORTED, method.ref, e.message ?: "aborted")
            return UnknownVal(method.ref.returnType)
        } finally {
            depth--
            active--
        }
    }

    /**
     * Call `shortId` on [receiver], resolved on its runtime class.
     */
    @JvmOverloads
    public fun invoke(receiver: Any, shortId: String, args: List<Any?> = emptyList()): Any? {
        val type = (receiver as? DvmObject)?.type ?: hostDescriptor(receiver.javaClass)
        if (receiver is DvmObject) interp.resolveVirtualFor(type, shortId)?.let { return call(it, args, receiver) }
        return hostExec.invokeInstance(MethodRef.of(type, shortId), receiver, args)
    }

    private fun hostDescriptor(cls: Class<*>): String = "L" + cls.name.replace('.', '/') + ";"

    /**
     * Emulate `new classDesc(args)` with the constructor of signature [ctorSig].
     *
     * @throws IllegalArgumentException if no such constructor exists
     */
    @JvmOverloads
    public fun construct(classDesc: String, ctorSig: String = "()V", args: List<Any?> = emptyList()): DvmObject {
        val obj = DvmObject(classDesc)
        source.method(classDesc, "<init>$ctorSig")?.let { init ->
            invoke(init, args, obj)
            return obj
        }
        if (hooks.isFrameworkClass(classDesc) && FrameworkStubs.method(classDesc, "<init>$ctorSig") != null) return obj
        throw IllegalArgumentException("no <init>$ctorSig on $classDesc")
    }

    /**
     * Read static field `Lcls;->NAME`, initializing the class first; [UNKNOWN] if it cannot be read.
     */
    public fun getStatic(pattern: String): Any? {
        val (cls, name) = splitField(pattern)
        val info = source.classInfo(cls)
        if (info == null) return hostStaticField(cls, name).let { if (it === NotHandled) UNKNOWN else it }
        runCatching { ensureClinit(cls) }
        val meta = findField(cls, name, static = true) ?: return UNKNOWN
        val statics = staticsOf(meta.ref.declClass)
        return if (statics.containsKey(meta.ref.key)) statics[meta.ref.key] else defaultValueOf(meta.ref.type)
    }

    /**
     * Write static field `Lcls;->NAME` of an application class, initializing the class first.
     */
    public fun setStatic(pattern: String, value: Any?) {
        val (cls, name) = splitField(pattern)
        runCatching { ensureClinit(cls) }
        val meta = findField(cls, name, static = true)
        val (owner, key) = if (meta != null) meta.ref.declClass to meta.ref.key else cls to "$cls.$name"
        staticsOf(owner)[key] = value
    }

    /**
     * Read instance field [name] of [obj], resolved on its class chain.
     */
    public fun getField(obj: DvmObject, name: String): Any? {
        val meta = findField(obj.type, name, static = false)
        val key = meta?.ref?.key ?: obj.fields.keys.firstOrNull { it.endsWith(".$name") } ?: return UNKNOWN
        return if (obj.fields.containsKey(key)) obj.fields[key] else defaultValueOf(meta?.ref?.type ?: "")
    }

    /**
     * Write instance field [name] of [obj], resolved on its class chain.
     */
    public fun setField(obj: DvmObject, name: String, value: Any?) {
        val meta = findField(obj.type, name, static = false)
        obj.fields[meta?.ref?.key ?: obj.fields.keys.firstOrNull { it.endsWith(".$name") } ?: "${obj.type}.$name"] = value
    }

    private fun splitField(pattern: String): Pair<String, String> {
        val i = pattern.indexOf("->")
        require(i > 0 && i < pattern.length - 2) { "malformed field pattern: $pattern" }
        return pattern.substring(0, i) to pattern.substring(i + 2)
    }

    private fun findField(startType: String, name: String, static: Boolean): FieldMeta? {
        var cur: String? = startType
        while (cur != null) {
            val info = source.classInfo(cur) ?: return null
            info.fields.firstOrNull { it.ref.name == name && it.isStatic == static }?.let { return it }
            cur = info.superType
        }
        return null
    }

    /**
     * Static field storage of [declClass], created on first access. Keys are [jadx.plugins.emu.exec.model.FieldRef.key].
     */
    public fun staticsOf(declClass: String): HashMap<String, Any?> = statics.getOrPut(declClass) { HashMap() }

    /**
     * Descriptors of classes whose static initializer has started or completed in this VM.
     */
    public fun initialized(): Set<String> = clinitState.keys

    /**
     * Read a static field of a host or framework class.
     *
     * @return the value, or [NotHandled] if the field is stubbed as unknown, blocked by policy, or unreadable
     */
    internal fun hostStaticField(declClass: String, name: String): Any? {
        if (androidEnvUnknown && hooks.env.field(declClass, name) !== NotHandled) return NotHandled
        val stub = hooks.fieldValue(declClass, name)
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
    public fun ensureClinit(desc: String) {
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
    internal fun deadlineExceeded(): Boolean = System.nanoTime() >= deadline
}
