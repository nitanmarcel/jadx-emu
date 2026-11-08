package jadx.plugins.emu.exec.graph

import jadx.plugins.emu.exec.AbsHeap
import jadx.plugins.emu.exec.CallResolver
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.StubNotImplemented
import jadx.plugins.emu.exec.Frame
import jadx.plugins.emu.exec.Interpreter
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.model.DalvikInsn
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.model.MethodRef
import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.DvmThrowable
import jadx.plugins.emu.exec.runtime.UninitHost
import jadx.plugins.emu.exec.runtime.UnknownVal
import jadx.plugins.emu.exec.runtime.WideHigh
import jadx.api.plugins.input.insns.Opcode

internal class State(val regs: Array<Any?>, val result: Any?, val resultType: String?)

private object NoReturn

/**
 * Abstract register state of a method at every instruction, produced by [Dataflow.analyze].
 *
 * Register values are either concrete host values, emulated objects, or [UnknownVal]; a value that
 * differs between paths reaching the same instruction is reported as unknown.
 *
 * @property method the analyzed method
 * @property returnValue the joined value of all reachable `return` instructions
 * @property complete false if the analysis hit a limit or an unhandled instruction, in which case
 *   [reachable] may under-approximate
 * @property reachable per instruction index, whether any path reaches it
 */
public class DataflowResult internal constructor(
    public val method: DexMethod,
    private val inStates: Array<State?>,
    private val outStates: Array<State?>,
    public val returnValue: Any?,
    public val complete: Boolean = true,
    private val edges: Map<Int, List<Int>> = emptyMap(),
) {
    public val reachable: BooleanArray = BooleanArray(method.insns.size) { inStates[it] != null }

    /**
     * Offsets of the successors actually taken from the instruction at [offset].
     * A conditional branch with a single successor was decided statically.
     */
    public fun successorsOf(offset: Int): List<Int> = edges[offset] ?: emptyList()

    /**
     * Value of register [reg] before the instruction at [index] executes, or null if unreachable.
     */
    public fun regIn(index: Int, reg: Int): Any? = inStates[index]?.regs?.getOrNull(reg)

    /**
     * Value of register [reg] after the instruction at [index] executes, or null if unreachable.
     */
    public fun regOut(index: Int, reg: Int): Any? = outStates[index]?.regs?.getOrNull(reg)

    /**
     * [regIn] addressed by instruction offset.
     */
    public fun regInAtOffset(offset: Int, reg: Int): Any? = method.offsetToIndex[offset]?.let { regIn(it, reg) }

    /**
     * [regOut] addressed by instruction offset.
     */
    public fun regOutAtOffset(offset: Int, reg: Int): Any? = method.offsetToIndex[offset]?.let { regOut(it, reg) }

    /**
     * Whether the instruction at [offset] is reachable.
     */
    public fun isReachableOffset(offset: Int): Boolean = method.offsetToIndex[offset]?.let { reachable[it] } ?: false
}

/**
 * Path-insensitive abstract interpretation of a method.
 *
 * Executes every reachable instruction with values joined at control-flow merges, following calls into
 * application code (with summaries) and host code (through [Vm.hostExec]). Static and instance field
 * state is modelled weakly. Use it to learn which values are constant at each program point without
 * running the method concretely.
 */
public class Dataflow(private val vm: Vm) {

    private val interp = Interpreter(vm)
    private val inProgress = HashSet<String>()
    private val summaryCache = HashMap<List<Any?>, Any?>()
    private var depth = 0
    private val maxDepth = vm.limits.maxDepth
    private val handlerEdgeCache = HashMap<DexMethod, Map<Int, IntArray>>()
    private val resolver = CallResolver { insn, frame -> resolveCall(insn, frame) }

    private val siteObjects = HashMap<String, DvmObject>()
    private var heapVersion = 0
    private var complete = true
    private val edges = HashMap<Int, List<Int>>()
    private val heap = object : AbsHeap {
        override fun newInstance(site: String, type: String): Any? {
            vm.ensureClinit(type)
            if (vm.source.classInfo(type) == null) return UninitHost(type)
            return siteObjects.getOrPut(site) { DvmObject(type) }
        }
        override fun iget(obj: Any?, key: String, type: String): Any? {
            val ctx = vm.ctx
            if (ctx != null && (key in ctx.mutableFields || type in EngineContext.MUTABLE_CONTAINER_TYPES)) return UnknownVal(type)
            return if (obj is DvmObject) obj.fields[key] ?: UnknownVal(type) else UnknownVal(type)
        }
        override fun iput(obj: Any?, key: String, v: Any?) {
            if (obj !is DvmObject) return
            weak(obj.fields, key, v)
        }
        override fun sget(declClass: String, key: String, type: String): Any? {
            vm.ensureClinit(declClass)
            val ctx = vm.ctx
            if (ctx != null && (key in ctx.mutableFields || type in EngineContext.MUTABLE_CONTAINER_TYPES)) return UnknownVal(type)
            return vm.staticsOf(declClass)[key] ?: UnknownVal(type)
        }
        override fun sput(declClass: String, key: String, v: Any?) {
            vm.ensureClinit(declClass)
            weak(vm.staticsOf(declClass), key, v)
        }
    }

    private fun weak(map: MutableMap<String, Any?>, key: String, v: Any?) {
        val present = map.containsKey(key)
        val nw = if (present) joinSlot(map[key], v) else v
        if (!present || !slotEqual(nw, map[key])) { map[key] = nw; heapVersion++; summaryCache.clear() }
    }

    /**
     * Analyze [method].
     *
     * @param args concrete argument values to start from; null treats every parameter as unknown
     */
    @JvmOverloads
    public fun analyze(method: DexMethod, args: List<Any?>? = null): DataflowResult {
        complete = true
        edges.clear()
        summaryCache.clear()
        val (ret, inS, outS) = run(method, entry(method, args), recordEdges = true)
        return DataflowResult(method, inS, outS, ret, complete, edges.toMap())
    }

    private fun run(method: DexMethod, entryState: State, recordEdges: Boolean = false): Triple<Any?, Array<State?>, Array<State?>> {
        val n = method.insns.size
        val inS = arrayOfNulls<State>(n)
        val outS = arrayOfNulls<State>(n)
        inS[0] = entryState

        val handlerEdges = handlerEdgeCache.getOrPut(method) { handlerEdgesOf(method) }

        var ret: Any? = NoReturn
        val cap = (n + 4) * (method.registersCount + 4) * 8 + 1024
        val deadline = if (vm.limits.maxMillis <= 0) Long.MAX_VALUE else System.nanoTime() + vm.limits.maxMillis * 1_000_000
        val scratch = Frame(method.registersCount)
        val wl = IntMinHeap(n)
        val inWl = BooleanArray(n)
        val enqueue: (Int) -> Unit = { si -> if (!inWl[si]) { inWl[si] = true; wl.add(si) } }
        var outerPass = 0
        while (true) {
            if (System.nanoTime() >= deadline) { complete = false; break }
            val versionBefore = heapVersion
            ret = NoReturn
            wl.clear(); inWl.fill(false)
            if (outerPass == 0) enqueue(0) else for (i in 0 until n) if (inS[i] != null) enqueue(i)
            var steps = 0
            while (!wl.isEmpty()) {
                if (steps++ > cap || (steps and 2047) == 0 && System.nanoTime() >= deadline) { complete = false; break }
                val i = wl.poll(); inWl[i] = false
                val inState = inS[i] ?: continue
                loadScratch(scratch, inState)
                val res = interp.absStep(method, method.insns[i], scratch, i, resolver, heap)
                val prev = outS[i]
                val out = if (prev != null && frameEqualsState(scratch, prev)) prev else snapshot(scratch)
                outS[i] = out
                if (recordEdges) edges[method.insns[i].offset] = res.successors.toList()
                if (res.returns) ret = if (ret === NoReturn) res.returnVal else joinSlot(ret, res.returnVal)

                for (off in res.successors) propagate(method, inS, enqueue, off, out)
                handlerEdges[i]?.forEach { off -> propagate(method, inS, enqueue, off, inState) }
            }
            outerPass++
            if (outerPass >= 64) { complete = false; break }
            if (heapVersion == versionBefore) break
        }

        return Triple(if (ret === NoReturn) null else ret, inS, outS)
    }

    private fun resolveCall(insn: DalvikInsn, frame: Frame): Pair<Any?, String?> {
        val ref = insn.ref as MethodRef
        val rt = ref.returnType
        val op = insn.opcode
        val hasReceiver = op != Opcode.INVOKE_STATIC && op != Opcode.INVOKE_STATIC_RANGE
        val (recv, args) = interp.gatherArgs(insn, frame, ref, hasReceiver)

        if (ref.name == "<init>" && recv is UninitHost) {
            frame.replace(recv, vm.hostExec.construct(recv.type, ref, args))
            return null to null
        }

        val target = resolveTarget(ref, recv, op)
        if (target != null) return summary(target, recv, args) to rt

        interp.tryReflect(ref, recv, args, allowInvoke = false)?.let { return it }

        val hv = try {
            if (hasReceiver) vm.hostExec.invokeInstance(ref, recv, args) else vm.hostExec.invokeStatic(ref, args)
        } catch (e: StubNotImplemented) {
            UnknownVal(rt)
        } catch (e: DvmThrowable) {
            UnknownVal(rt)
        }
        if (hv is UnknownVal) {
            havocCall(ref.name, recv, args)
            if (ref.name != "<init>") havocHostContainer(frame, recv)
            for (a in args) havocHostContainer(frame, a)
        }
        return hv to rt
    }

    private fun havocHostContainer(frame: Frame, v: Any?) {
        if (v is java.util.Collection<*> || v is java.util.Map<*, *> || v is StringBuilder || v is StringBuffer) {
            frame.replace(v, UnknownVal("L" + v.javaClass.name.replace('.', '/') + ";"))
        }
    }

    private fun resolveTarget(ref: MethodRef, recv: Any?, op: Opcode): DexMethod? = when (op) {
        Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE ->
            interp.resolveVirtualFor(ref.declClass, ref.shortId)
        Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE ->
            vm.source.method(ref.declClass, ref.shortId)
        Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE ->
            interp.resolveVirtualFor(vm.source.classInfo(ref.declClass)?.superType, ref.shortId)
        else -> if (recv is DvmObject) interp.resolveVirtualFor(recv.type, ref.shortId) else null
    }

    private fun summary(target: DexMethod, recv: Any?, args: List<Any?>): Any? {
        val mid = "${target.declClass}#${target.ref.shortId}"
        if (target.insns.isEmpty() || mid in inProgress || depth >= maxDepth) {
            havocCall(target.ref.name, recv, args)
            return UnknownVal(target.ref.returnType)
        }
        val key = summaryKey(mid, recv, args)
        if (summaryCache.containsKey(key)) return summaryCache[key]
        inProgress.add(mid); depth++
        val hvBefore = heapVersion
        val completeBefore = complete
        return try {
            val frame = Frame(target.registersCount)
            interp.bindParams(frame, target, args, recv)
            val r = run(target, snapshot(frame)).first
            if (heapVersion == hvBefore && complete == completeBefore) summaryCache[key] = r
            r
        } finally { depth--; inProgress.remove(mid) }
    }

    private fun summaryKey(mid: String, recv: Any?, args: List<Any?>): List<Any?> =
        buildList { add(mid); add(canon(recv)); for (a in args) add(canon(a)) }

    private fun canon(v: Any?): Any? = when (v) {
        null, is Int, is Long, is Float, is Double, is Boolean, is Char, is Byte, is Short, is String -> v
        is UnknownVal -> "U:" + (v.type ?: "?")
        WideHigh -> "W"
        else -> IdentityKey(v)
    }

    private class IdentityKey(val v: Any) {
        override fun equals(other: Any?) = other is IdentityKey && other.v === v
        override fun hashCode() = System.identityHashCode(v)
    }

    private fun havocCall(name: String, recv: Any?, args: List<Any?>) {
        if (name != "<init>") havocObject(recv)
        for (a in args) havocObject(a)
    }

    private fun havocObject(v: Any?) {
        if (v !is DvmObject) return
        for (k in v.fields.keys.toList()) {
            val cur = v.fields[k]
            if (cur is UnknownVal) continue
            weak(v.fields, k, UnknownVal(typeOf(cur) ?: "Ljava/lang/Object;"))
        }
    }

    private fun propagate(method: DexMethod, inS: Array<State?>, enqueue: (Int) -> Unit, offset: Int, incoming: State) {
        val si = method.offsetToIndex[offset] ?: return
        val cur = inS[si]
        if (cur == null) { inS[si] = incoming; enqueue(si); return }
        val joined = joinStatesOrSame(cur, incoming)
        if (joined !== cur) { inS[si] = joined; enqueue(si) }
    }

    private fun entry(method: DexMethod, args: List<Any?>?): State {
        val frame = Frame(method.registersCount)
        if (args == null) {
            var reg = method.argsStartReg
            if (!method.isStatic) { frame.set(reg, UnknownVal(method.declClass)); reg++ }
            for (t in method.ref.argTypes) {
                if (t == "J" || t == "D") { frame.setWide(reg, UnknownVal(t)); reg += 2 } else { frame.set(reg, UnknownVal(t)); reg++ }
            }
        } else {
            interp.bindParams(frame, method, args, args.firstOrNull())
        }
        return snapshot(frame)
    }

    private fun loadScratch(frame: Frame, s: State) {
        System.arraycopy(s.regs, 0, frame.regs, 0, s.regs.size)
        frame.result = s.result
        frame.resultType = s.resultType
        frame.pendingException = null
    }

    private fun snapshot(frame: Frame) = State(frame.regs.copyOf(), frame.result, frame.resultType)

    private fun frameEqualsState(frame: Frame, s: State): Boolean {
        if (frame.regs.size != s.regs.size) return false
        for (i in frame.regs.indices) if (!slotEqual(frame.regs[i], s.regs[i])) return false
        return slotEqual(frame.result, s.result) && frame.resultType == s.resultType
    }

    private fun joinStatesOrSame(cur: State, inc: State): State {
        var regs: Array<Any?>? = null
        for (i in cur.regs.indices) {
            val j = joinSlot(cur.regs[i], inc.regs.getOrNull(i))
            if (!slotEqual(j, cur.regs[i])) (regs ?: cur.regs.copyOf().also { regs = it })[i] = j
        }
        val jr = joinSlot(cur.result, inc.result)
        val jt = if (cur.resultType == inc.resultType) cur.resultType else null
        if (regs == null && slotEqual(jr, cur.result) && jt == cur.resultType) return cur
        return State(regs ?: cur.regs.copyOf(), jr, jt)
    }

    private fun joinSlot(x: Any?, y: Any?): Any? =
        if (slotEqual(x, y)) x else UnknownVal(commonType(typeOf(x), typeOf(y)))

    private fun slotEqual(x: Any?, y: Any?): Boolean = when {
        x is UnknownVal && y is UnknownVal -> x.type == y.type
        x === WideHigh && y === WideHigh -> true
        else -> x === y || x == y
    }

    private fun commonType(a: String?, b: String?): String? = if (a == b) a else null

    private fun typeOf(x: Any?): String? = when (x) {
        is UnknownVal -> x.type
        is Int, is Boolean, is Char, is Byte, is Short -> "I"
        is Long -> "J"; is Float -> "F"; is Double -> "D"
        is String -> "Ljava/lang/String;"
        is DvmObject -> x.type
        is IntArray -> "[I"; is LongArray -> "[J"; is ByteArray -> "[B"; is CharArray -> "[C"
        is ShortArray -> "[S"; is BooleanArray -> "[Z"; is FloatArray -> "[F"; is DoubleArray -> "[D"
        else -> null
    }
}

private class IntMinHeap(capacity: Int) {
    private val a = IntArray(if (capacity > 0) capacity else 1)
    private var size = 0

    fun isEmpty() = size == 0
    fun clear() { size = 0 }

    fun add(x: Int) {
        var i = size++
        a[i] = x
        while (i > 0) {
            val p = (i - 1) ushr 1
            if (a[p] <= a[i]) break
            val t = a[p]; a[p] = a[i]; a[i] = t; i = p
        }
    }

    fun poll(): Int {
        val top = a[0]
        a[0] = a[--size]
        var i = 0
        while (true) {
            val l = 2 * i + 1; val r = l + 1; var m = i
            if (l < size && a[l] < a[m]) m = l
            if (r < size && a[r] < a[m]) m = r
            if (m == i) break
            val t = a[m]; a[m] = a[i]; a[i] = t; i = m
        }
        return top
    }
}
