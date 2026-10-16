package jadx.plugins.emu.api

import jadx.plugins.emu.exec.Diagnostic
import jadx.plugins.emu.exec.NotHandled
import jadx.plugins.emu.exec.StubNotImplemented
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.VmAbort
import jadx.plugins.emu.exec.model.MethodRef
import jadx.plugins.emu.exec.runtime.DvmThrowable
import jadx.plugins.emu.exec.runtime.UNKNOWN
import jadx.plugins.emu.exec.runtime.UnknownVal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A method call seen by a [MethodHandler].
 */
class MethodCall(val vm: Vm, val method: MethodRef, val receiver: Any?, val args: MutableList<Any?>) {
    var replaced: Boolean = false
        private set
    var result: Any? = null
        private set

    /**
     * Set the call's result instead of running it.
     */
    fun replace(value: Any?) { replaced = true; result = value }
}

/**
 * Handler for a hooked or stubbed method.
 */
fun interface MethodHandler { fun MethodCall.handle() }

/**
 * Handle to a registered hook, stub or field.
 */
fun interface Registration { fun remove() }

/**
 * Handler run after a call has completed, to observe or rewrite its result.
 *
 * Return [result] unchanged to only observe, or another value to change what the caller receives.
 * Calling [MethodCall.replace] here has no effect (it is reported as a handler failure): return the new value.
 */
fun interface ReturnHandler { fun MethodCall.handle(result: Any?): Any? }

/**
 * Values reported for `android.os.Build` and `Build.VERSION` fields.
 */
class AndroidEnv(
    sdkInt: Int = 33,
    release: String = "13",
    model: String = "Pixel 6",
    manufacturer: String = "Google",
    brand: String = "google",
    device: String = "oriole",
    product: String = "oriole",
    fingerprint: String = "google/oriole/oriole:13/TQ3A.230805.001/10316531:user/release-keys",
    val syntheticCaller: String? = null,
) {
    private val fields: Map<String, Any?> = mapOf(
        "Landroid/os/Build\$VERSION;->SDK_INT" to sdkInt,
        "Landroid/os/Build\$VERSION;->RELEASE" to release,
        "Landroid/os/Build;->MODEL" to model,
        "Landroid/os/Build;->MANUFACTURER" to manufacturer,
        "Landroid/os/Build;->BRAND" to brand,
        "Landroid/os/Build;->DEVICE" to device,
        "Landroid/os/Build;->PRODUCT" to product,
        "Landroid/os/Build;->FINGERPRINT" to fingerprint,
        "Landroid/os/Build;->SERIAL" to "unknown",
        "Landroid/os/Build;->HARDWARE" to device,
    )

    /**
     * Value of the `Build` field, or [NotHandled] if this environment does not define it.
     */
    fun field(declClass: String, name: String): Any? {
        val key = "$declClass->$name"
        return if (key in fields) fields[key] else NotHandled
    }
}

/**
 * Method hooks, framework stubs and static field values, keyed by `Lcls;->name` or `Lcls;->name(args)ret`.
 */
class Hooks private constructor(val env: AndroidEnv, private val parent: Hooks?) {
    constructor(env: AndroidEnv = AndroidEnv()) : this(env, null)

    private class Entry(val id: Int, val owner: Hooks, val handler: MethodHandler?, val value: Any?, val builtin: Boolean)
    private class AfterEntry(val id: Int, val owner: Hooks, val handler: ReturnHandler)

    private val root: Hooks = parent?.root ?: this
    private val counter = AtomicInteger(0)
    private val hooks = ConcurrentHashMap<String, CopyOnWriteArrayList<Entry>>()
    private val stubs = ConcurrentHashMap<String, CopyOnWriteArrayList<Entry>>()
    private val fields = ConcurrentHashMap<String, CopyOnWriteArrayList<Entry>>()
    private val afters = ConcurrentHashMap<String, CopyOnWriteArrayList<AfterEntry>>()

    init { if (parent == null) registerBuiltins() }

    /**
     * A view of this registry with its own [clear].
     */
    fun scope(): Hooks = Hooks(env, this)

    /**
     * Run [handler] before every call matching [pattern].
     */
    fun hook(pattern: String, handler: MethodHandler): Registration = add(root.hooks, pattern, handler, null)

    /**
     * Run [handler] after every call matching [pattern] that completed through emulated code, a host method
     * or the native bridge, with the call's result (a stub's result included). Calls replaced by a hook are not
     * seen, and neither are calls that threw or constructions of host objects. Handlers registered for the exact
     * signature run before name-only ones, newest first within each group, each receiving the previous result;
     * a handler that throws is reported as [Diagnostic.Kind.HANDLER_FAILED] and leaves the result unchanged.
     */
    fun after(pattern: String, handler: ReturnHandler): Registration {
        val entry = AfterEntry(root.counter.incrementAndGet(), this, handler)
        root.afters.getOrPut(pattern) { CopyOnWriteArrayList() }.add(entry)
        return Registration { root.afters[pattern]?.removeIf { it.id == entry.id } }
    }

    /**
     * Implement a framework or host method matching [pattern].
     */
    fun stub(pattern: String, handler: MethodHandler): Registration = add(root.stubs, pattern, handler, null)

    /**
     * Implement a framework or host method as returning [value].
     */
    fun stub(pattern: String, value: Any?): Registration = stub(pattern) { replace(value) }

    /**
     * Set the value of static field [pattern].
     */
    fun field(pattern: String, value: Any?): Registration = add(root.fields, pattern, null, value)

    /**
     * Remove the registrations made through this instance.
     */
    fun clear() {
        val mine: (Entry) -> Boolean = if (parent == null) { e -> !e.builtin } else { e -> e.owner === this }
        for (map in listOf(root.hooks, root.stubs, root.fields)) for (list in map.values) list.removeIf(mine)
        val mineAfter: (AfterEntry) -> Boolean = if (parent == null) { _ -> true } else { e -> e.owner === this }
        for (list in root.afters.values) list.removeIf(mineAfter)
    }

    /**
     * Whether [declClass] is an Android framework class.
     */
    fun isFrameworkClass(declClass: String): Boolean = FRAMEWORK_PREFIXES.any { declClass.startsWith(it) }

    internal fun runHooks(vm: Vm, ref: MethodRef, receiver: Any?, args: MutableList<Any?>): MethodCall? {
        val entries = root.matching(root.hooks, ref) ?: return null
        val call = MethodCall(vm, ref, receiver, args)
        for (e in entries) {
            if (!run(e, call)) call.replace(UnknownVal(ref.returnType))
            if (call.replaced) break
        }
        return call
    }

    internal fun runAfters(vm: Vm, ref: MethodRef, receiver: Any?, args: MutableList<Any?>, result: Any?): Any? {
        if (root.afters.isEmpty()) return result   // nothing was ever registered: no allocation, no lookup
        val entries = root.matchingAfters(ref) ?: return result
        var out = result
        for (e in entries) {
            val call = MethodCall(vm, ref, receiver, args)
            try {
                out = with(e.handler) { call.handle(out) }
            } catch (t: DvmThrowable) {
                throw t
            } catch (t: VmAbort) {
                throw t
            } catch (t: Throwable) {
                vm.diagnose(Diagnostic.Kind.HANDLER_FAILED, ref, t.toString())
            }
            if (call.replaced) vm.diagnose(Diagnostic.Kind.HANDLER_FAILED, ref, "replace() has no effect in an after handler; return the new value")
        }
        return out
    }

    internal fun runStubs(vm: Vm, ref: MethodRef, receiver: Any?, args: List<Any?>): Any? {
        val entries = root.matching(root.stubs, ref)
        if (entries != null) {
            val call = MethodCall(vm, ref, receiver, args.toMutableList())
            for (e in entries) {
                if (!run(e, call)) return UnknownVal(ref.returnType)
                if (call.replaced) return call.result
            }
        }
        if (isFrameworkClass(ref.declClass)) throw StubNotImplemented("${ref.declClass}->${ref.shortId}")
        return NotHandled
    }

    internal fun fieldValue(declClass: String, name: String): Any? {
        root.fields["$declClass->$name"]?.lastOrNull()?.let { return it.value }
        return env.field(declClass, name)
    }

    private fun run(e: Entry, call: MethodCall): Boolean {
        try {
            with(e.handler!!) { call.handle() }
            return true
        } catch (t: DvmThrowable) {
            throw t
        } catch (t: VmAbort) {
            throw t
        } catch (t: Throwable) {
            call.vm.diagnose(Diagnostic.Kind.HANDLER_FAILED, call.method, t.toString())
            return false
        }
    }

    private fun matching(map: ConcurrentHashMap<String, CopyOnWriteArrayList<Entry>>, ref: MethodRef): List<Entry>? {
        val exact = map["${ref.declClass}->${ref.shortId}"]
        val byName = map["${ref.declClass}->${ref.name}"]
        if (exact.isNullOrEmpty() && byName.isNullOrEmpty()) return null
        val out = ArrayList<Entry>((exact?.size ?: 0) + (byName?.size ?: 0))
        exact?.let { out.addAll(it.asReversed()) }
        byName?.let { out.addAll(it.asReversed()) }
        return out
    }

    private fun matchingAfters(ref: MethodRef): List<AfterEntry>? {
        val exact = afters["${ref.declClass}->${ref.shortId}"]
        val byName = afters["${ref.declClass}->${ref.name}"]
        if (exact.isNullOrEmpty() && byName.isNullOrEmpty()) return null
        val out = ArrayList<AfterEntry>((exact?.size ?: 0) + (byName?.size ?: 0))
        exact?.let { out.addAll(it.asReversed()) }
        byName?.let { out.addAll(it.asReversed()) }
        return out
    }

    private fun add(map: ConcurrentHashMap<String, CopyOnWriteArrayList<Entry>>, pattern: String, handler: MethodHandler?, value: Any?, builtin: Boolean = false): Registration {
        val entry = Entry(root.counter.incrementAndGet(), this, handler, value, builtin)
        map.getOrPut(pattern) { CopyOnWriteArrayList() }.add(entry)
        return Registration { map[pattern]?.removeIf { it.id == entry.id } }
    }

    private fun builtin(pattern: String, handler: MethodHandler) { add(stubs, pattern, handler, null, builtin = true) }

    private fun registerBuiltins() {
        builtin("Landroid/util/Base64;->encodeToString") { replace(base64Encode(args, toStr = true)) }
        builtin("Landroid/util/Base64;->encode") { replace(base64Encode(args, toStr = false)) }
        builtin("Landroid/util/Base64;->decode") { replace(base64Decode(args)) }
        for (m in listOf("d", "e", "i", "w", "v", "wtf", "println")) builtin("Landroid/util/Log;->$m") { replace(0) }
        builtin("Landroid/util/Log;->isLoggable") { replace(false) }
        builtin("Landroid/util/Log;->getStackTraceString") { replace("") }
        for (cls in listOf("Ljava/lang/Thread;", "Ljava/lang/Throwable;", "Ljava/lang/Exception;", "Ljava/lang/RuntimeException;"))
            builtin("$cls->getStackTrace") { vm.syntheticCaller?.let { c -> replace(Array(16) { StackTraceElement(c, "m$it", "Source.java", it + 1) }) } }
    }

    private fun base64Encode(args: List<Any?>, toStr: Boolean): Any? {
        val data = args.getOrNull(0) as? ByteArray ?: return UNKNOWN
        val e = encoder((args.getOrNull(1) as? Int) ?: 0)
        return if (toStr) e.encodeToString(data) else e.encode(data)
    }

    private fun base64Decode(args: List<Any?>): Any? {
        val d = decoder((args.getOrNull(1) as? Int) ?: 0)
        return when (val v = args.getOrNull(0)) {
            is String -> d.decode(v)
            is ByteArray -> d.decode(v)
            else -> UNKNOWN
        }
    }

    private fun encoder(flags: Int): java.util.Base64.Encoder {
        var e = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) e = e.withoutPadding()
        return e
    }

    private fun decoder(flags: Int): java.util.Base64.Decoder =
        if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getMimeDecoder()

    companion object {
        private const val NO_PADDING = 1
        private const val URL_SAFE = 8
        private val FRAMEWORK_PREFIXES = listOf("Landroid/", "Lcom/android/", "Ldalvik/", "Llibcore/")
    }
}
