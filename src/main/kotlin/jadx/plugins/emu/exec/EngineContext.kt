package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.UninitHost
import jadx.plugins.emu.exec.runtime.UnknownVal
import jadx.plugins.emu.exec.runtime.WideHigh
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-application configuration and facts shared by many [Vm] instances: the host-code policy, the
 * Android environment, cached static-initializer results and the set of fields mutated outside initializers.
 *
 * Create one per loaded app and build VMs from it with [newVm]; it is safe to share across threads.
 *
 * @property limits default limits for VMs created by [newVm]
 * @property host which JVM classes emulated code may execute
 * @property android framework stubs and device environment
 */
class EngineContext(
    private val source: MethodSource,
    val limits: ExecLimits = ExecLimits(),
    val host: HostBoundary = HostBoundary(),
    val android: AndroidStubs = AndroidStubs(),
) {
    /**
     * Sinks receiving [Diagnostic]s from every VM built on this context; [LogSink] is registered by default.
     */
    val diagnostics: MutableList<DiagnosticSink> = CopyOnWriteArrayList(listOf(LogSink()))

    /**
     * A VM over this context's source, policy and environment.
     *
     * @param androidEnvUnknown when true, `Build.*` fields read as unknown so results do not depend on the
     *   configured device; the default for analyses
     * @param diagnostics sinks for this VM, by default the context-wide ones
     */
    fun newVm(
        limits: ExecLimits = this.limits,
        hook: ExecHook? = null,
        hooks: HookRegistry? = null,
        statics: HashMap<String, HashMap<String, Any?>> = HashMap(),
        androidEnvUnknown: Boolean = true,
        diagnostics: List<DiagnosticSink> = this.diagnostics,
    ): Vm = Vm(source, host, limits, hook, null, hooks, this, android, statics, androidEnvUnknown, diagnostics)

    companion object {
        /**
         * Descriptors of mutable collection and builder types. Fields of these types are treated as
         * unknown by abstract analyses because their contents can change between reads.
         */
        val MUTABLE_CONTAINER_TYPES: Set<String> = setOf(
            "Ljava/util/Collection;", "Ljava/util/List;", "Ljava/util/ArrayList;", "Ljava/util/LinkedList;",
            "Ljava/util/Vector;", "Ljava/util/Stack;", "Ljava/util/AbstractList;", "Ljava/util/AbstractCollection;",
            "Ljava/util/Set;", "Ljava/util/HashSet;", "Ljava/util/LinkedHashSet;", "Ljava/util/TreeSet;", "Ljava/util/AbstractSet;",
            "Ljava/util/Map;", "Ljava/util/HashMap;", "Ljava/util/LinkedHashMap;", "Ljava/util/TreeMap;",
            "Ljava/util/Hashtable;", "Ljava/util/AbstractMap;", "Ljava/util/concurrent/ConcurrentHashMap;",
            "Ljava/util/Queue;", "Ljava/util/Deque;", "Ljava/util/ArrayDeque;", "Ljava/util/PriorityQueue;",
            "Ljava/lang/StringBuilder;", "Ljava/lang/StringBuffer;",
        )
    }

    private class Uncopyable(val value: Any) : RuntimeException()

    private val snapshots = HashMap<String, HashMap<String, Any?>?>()

    /**
     * Static field values of [desc] after its initializer has run, as a fresh deep copy.
     *
     * The initializer is emulated once and cached. Returns null if the class is unknown, if its
     * initializer touched other classes' static state, or if a value cannot be copied.
     */
    @Synchronized
    fun clinitStaticsFor(desc: String): HashMap<String, Any?>? {
        if (source.classInfo(desc) == null) return null
        if (!snapshots.containsKey(desc)) snapshots[desc] = computeSnapshot(desc)
        val base = snapshots[desc] ?: return null
        val seen = IdentityHashMap<Any, Any>()
        return HashMap<String, Any?>(base.size).apply { for ((k, v) in base) put(k, deepCopy(v, seen)) }
    }

    private fun computeSnapshot(desc: String): HashMap<String, Any?>? {
        val vm = Vm(source, host, limits, ctx = null, android = android, diagnostics = diagnostics)
        runCatching { vm.ensureClinit(desc) }
        val allowed = superChain(desc)
        if (vm.initialized().any { it !in allowed }) return null
        val base = vm.statics[desc] ?: return HashMap()
        return try {
            val seen = IdentityHashMap<Any, Any>()
            HashMap<String, Any?>(base.size).apply { for ((k, v) in base) put(k, deepCopy(v, seen)) }
        } catch (e: Uncopyable) {
            null
        }
    }

    /**
     * Keys of fields written anywhere other than `<clinit>` (static) or `<init>` (instance).
     * Reads of these fields are treated as unknown by abstract analyses.
     */
    val mutableFields: Set<String> by lazy {
        val out = HashSet<String>()
        for (m in source.allMethods()) {
            val n = m.ref.name
            for (insn in m.insns) {
                val op = insn.opcode.name
                val isSput = op.startsWith("SPUT") && n != "<clinit>"
                val isIput = op.startsWith("IPUT") && n != "<init>" && n != "<clinit>"
                if (isSput || isIput) {
                    (insn.ref as? jadx.plugins.emu.exec.model.FieldRef)?.let { out += it.key }
                }
            }
        }
        out
    }

    private fun superChain(desc: String): Set<String> {
        val out = HashSet<String>()
        var c: String? = desc
        while (c != null && out.add(c)) c = source.classInfo(c)?.superType
        return out
    }

    private fun deepCopy(v: Any?, seen: IdentityHashMap<Any, Any>): Any? = when (v) {
        null, is Int, is Long, is Float, is Double, is Boolean, is Char, is Byte, is Short, is String,
        is UnknownVal, is UninitHost -> v
        WideHigh -> v
        is DvmObject -> (seen[v] as? DvmObject) ?: DvmObject(v.type).also { c ->
            seen[v] = c
            for ((k, fv) in v.fields) c.fields[k] = deepCopy(fv, seen)
        }
        is IntArray -> v.copyOf(); is LongArray -> v.copyOf(); is ByteArray -> v.copyOf()
        is CharArray -> v.copyOf(); is ShortArray -> v.copyOf(); is BooleanArray -> v.copyOf()
        is FloatArray -> v.copyOf(); is DoubleArray -> v.copyOf()
        is Array<*> -> (seen[v] as? Array<*>) ?: arrayOfNulls<Any?>(v.size).also { c ->
            seen[v] = c
            for (i in v.indices) c[i] = deepCopy(v[i], seen)
        }
        else -> throw Uncopyable(v)
    }
}
