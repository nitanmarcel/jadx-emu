package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.model.ArrayPayload
import jadx.plugins.emu.exec.model.CallSiteRef
import jadx.plugins.emu.exec.model.DalvikInsn
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.model.FieldRef
import jadx.plugins.emu.exec.model.Handler
import jadx.plugins.emu.exec.model.MethodRef
import jadx.plugins.emu.exec.model.StringRef
import jadx.plugins.emu.exec.model.SwitchPayload
import jadx.plugins.emu.exec.model.TypeRef
import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.DvmThrowable
import jadx.plugins.emu.exec.runtime.UninitHost
import jadx.plugins.emu.exec.runtime.UnknownVal
import jadx.api.plugins.input.insns.Opcode

private sealed interface Step
private class Next(val pc: Int) : Step
private class Done(val value: Any?) : Step

/**
 * Outcome of one abstract step.
 *
 * @property successors offsets of the instructions control may reach next; empty when the method returns or throws
 * @property returnVal the returned value when [returns] is true
 * @property returns whether the instruction leaves the method
 */
class AbsResult(val successors: IntArray, val returnVal: Any?, val returns: Boolean)

private val EMPTY_INTS = IntArray(0)

private val TYPED_GETTERS = mapOf(
    "getInt" to "I", "getLong" to "J", "getShort" to "S", "getByte" to "B", "getChar" to "C",
    "getBoolean" to "Z", "getFloat" to "F", "getDouble" to "D",
)

private val TYPED_SETTERS = setOf("setInt", "setLong", "setShort", "setByte", "setChar", "setBoolean", "setFloat", "setDouble")

private val PRIMITIVE_NAMES = mapOf(
    "I" to "int", "J" to "long", "Z" to "boolean", "B" to "byte", "C" to "char", "S" to "short",
    "F" to "float", "D" to "double", "V" to "void",
)

private const val ACC_PUBLIC = 0x1
private const val ACC_VARARGS = 0x80

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val CLASS = "Ljava/lang/Class;"
private const val METHOD = "Ljava/lang/reflect/Method;"
private const val CONSTRUCTOR = "Ljava/lang/reflect/Constructor;"
private const val FIELD = "Ljava/lang/reflect/Field;"

/**
 * Supplies the result of an invoke during abstract interpretation.
 */
fun interface CallResolver {
    /**
     * @return the call result and its type descriptor; the result may be an [UnknownVal]
     */
    fun resolve(insn: DalvikInsn, frame: Frame): Pair<Any?, String?>
}

/**
 * Object and field model used during abstract interpretation.
 */
interface AbsHeap {
    /**
     * @param site allocation site as `Lcls;#shortId@offset`
     */
    fun newInstance(site: String, type: String): Any?
    fun iget(obj: Any?, key: String, type: String): Any?
    fun iput(obj: Any?, key: String, v: Any?)
    fun sget(declClass: String, key: String, type: String): Any?
    fun sput(declClass: String, key: String, v: Any?)
}

/**
 * Instruction executor behind [Vm]. Most callers should use [Vm.invoke] instead.
 */
class Interpreter(private val vm: Vm) {

    private val fallCache = HashMap<DexMethod, Array<IntArray>>()

    /**
     * Execute [method] to completion in a fresh frame.
     *
     * @return the return value, or null for `void`
     * @throws VmAbort if a limit is exceeded
     * @throws jadx.plugins.emu.exec.runtime.DvmThrowable if emulated code throws and does not catch
     */
    fun run(method: DexMethod, args: List<Any?>, receiver: Any?): Any? {
        val frame = Frame(method.registersCount)
        bindParams(frame, method, args, receiver)
        val hook = vm.hook
        val outerMethod = vm.curMethod
        val outerOffset = vm.curOffset
        vm.curMethod = method
        hook?.onEnter(method, frame)
        try {
            var pc = 0
            var steps = 0
            while (true) {
                if (steps++ > vm.limits.maxSteps || vm.deadlineExceeded()) throw VmAbort("step limit")
                if (pc !in method.insns.indices) throw VmAbort("pc out of range")
                val insn = method.insns[pc]
                vm.curOffset = insn.offset
                hook?.onStep(method, insn, frame, pc)
                when (val s = exec(method, insn, frame, pc)) {
                    is Next -> {
                        if (insn.resultReg >= 0 && s.pc == pc + 1) applyResultReg(insn, frame)
                        pc = s.pc
                    }
                    is Done -> return s.value
                }
            }
        } finally {
            vm.curMethod = outerMethod
            vm.curOffset = outerOffset
            hook?.onExit(method)
        }
    }

    /**
     * Store [receiver] and [args] into the parameter registers of [frame], coercing each argument to
     * its declared type.
     */
    fun bindParams(frame: Frame, method: DexMethod, args: List<Any?>, receiver: Any?) {
        var reg = method.argsStartReg
        if (!method.isStatic) { frame.set(reg, receiver); reg++ }
        var i = 0
        for (t in method.ref.argTypes) {
            val v = retype(args.getOrNull(i), t)
            if (t == "J" || t == "D") { frame.setWide(reg, v); reg += 2 } else { frame.set(reg, v); reg++ }
            i++
        }
    }

    private fun exec(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int): Step {
        val r = insn.regs
        when (insn.opcode) {
            Opcode.RETURN -> return Done(retype(frame.get(r[0]), method.ref.returnType))
            Opcode.RETURN_VOID -> return Done(null)
            Opcode.GOTO -> return Next(idx(method, insn.target))
            Opcode.IF_EQ -> return branch(method, insn, pc, eq(frame.get(r[0]), frame.get(r[1])))
            Opcode.IF_NE -> return branch(method, insn, pc, !eq(frame.get(r[0]), frame.get(r[1])))
            Opcode.IF_LT -> return branch(method, insn, pc, ci2(frame.get(r[0])) < ci2(frame.get(r[1])))
            Opcode.IF_GE -> return branch(method, insn, pc, ci2(frame.get(r[0])) >= ci2(frame.get(r[1])))
            Opcode.IF_GT -> return branch(method, insn, pc, ci2(frame.get(r[0])) > ci2(frame.get(r[1])))
            Opcode.IF_LE -> return branch(method, insn, pc, ci2(frame.get(r[0])) <= ci2(frame.get(r[1])))
            Opcode.IF_EQZ -> return branch(method, insn, pc, isZero(frame.get(r[0])))
            Opcode.IF_NEZ -> return branch(method, insn, pc, !isZero(frame.get(r[0])))
            Opcode.IF_LTZ -> return branch(method, insn, pc, ci2(frame.get(r[0])) < 0)
            Opcode.IF_GEZ -> return branch(method, insn, pc, ci2(frame.get(r[0])) >= 0)
            Opcode.IF_GTZ -> return branch(method, insn, pc, ci2(frame.get(r[0])) > 0)
            Opcode.IF_LEZ -> return branch(method, insn, pc, ci2(frame.get(r[0])) <= 0)
            Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> {
                val sw = insn.payload as? SwitchPayload ?: return Next(pc + 1)
                val sel = frame.get(r[0])
                if (sel.unk()) throw VmAbort("unknown switch selector")
                val k = sw.keys.indexOf(ci(sel))
                return if (k < 0) Next(pc + 1) else Next(idx(method, insn.offset + sw.targets[k]))
            }
            Opcode.DIV_INT -> return divInt(method, insn, pc, frame) { a, b -> a / b }
            Opcode.REM_INT -> return divInt(method, insn, pc, frame) { a, b -> a % b }
            Opcode.DIV_INT_LIT -> return divIntLit(method, insn, pc, frame, insn.literal.toInt()) { a, b -> a / b }
            Opcode.REM_INT_LIT -> return divIntLit(method, insn, pc, frame, insn.literal.toInt()) { a, b -> a % b }
            Opcode.DIV_LONG -> return divLong(method, insn, pc, frame) { a, b -> a / b }
            Opcode.REM_LONG -> return divLong(method, insn, pc, frame) { a, b -> a % b }
            Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> return invoke(method, insn, frame, pc, hasReceiver = false, virtual = false, superCall = false)
            Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE -> return invoke(method, insn, frame, pc, hasReceiver = true, virtual = false, superCall = false)
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE ->
                return invoke(method, insn, frame, pc, hasReceiver = true, virtual = true, superCall = false)
            Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE -> return invoke(method, insn, frame, pc, hasReceiver = true, virtual = false, superCall = true)
            Opcode.INVOKE_CUSTOM, Opcode.INVOKE_CUSTOM_RANGE -> { invokeCustom(insn, frame); return Next(pc + 1) }
            Opcode.CHECK_CAST -> {
                val o = frame.get(r[0]); val ct = (insn.ref as TypeRef).desc
                if (!o.unk() && o != null) {
                    val vt = valueType(o)
                    if (vt != null && !isAssignable(vt, ct)) return routeThrow(method, insn.offset, DvmThrowable("Ljava/lang/ClassCastException;", null), frame)
                }
                return Next(pc + 1)
            }
            Opcode.THROW -> return routeThrow(method, insn.offset, asThrowable(frame.get(r[0])), frame)
            else -> { dataTransfer(insn, frame); return Next(pc + 1) }
        }
    }

    private fun dataTransfer(insn: DalvikInsn, frame: Frame) {
        val r = insn.regs
        when (insn.opcode) {
            Opcode.NOP -> {}
            Opcode.CONST -> frame.set(r[0], insn.literal.toInt())
            Opcode.CONST_WIDE -> frame.setWide(r[0], insn.literal)
            Opcode.CONST_STRING -> frame.set(r[0], (insn.ref as StringRef).value)
            Opcode.CONST_CLASS -> frame.set(r[0], DvmClass((insn.ref as TypeRef).desc))
            Opcode.MOVE, Opcode.MOVE_OBJECT -> frame.set(r[0], frame.get(r[1]))
            Opcode.MOVE_WIDE -> frame.setWide(r[0], frame.get(r[1]))
            Opcode.MOVE_RESULT -> {
                val t = frame.resultType
                if (t == "J" || t == "D") frame.setWide(r[0], frame.result) else frame.set(r[0], retype(frame.result, t))
                frame.result = null; frame.resultType = null
            }
            Opcode.MOVE_EXCEPTION -> {
                val pe = frame.pendingException
                frame.set(r[0], (pe as? DvmThrowable)?.let { it.obj ?: it } ?: pe ?: UnknownVal("Ljava/lang/Throwable;"))
                frame.pendingException = null
            }

            Opcode.ADD_INT -> binInt(frame, r) { a, b -> a + b }
            Opcode.SUB_INT -> binInt(frame, r) { a, b -> a - b }
            Opcode.MUL_INT -> binInt(frame, r) { a, b -> a * b }
            Opcode.AND_INT -> binInt(frame, r) { a, b -> a and b }
            Opcode.OR_INT -> binInt(frame, r) { a, b -> a or b }
            Opcode.XOR_INT -> binInt(frame, r) { a, b -> a xor b }
            Opcode.SHL_INT -> binInt(frame, r) { a, b -> a shl (b and 0x1f) }
            Opcode.SHR_INT -> binInt(frame, r) { a, b -> a shr (b and 0x1f) }
            Opcode.USHR_INT -> binInt(frame, r) { a, b -> a ushr (b and 0x1f) }
            Opcode.ADD_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a + b }
            Opcode.RSUB_INT -> litInt(frame, r, insn.literal.toInt()) { a, b -> b - a }
            Opcode.MUL_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a * b }
            Opcode.AND_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a and b }
            Opcode.OR_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a or b }
            Opcode.XOR_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a xor b }
            Opcode.SHL_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a shl (b and 0x1f) }
            Opcode.SHR_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a shr (b and 0x1f) }
            Opcode.USHR_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a ushr (b and 0x1f) }
            Opcode.NEG_INT -> unInt(frame, r) { -it }
            Opcode.NOT_INT -> unInt(frame, r) { it.inv() }

            Opcode.ADD_LONG -> binLong(frame, r) { a, b -> a + b }
            Opcode.SUB_LONG -> binLong(frame, r) { a, b -> a - b }
            Opcode.MUL_LONG -> binLong(frame, r) { a, b -> a * b }
            Opcode.AND_LONG -> binLong(frame, r) { a, b -> a and b }
            Opcode.OR_LONG -> binLong(frame, r) { a, b -> a or b }
            Opcode.XOR_LONG -> binLong(frame, r) { a, b -> a xor b }
            Opcode.SHL_LONG -> binLongShift(frame, r) { a, b -> a shl b }
            Opcode.SHR_LONG -> binLongShift(frame, r) { a, b -> a shr b }
            Opcode.USHR_LONG -> binLongShift(frame, r) { a, b -> a ushr b }
            Opcode.NEG_LONG -> { val a = frame.get(r[1]); frame.setWide(r[0], if (a.unk()) UnknownVal("J") else -cl(a)) }
            Opcode.NOT_LONG -> { val a = frame.get(r[1]); frame.setWide(r[0], if (a.unk()) UnknownVal("J") else cl(a).inv()) }
            Opcode.CMP_LONG -> { val a = frame.get(aReg(r)); val b = frame.get(bReg(r)); frame.set(r[0], if (a.unk() || b.unk()) UnknownVal("I") else cl(a).compareTo(cl(b))) }

            Opcode.ADD_FLOAT -> binFloat(frame, r) { a, b -> a + b }
            Opcode.SUB_FLOAT -> binFloat(frame, r) { a, b -> a - b }
            Opcode.MUL_FLOAT -> binFloat(frame, r) { a, b -> a * b }
            Opcode.DIV_FLOAT -> binFloat(frame, r) { a, b -> a / b }
            Opcode.REM_FLOAT -> binFloat(frame, r) { a, b -> a % b }
            Opcode.NEG_FLOAT -> { val a = frame.get(r[1]); frame.set(r[0], if (a.unk()) UnknownVal("F") else -cf(a)) }
            Opcode.ADD_DOUBLE -> binDouble(frame, r) { a, b -> a + b }
            Opcode.SUB_DOUBLE -> binDouble(frame, r) { a, b -> a - b }
            Opcode.MUL_DOUBLE -> binDouble(frame, r) { a, b -> a * b }
            Opcode.DIV_DOUBLE -> binDouble(frame, r) { a, b -> a / b }
            Opcode.REM_DOUBLE -> binDouble(frame, r) { a, b -> a % b }
            Opcode.NEG_DOUBLE -> { val a = frame.get(r[1]); frame.setWide(r[0], if (a.unk()) UnknownVal("D") else -cd(a)) }
            Opcode.CMPL_FLOAT -> cmpF(frame, r, -1)
            Opcode.CMPG_FLOAT -> cmpF(frame, r, 1)
            Opcode.CMPL_DOUBLE -> cmpD(frame, r, -1)
            Opcode.CMPG_DOUBLE -> cmpD(frame, r, 1)

            Opcode.INT_TO_LONG -> conv(frame, r, "J") { ci(it).toLong() }
            Opcode.INT_TO_FLOAT -> conv(frame, r, "F") { ci(it).toFloat() }
            Opcode.INT_TO_DOUBLE -> conv(frame, r, "D") { ci(it).toDouble() }
            Opcode.LONG_TO_INT -> conv(frame, r, "I") { cl(it).toInt() }
            Opcode.LONG_TO_FLOAT -> conv(frame, r, "F") { cl(it).toFloat() }
            Opcode.LONG_TO_DOUBLE -> conv(frame, r, "D") { cl(it).toDouble() }
            Opcode.FLOAT_TO_INT -> conv(frame, r, "I") { cf(it).toInt() }
            Opcode.FLOAT_TO_LONG -> conv(frame, r, "J") { cf(it).toLong() }
            Opcode.FLOAT_TO_DOUBLE -> conv(frame, r, "D") { cf(it).toDouble() }
            Opcode.DOUBLE_TO_INT -> conv(frame, r, "I") { cd(it).toInt() }
            Opcode.DOUBLE_TO_LONG -> conv(frame, r, "J") { cd(it).toLong() }
            Opcode.DOUBLE_TO_FLOAT -> conv(frame, r, "F") { cd(it).toFloat() }
            Opcode.INT_TO_BYTE -> conv(frame, r, "B") { ci(it).toByte() }
            Opcode.INT_TO_CHAR -> conv(frame, r, "C") { ci(it).toChar() }
            Opcode.INT_TO_SHORT -> conv(frame, r, "S") { ci(it).toShort() }

            Opcode.NEW_INSTANCE -> {
                val desc = (insn.ref as TypeRef).desc
                vm.ensureClinit(desc)
                frame.set(r[0], if (vm.source.classInfo(desc) != null) DvmObject(desc) else UninitHost(desc))
            }
            Opcode.NEW_ARRAY -> {
                val desc = (insn.ref as TypeRef).desc
                val len = frame.get(r[1])
                frame.set(r[0], if (len.unk()) UnknownVal(desc) else newArray(desc, ci(len)))
            }
            Opcode.ARRAY_LENGTH -> {
                val a = frame.get(r[1])
                frame.set(r[0], if (a == null || a.unk() || !a.javaClass.isArray) UnknownVal("I") else java.lang.reflect.Array.getLength(a))
            }
            Opcode.AGET, Opcode.AGET_BOOLEAN, Opcode.AGET_BYTE, Opcode.AGET_BYTE_BOOLEAN, Opcode.AGET_CHAR,
            Opcode.AGET_SHORT, Opcode.AGET_OBJECT, Opcode.AGET_WIDE -> {
                val a = frame.get(r[1]); val ix = frame.get(r[2])
                val v = if (a == null || a.unk() || ix.unk()) UnknownVal(agetType(a, insn.opcode))
                else runCatching { java.lang.reflect.Array.get(a, ci(ix)) }.getOrElse { throw VmAbort("aget") }
                if (insn.opcode == Opcode.AGET_WIDE) frame.setWide(r[0], v) else frame.set(r[0], v)
            }
            Opcode.APUT, Opcode.APUT_BOOLEAN, Opcode.APUT_BYTE, Opcode.APUT_BYTE_BOOLEAN, Opcode.APUT_CHAR,
            Opcode.APUT_SHORT, Opcode.APUT_OBJECT, Opcode.APUT_WIDE -> {
                val a = frame.get(r[1]); val ix = frame.get(r[2]); val v = frame.get(r[0])
                if (a != null && !a.unk()) {
                    if (ix.unk() || (v.unk() && a !is Array<*>)) frame.replace(a, UnknownVal(valueType(a)))
                    else runCatching { aput(a, ci(ix), v) }.getOrElse { throw VmAbort("aput") }
                }
            }
            Opcode.FILL_ARRAY_DATA -> {
                val a = frame.get(r[0]); val data = (insn.payload as? ArrayPayload)?.data
                if (a != null && !a.unk() && data != null) fillArray(a, data)
            }

            Opcode.IGET -> {
                val fr = insn.ref as FieldRef; val o = frame.get(r[1])
                fieldSet(frame, r[0], if (o is DvmObject) (if (o.fields.containsKey(key(fr))) o.fields[key(fr)] else defaultValue(fr.type)) else UnknownVal(fr.type), fr.type)
            }
            Opcode.IPUT -> { val fr = insn.ref as FieldRef; val o = frame.get(r[1]); if (o is DvmObject) o.fields[key(fr)] = retype(frame.get(r[0]), fr.type) }
            Opcode.SGET -> {
                val fr = insn.ref as FieldRef; vm.ensureClinit(fr.declClass)
                fieldSet(frame, r[0], when {
                    vm.source.classInfo(fr.declClass) != null -> {
                        val statics = vm.staticsOf(fr.declClass)
                        if (statics.containsKey(key(fr))) statics[key(fr)] else defaultValue(fr.type)
                    }
                    else -> vm.hostStaticField(fr.declClass, fr.name).let { if (it !== NotHandled) it else UnknownVal(fr.type) }
                }, fr.type)
            }
            Opcode.SPUT -> {
                val fr = insn.ref as FieldRef; vm.ensureClinit(fr.declClass)
                if (vm.source.classInfo(fr.declClass) != null) vm.staticsOf(fr.declClass)[key(fr)] = retype(frame.get(r[0]), fr.type)
            }

            Opcode.FILLED_NEW_ARRAY, Opcode.FILLED_NEW_ARRAY_RANGE -> {
                val desc = (insn.ref as TypeRef).desc
                val vals = r.map { frame.get(it) }
                frame.result = if (vals.any { it.unk() }) UnknownVal(desc) else filledArray(desc, vals)
                frame.resultType = desc
            }
            Opcode.CONST_METHOD_HANDLE -> frame.set(r[0], UnknownVal("Ljava/lang/invoke/MethodHandle;"))
            Opcode.CONST_METHOD_TYPE -> frame.set(r[0], UnknownVal("Ljava/lang/invoke/MethodType;"))
            Opcode.INVOKE_POLYMORPHIC, Opcode.INVOKE_POLYMORPHIC_RANGE -> {
                val ref = insn.ref as MethodRef
                frame.result = UnknownVal(ref.returnType); frame.resultType = ref.returnType
            }

            Opcode.MONITOR_ENTER, Opcode.MONITOR_EXIT -> {}
            Opcode.INSTANCE_OF -> {
                val o = frame.get(r[1]); val ct = (insn.ref as TypeRef).desc
                frame.set(r[0], when {
                    o.unk() -> UnknownVal("I")
                    o == null -> 0
                    else -> valueType(o)?.let { if (isAssignable(it, ct)) 1 else 0 } ?: UnknownVal("I")
                })
            }
            else -> throw VmAbort("unimplemented ${insn.opcode}")
        }
    }

    /**
     * Abstractly execute one instruction in place on [frame].
     *
     * Unknown operands produce unknown results instead of aborting, and undecidable branches report every
     * successor. Calls go through [resolver], or yield unknown values when it is null; heap accesses go through
     * [heap], or through the VM's concrete state when it is null.
     */
    fun absStep(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int, resolver: CallResolver? = null, heap: AbsHeap? = null): AbsResult {
        val res = absStepInner(method, insn, frame, pc, resolver, heap)
        if (insn.resultReg >= 0 && !res.returns) applyResultReg(insn, frame)
        return res
    }

    private fun applyResultReg(insn: DalvikInsn, frame: Frame) {
        val t = frame.resultType ?: return
        if (t == "V") return
        if (t == "J" || t == "D") frame.setWide(insn.resultReg, frame.result) else frame.set(insn.resultReg, retype(frame.result, t))
        frame.result = null; frame.resultType = null
    }

    private fun absStepInner(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int, resolver: CallResolver?, heap: AbsHeap?): AbsResult {
        val r = insn.regs
        return when (insn.opcode) {
            Opcode.RETURN -> AbsResult(EMPTY_INTS, retype(frame.get(r[0]), method.ref.returnType), true)
            Opcode.RETURN_VOID -> AbsResult(EMPTY_INTS, null, true)
            Opcode.GOTO -> AbsResult(intArrayOf(insn.target), null, false)
            Opcode.IF_EQ -> absBranch(method, insn, pc, eqAbs(frame.get(r[0]), frame.get(r[1])))
            Opcode.IF_NE -> absBranch(method, insn, pc, eqAbs(frame.get(r[0]), frame.get(r[1]))?.not())
            Opcode.IF_LT -> absBranch(method, insn, pc, relAbs(frame.get(r[0]), frame.get(r[1])) { a, b -> a < b })
            Opcode.IF_GE -> absBranch(method, insn, pc, relAbs(frame.get(r[0]), frame.get(r[1])) { a, b -> a >= b })
            Opcode.IF_GT -> absBranch(method, insn, pc, relAbs(frame.get(r[0]), frame.get(r[1])) { a, b -> a > b })
            Opcode.IF_LE -> absBranch(method, insn, pc, relAbs(frame.get(r[0]), frame.get(r[1])) { a, b -> a <= b })
            Opcode.IF_EQZ -> absBranch(method, insn, pc, zAbs(frame.get(r[0])))
            Opcode.IF_NEZ -> absBranch(method, insn, pc, zAbs(frame.get(r[0]))?.not())
            Opcode.IF_LTZ -> absBranch(method, insn, pc, relZAbs(frame.get(r[0])) { it < 0 })
            Opcode.IF_GEZ -> absBranch(method, insn, pc, relZAbs(frame.get(r[0])) { it >= 0 })
            Opcode.IF_GTZ -> absBranch(method, insn, pc, relZAbs(frame.get(r[0])) { it > 0 })
            Opcode.IF_LEZ -> absBranch(method, insn, pc, relZAbs(frame.get(r[0])) { it <= 0 })
            Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> absSwitch(method, insn, frame, pc)
            Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE,
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
            Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE -> {
                val ref = insn.ref as MethodRef
                if (resolver != null) {
                    val (res, rt) = resolver.resolve(insn, frame)
                    frame.result = res; frame.resultType = rt
                } else {
                    frame.result = UnknownVal(ref.returnType); frame.resultType = ref.returnType
                }
                fallResult(method, pc)
            }
            Opcode.DIV_INT -> { absDivInt(frame, r) { a, b -> a / b }; fallResult(method, pc) }
            Opcode.REM_INT -> { absDivInt(frame, r) { a, b -> a % b }; fallResult(method, pc) }
            Opcode.DIV_INT_LIT -> { absDivIntLit(frame, r, insn.literal.toInt()) { a, b -> a / b }; fallResult(method, pc) }
            Opcode.REM_INT_LIT -> { absDivIntLit(frame, r, insn.literal.toInt()) { a, b -> a % b }; fallResult(method, pc) }
            Opcode.DIV_LONG -> { absDivLong(frame, r) { a, b -> a / b }; fallResult(method, pc) }
            Opcode.REM_LONG -> { absDivLong(frame, r) { a, b -> a % b }; fallResult(method, pc) }
            Opcode.THROW -> AbsResult(EMPTY_INTS, null, false)
            Opcode.INVOKE_CUSTOM, Opcode.INVOKE_CUSTOM_RANGE -> { invokeCustom(insn, frame); fallResult(method, pc) }
            Opcode.CHECK_CAST -> fallResult(method, pc)
            Opcode.NEW_INSTANCE -> {
                if (heap != null) frame.set(r[0], heap.newInstance(siteOf(method, insn), (insn.ref as TypeRef).desc)) else dataTransfer(insn, frame)
                fallResult(method, pc)
            }
            Opcode.IGET -> {
                if (heap != null) { val fr = insn.ref as FieldRef; fieldSet(frame, r[0], heap.iget(frame.get(r[1]), fr.key, fr.type), fr.type) } else dataTransfer(insn, frame)
                fallResult(method, pc)
            }
            Opcode.IPUT -> {
                if (heap != null) { val fr = insn.ref as FieldRef; heap.iput(frame.get(r[1]), fr.key, retype(frame.get(r[0]), fr.type)) } else dataTransfer(insn, frame)
                fallResult(method, pc)
            }
            Opcode.SGET -> {
                val fr = insn.ref as FieldRef
                if (heap != null && vm.source.classInfo(fr.declClass) != null) fieldSet(frame, r[0], heap.sget(fr.declClass, fr.key, fr.type), fr.type) else dataTransfer(insn, frame)
                fallResult(method, pc)
            }
            Opcode.SPUT -> {
                val fr = insn.ref as FieldRef
                if (heap != null && vm.source.classInfo(fr.declClass) != null) heap.sput(fr.declClass, fr.key, retype(frame.get(r[0]), fr.type)) else dataTransfer(insn, frame)
                fallResult(method, pc)
            }
            else -> {
                val ok = runCatching { dataTransfer(insn, frame) }.isSuccess
                if (!ok) { frame.result = UnknownVal(null); frame.resultType = null; if (r.isNotEmpty()) frame.set(r[0], UnknownVal(null)) }
                fallResult(method, pc)
            }
        }
    }

    private fun siteOf(method: DexMethod, insn: DalvikInsn) = "${method.declClass}#${method.ref.shortId}@${insn.offset}"

    private fun fallResult(method: DexMethod, pc: Int) = AbsResult(fall(method, pc), null, false)

    private fun fall(method: DexMethod, pc: Int): IntArray =
        fallCache.getOrPut(method) {
            val n = method.insns.size
            Array(n) { i -> if (i + 1 < n) intArrayOf(method.insns[i + 1].offset) else EMPTY_INTS }
        }[pc]

    private fun absBranch(method: DexMethod, insn: DalvikInsn, pc: Int, cond: Boolean?): AbsResult = when (cond) {
        true -> AbsResult(intArrayOf(insn.target), null, false)
        false -> AbsResult(fall(method, pc), null, false)
        null -> AbsResult(fall(method, pc) + insn.target, null, false)
    }

    private fun absSwitch(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int): AbsResult {
        val sw = insn.payload as? SwitchPayload ?: return fallResult(method, pc)
        val sel = frame.get(insn.regs[0])
        if (sel.unk()) return AbsResult(fall(method, pc) + IntArray(sw.targets.size) { insn.offset + sw.targets[it] }, null, false)
        val k = sw.keys.indexOf(ci(sel))
        return if (k < 0) fallResult(method, pc) else AbsResult(intArrayOf(insn.offset + sw.targets[k]), null, false)
    }

    private fun eqAbs(a: Any?, b: Any?): Boolean? = if (a.unk() || b.unk()) null else eq(a, b)
    private fun zAbs(v: Any?): Boolean? = if (v.unk()) null else isZero(v)
    private fun relAbs(a: Any?, b: Any?, op: (Int, Int) -> Boolean): Boolean? = if (a.unk() || b.unk()) null else op(ci(a), ci(b))
    private fun relZAbs(v: Any?, op: (Int) -> Boolean): Boolean? = if (v.unk()) null else op(ci(v))

    private fun absDivInt(frame: Frame, r: IntArray, op: (Int, Int) -> Int) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.set(r[0], if (a.unk() || b.unk() || ci(b) == 0) UnknownVal("I") else op(ci(a), ci(b)))
    }
    private fun absDivIntLit(frame: Frame, r: IntArray, lit: Int, op: (Int, Int) -> Int) {
        val a = frame.get(r[1])
        frame.set(r[0], if (a.unk() || lit == 0) UnknownVal("I") else op(ci(a), lit))
    }
    private fun absDivLong(frame: Frame, r: IntArray, op: (Long, Long) -> Long) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.setWide(r[0], if (a.unk() || b.unk() || cl(b) == 0L) UnknownVal("J") else op(cl(a), cl(b)))
    }

    private fun invoke(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int, hasReceiver: Boolean, virtual: Boolean, superCall: Boolean): Step {
        val ref = insn.ref as MethodRef
        if (!hasReceiver) vm.ensureClinit(ref.declClass)
        val (recv, args) = gatherArgs(insn, frame, ref, hasReceiver)
        vm.hooks?.interceptors("${ref.declClass}->${ref.shortId}")?.takeIf { it.isNotEmpty() }?.let { hooks ->
            val call = HookCall("${ref.declClass}->${ref.shortId}", recv, args)
            for (h in hooks) h.onInvoke(call)
            if (call.replaced) {
                frame.result = retype(call.result, ref.returnType); frame.resultType = ref.returnType
                return Next(pc + 1)
            }
        }
        val reflected = try { tryReflect(ref, recv, args) } catch (t: DvmThrowable) { return routeThrow(method, insn.offset, t, frame) }
        reflected?.let { (res, rt) ->
            frame.result = res; frame.resultType = rt; return Next(pc + 1)
        }
        if (ref.name == "<init>" && recv is UninitHost) {
            frame.replace(recv, vm.hostExec.construct(recv.type, ref, args))
            return Next(pc + 1)
        }
        val target = when {
            superCall -> resolveVirtual(vm.source.classInfo(ref.declClass)?.superType, ref.shortId)
            virtual -> resolveVirtual((recv as? DvmObject)?.type ?: ref.declClass, ref.shortId)
            !hasReceiver -> resolveVirtual(ref.declClass, ref.shortId)
            else -> vm.source.method(ref.declClass, ref.shortId)
        }
        if (target == null && vm.nativeBridge != null && vm.source.isNative(ref.declClass, ref.shortId)) {
            val sig = "(${ref.argTypes.joinToString("")})${ref.returnType}"
            val nr = vm.nativeBridge.call(ref.declClass.removePrefix("L").removeSuffix(";"), ref.name, sig, args, recv)
            if (nr !== NotHandled) { frame.result = nr; frame.resultType = ref.returnType; return Next(pc + 1) }
        }
        val result = try {
            when {
                target != null -> vm.call(target, args, recv)
                hasReceiver -> vm.hostExec.invokeInstance(ref, recv, args)
                else -> vm.hostExec.invokeStatic(ref, args)
            }
        } catch (t: DvmThrowable) {
            return routeThrow(method, insn.offset, t, frame)
        } catch (e: StubNotImplemented) {
            vm.diagnose(Diagnostic.Kind.MISSING_STUB, ref, "no stub")
            UnknownVal(ref.returnType)
        }
        frame.result = result
        frame.resultType = ref.returnType
        return Next(pc + 1)
    }

    /**
     * Find the implementation of [shortId] starting at class [startType] and walking up the superclass chain.
     */
    fun resolveVirtualFor(startType: String?, shortId: String): DexMethod? = resolveVirtual(startType, shortId)

    /**
     * Read an invoke's receiver and arguments from [frame].
     */
    fun gatherArgs(insn: DalvikInsn, frame: Frame, ref: MethodRef, hasReceiver: Boolean): Pair<Any?, MutableList<Any?>> {
        val regs = insn.regs
        var i = 0
        val recv = if (hasReceiver) frame.get(regs[i++]) else null
        val args = ArrayList<Any?>(ref.argTypes.size)
        for (t in ref.argTypes) {
            args.add(frame.get(regs[i]))
            i += if (t == "J" || t == "D") 2 else 1
        }
        return recv to args
    }

    private fun invokeCustom(insn: DalvikInsn, frame: Frame) {
        val cs = insn.ref as? CallSiteRef
        if (cs == null) { frame.result = UnknownVal(null); frame.resultType = null; return }
        frame.result = evalCallSite(cs, gatherCustomArgs(insn, frame, cs.argTypes))
        frame.resultType = cs.returnType
    }

    private fun gatherCustomArgs(insn: DalvikInsn, frame: Frame, argTypes: List<String>): List<Any?> {
        val regs = insn.regs
        var i = 0
        val args = ArrayList<Any?>(argTypes.size)
        for (t in argTypes) {
            args.add(frame.get(regs.getOrNull(i) ?: return args))
            i += if (t == "J" || t == "D") 2 else 1
        }
        return args
    }

    private fun evalCallSite(cs: CallSiteRef, args: List<Any?>): Any? = when (cs.name) {
        "makeConcat" -> if (args.any { !concatable(it) }) UnknownVal("Ljava/lang/String;") else args.joinToString("") { concatStr(it) }
        "makeConcatWithConstants" -> {
            val recipe = cs.recipe
            if (recipe == null) UnknownVal("Ljava/lang/String;") else weaveRecipe(recipe, args, cs.constants)
        }
        else -> UnknownVal(cs.returnType)
    }

    private fun weaveRecipe(recipe: String, args: List<Any?>, constants: List<Any?>): Any? {
        val sb = StringBuilder(recipe.length)
        var ai = 0; var ci = 0
        for (c in recipe) when (c.code) {
            1 -> { val a = args.getOrNull(ai++); if (!concatable(a)) return UnknownVal("Ljava/lang/String;"); sb.append(concatStr(a)) }
            2 -> sb.append(concatStr(constants.getOrNull(ci++)))
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private fun concatable(v: Any?): Boolean =
        v !is UnknownVal && v !is DvmObject && v !is UninitHost && v !is DvmClass && v !is DvmMethodHandle && v !is DvmCtorHandle && v !is DvmField
    private fun concatStr(v: Any?): String = if (v == null) "null" else v.toString()

    private fun resolveVirtual(startType: String?, shortId: String): DexMethod? {
        var cur = startType
        while (cur != null) {
            vm.source.method(cur, shortId)?.let { return it }
            cur = vm.source.classInfo(cur)?.superType
        }
        return null
    }

    /**
     * Emulate a `java.lang.Class` or `java.lang.reflect` call on emulated values.
     *
     * @param allowInvoke whether `Method.invoke`, `Constructor.newInstance` and `Class.newInstance` may execute code
     * @return the result and its type, or null if [ref] is not a handled reflective call
     * @throws DvmThrowable for failures the Java API reports as exceptions (`Class.cast`, array bounds)
     */
    fun tryReflect(ref: MethodRef, recv: Any?, args: List<Any?>, allowInvoke: Boolean = true): Pair<Any?, String?>? =
        when (ref.declClass) {
            "Ljava/lang/Class;" -> reflectClass(ref.name, recv, args, allowInvoke)
            "Ljava/lang/reflect/Method;" -> reflectMethod(ref.name, recv, args, allowInvoke)
            "Ljava/lang/reflect/Constructor;" -> reflectConstructor(ref.name, recv, args, allowInvoke)
            "Ljava/lang/reflect/Field;" -> reflectField(ref.name, recv, args)
            "Ljava/lang/reflect/Array;" -> reflectArray(ref.name, args)
            "Ljava/lang/Object;" -> when (ref.name) {
                "getClass" -> valueType(recv)?.let { DvmClass(it) to CLASS }
                else -> if (isReflective(recv)) identityOp(ref.name, recv, args.map { classOf(it) ?: it }) else null
            }
            else -> null
        }

    private fun isReflective(v: Any?) = v is DvmClass || v is DvmMethodHandle || v is DvmCtorHandle || v is DvmField

    private fun identityOp(name: String, recv: Any?, args: List<Any?>): Pair<Any?, String?>? = when (name) {
        "hashCode" -> recv.hashCode() to "I"
        "equals" -> (recv == args.getOrNull(0)) to "Z"
        "toString" -> describe(recv) to STRING
        else -> null
    }

    private fun describe(v: Any?): String = when (v) {
        is DvmClass -> (if (isInterfaceType(v.desc)) "interface " else "class ") + className(v.desc)
        is DvmMethodHandle -> "${className(methodReturn(v))} ${className(methodOwner(v))}.${methodName(v)}(${methodParams(v).joinToString(",") { className(it) }})"
        is DvmCtorHandle -> "${className(v.owner)}(${v.params.joinToString(",") { className(it) }})"
        is DvmField -> "${className(v.ref.type)} ${className(v.ref.declClass)}.${v.ref.name}"
        else -> v.toString()
    }

    private fun reflectClass(name: String, recv: Any?, args: List<Any?>, allowInvoke: Boolean): Pair<Any?, String?>? {
        if (name == "forName") {
            val desc = descOfName(args.getOrNull(0) as? String ?: return null)
            val initialize = args.size < 2 || args[1].let { it.unk() || !isZero(it) }
            if (initialize) vm.ensureClinit(desc)
            return DvmClass(desc) to CLASS
        }
        val cls = classOf(recv) ?: return null
        val d = cls.desc
        return when (name) {
            "getMethod", "getDeclaredMethod" -> {
                val mname = args.getOrNull(0) as? String ?: return null
                val params = (args.getOrNull(1) as? Array<*>)?.map { classDesc(it) } ?: emptyList()
                resolveMethodHandle(d, mname, params, declaredOnly = name == "getDeclaredMethod") to METHOD
            }
            "getMethods", "getDeclaredMethods" -> methodHandles(d, declaredOnly = name == "getDeclaredMethods").toTypedArray<Any?>() to "[$METHOD"
            "getConstructor", "getDeclaredConstructor" -> {
                val params = (args.getOrNull(0) as? Array<*>)?.map { classDesc(it) } ?: emptyList()
                DvmCtorHandle(d, params) to CONSTRUCTOR
            }
            "getConstructors", "getDeclaredConstructors" -> ctorHandles(d).toTypedArray<Any?>() to "[$CONSTRUCTOR"
            "newInstance" -> if (allowInvoke) construct(d, emptyList(), emptyList()) to d else null
            "getField", "getDeclaredField" -> {
                val fname = args.getOrNull(0) as? String ?: return null
                resolveFieldHandle(d, fname, declaredOnly = name == "getDeclaredField") to FIELD
            }
            "getFields", "getDeclaredFields" -> fieldHandles(d, declaredOnly = name == "getDeclaredFields").toTypedArray<Any?>() to "[$FIELD"
            "getName" -> className(d) to STRING
            "getCanonicalName" -> canonicalName(d) to STRING
            "getTypeName" -> typeName(d) to STRING
            "getSimpleName" -> simpleName(d) to STRING
            "getPackageName" -> packageName(d) to STRING
            "getSuperclass" -> superclassOf(d)?.let { DvmClass(it) } to CLASS
            "getInterfaces" -> interfacesOf(d).map { DvmClass(it) as Any? }.toTypedArray() to "[$CLASS"
            "isInterface" -> isInterfaceType(d) to "Z"
            "isArray" -> d.startsWith("[") to "Z"
            "isPrimitive" -> (d.length == 1) to "Z"
            "isEnum" -> (superclassOf(d) == "Ljava/lang/Enum;") to "Z"
            "getComponentType" -> (if (d.startsWith("[")) DvmClass(d.substring(1)) else null) to CLASS
            "getEnumConstants" -> enumConstants(d) to "[$d"
            "getModifiers" -> classModifiers(d) to "I"
            "isInstance" -> args.getOrNull(0).let { o ->
                when {
                    o.unk() -> UnknownVal("Z")
                    o == null || o == 0 -> false
                    else -> valueType(o)?.let { isAssignable(it, d) } ?: UnknownVal("Z")
                }
            } to "Z"
            "isAssignableFrom" -> classOf(args.getOrNull(0))?.let { isAssignable(it.desc, d) } to "Z"
            "cast" -> args.getOrNull(0).let { o ->
                val vt = if (o == null || o == 0 || o.unk()) null else valueType(o)
                if (vt != null && !isAssignable(vt, d)) throw DvmThrowable("Ljava/lang/ClassCastException;", "${className(vt)} cannot be cast to ${className(d)}")
                o
            } to OBJECT
            "desiredAssertionStatus" -> false to "Z"
            "hashCode", "equals", "toString" -> identityOp(name, cls, args.map { classOf(it) ?: it })
            else -> null
        }
    }

    private fun classOf(v: Any?): DvmClass? = when (v) {
        is DvmClass -> v
        is Class<*> -> DvmClass(classDesc(v))
        else -> null
    }

    private fun reflectMethod(name: String, recv: Any?, args: List<Any?>, allowInvoke: Boolean): Pair<Any?, String?>? {
        val h = recv as? DvmMethodHandle ?: return null
        return when (name) {
            "invoke" -> if (allowInvoke) invokeHandle(h, args.getOrNull(0), args.getOrNull(1)) to OBJECT else null
            "setAccessible" -> null to "V"
            "getName" -> methodName(h) to STRING
            "getDeclaringClass" -> DvmClass(methodOwner(h)) to CLASS
            "getReturnType" -> DvmClass(methodReturn(h)) to CLASS
            "getParameterTypes" -> methodParams(h).map { DvmClass(it) as Any? }.toTypedArray() to "[$CLASS"
            "getParameterCount" -> methodParams(h).size to "I"
            "getModifiers" -> methodModifiers(h) to "I"
            "isVarArgs" -> (methodModifiers(h) and ACC_VARARGS != 0) to "Z"
            "hashCode", "equals", "toString" -> identityOp(name, h, args)
            else -> null
        }
    }

    private fun reflectConstructor(name: String, recv: Any?, args: List<Any?>, allowInvoke: Boolean): Pair<Any?, String?>? {
        val h = recv as? DvmCtorHandle ?: return null
        return when (name) {
            "newInstance" -> if (allowInvoke) {
                val argList = when (val a = args.getOrNull(0)) {
                    is Array<*> -> a.toList()
                    null, 0 -> emptyList()
                    else -> return UnknownVal(h.owner) to h.owner
                }
                construct(h.owner, h.params, argList) to h.owner
            } else null
            "setAccessible" -> null to "V"
            "getName" -> className(h.owner) to STRING
            "getDeclaringClass" -> DvmClass(h.owner) to CLASS
            "getParameterTypes" -> h.params.map { DvmClass(it) as Any? }.toTypedArray() to "[$CLASS"
            "getParameterCount" -> h.params.size to "I"
            "getModifiers" -> ctorModifiers(h) to "I"
            "hashCode", "equals", "toString" -> identityOp(name, h, args)
            else -> null
        }
    }

    private fun reflectField(name: String, recv: Any?, args: List<Any?>): Pair<Any?, String?>? {
        val f = recv as? DvmField ?: return null
        return when (name) {
            "get" -> reflectFieldGet(f, args.getOrNull(0)) to OBJECT
            "set" -> reflectFieldSet(f, args.getOrNull(0), args.getOrNull(1)) to "V"
            "getName" -> f.ref.name to STRING
            "getType" -> DvmClass(f.ref.type) to CLASS
            "getDeclaringClass" -> DvmClass(f.ref.declClass) to CLASS
            "getModifiers" -> fieldModifiers(f) to "I"
            "setAccessible" -> null to "V"
            "hashCode", "equals", "toString" -> identityOp(name, f, args)
            else -> {
                TYPED_GETTERS[name]?.let { t -> return retype(reflectFieldGet(f, args.getOrNull(0)), t) to t }
                if (name in TYPED_SETTERS) reflectFieldSet(f, args.getOrNull(0), args.getOrNull(1)) to "V" else null
            }
        }
    }

    private fun reflectArray(name: String, args: List<Any?>): Pair<Any?, String?>? {
        when (name) {
            "newInstance" -> {
                val elem = classOf(args.getOrNull(0))?.desc ?: return null
                return when (val n = args.getOrNull(1)) {
                    is IntArray -> multiArray(elem, n, 0) to "[".repeat(n.size) + elem
                    else -> (if (n.unk()) UnknownVal("[$elem") else newArrayChecked("[$elem", ci(n))) to "[$elem"
                }
            }
            "getLength" -> {
                val a = args.getOrNull(0)
                return (if (a == null || a.unk() || !a.javaClass.isArray) UnknownVal("I") else java.lang.reflect.Array.getLength(a)) to "I"
            }
        }
        val a = args.getOrNull(0) ?: return null
        val ix = args.getOrNull(1)
        if (a.unk() || ix.unk()) {
            val t = TYPED_GETTERS[name] ?: if (name == "get") OBJECT else return null
            return UnknownVal(t) to t
        }
        if (!a.javaClass.isArray) return null
        return when {
            name == "get" -> arrayGet(a, ci(ix)) to OBJECT
            name == "set" -> arraySet(a, ci(ix), args.getOrNull(2)) to "V"
            name in TYPED_GETTERS -> TYPED_GETTERS.getValue(name).let { t -> retype(arrayGet(a, ci(ix)), t) to t }
            name in TYPED_SETTERS -> arraySet(a, ci(ix), args.getOrNull(2)) to "V"
            else -> null
        }
    }

    private fun arrayGet(a: Any, ix: Int): Any? {
        if (ix < 0 || ix >= java.lang.reflect.Array.getLength(a)) throw DvmThrowable("Ljava/lang/ArrayIndexOutOfBoundsException;", "Index $ix out of bounds")
        return java.lang.reflect.Array.get(a, ix)
    }

    private fun arraySet(a: Any, ix: Int, v: Any?): Any? {
        if (ix < 0 || ix >= java.lang.reflect.Array.getLength(a)) throw DvmThrowable("Ljava/lang/ArrayIndexOutOfBoundsException;", "Index $ix out of bounds")
        if (v.unk() && a !is Array<*>) return null
        aput(a, ix, v)
        return null
    }

    private fun multiArray(elem: String, dims: IntArray, at: Int): Any {
        val desc = "[".repeat(dims.size - at) + elem
        val a = newArrayChecked(desc, dims[at])
        if (at + 1 < dims.size) for (i in 0 until dims[at]) (a as Array<Any?>)[i] = multiArray(elem, dims, at + 1)
        return a
    }

    private fun newArrayChecked(desc: String, len: Int): Any {
        if (len < 0) throw DvmThrowable("Ljava/lang/NegativeArraySizeException;", len.toString())
        return newArray(desc, len)
    }

    private fun construct(owner: String, params: List<String>, args: List<Any?>): Any? {
        vm.ensureClinit(owner)
        if (vm.source.classInfo(owner) == null) return vm.hostExec.construct(owner, MethodRef(owner, "<init>", params, "V"), args)
        val cands = vm.source.methodsByName(owner, "<init>")
        val init = cands.firstOrNull { it.ref.argTypes == params } ?: cands.firstOrNull { it.ref.argTypes.size == args.size } ?: return UnknownVal(owner)
        val obj = DvmObject(owner)
        vm.call(init, args, obj)
        return obj
    }

    private fun reflectFieldGet(f: DvmField, target: Any?): Any? {
        val fr = f.ref
        if (f.isStatic) {
            vm.ensureClinit(fr.declClass)
            if (vm.source.classInfo(fr.declClass) == null) {
                return vm.hostStaticField(fr.declClass, fr.name).let { if (it !== NotHandled) it else UnknownVal(fr.type) }
            }
            val statics = vm.staticsOf(fr.declClass)
            return if (statics.containsKey(fr.key)) statics[fr.key] else defaultValue(fr.type)
        }
        val obj = target as? DvmObject ?: return UnknownVal(fr.type)
        return if (obj.fields.containsKey(fr.key)) obj.fields[fr.key] else defaultValue(fr.type)
    }

    private fun reflectFieldSet(f: DvmField, target: Any?, value: Any?): Any? {
        val fr = f.ref
        val v = retype(value, fr.type)
        if (f.isStatic) {
            vm.ensureClinit(fr.declClass)
            if (vm.source.classInfo(fr.declClass) != null) vm.staticsOf(fr.declClass)[fr.key] = v
        } else {
            (target as? DvmObject)?.let { it.fields[fr.key] = v }
        }
        return null
    }

    private fun resolveFieldHandle(owner: String, name: String, declaredOnly: Boolean): Any? {
        var cur: String? = owner
        while (cur != null) {
            val info = vm.source.classInfo(cur) ?: break
            info.fields.firstOrNull { it.ref.name == name }?.let { return DvmField(it.ref, it.isStatic, it.accessFlags) }
            if (declaredOnly) return DvmField(FieldRef(owner, name, OBJECT), false)
            cur = info.superType
        }
        val hostOwner = cur ?: return DvmField(FieldRef(owner, name, OBJECT), false)
        val hostF = runCatching { hostClass(hostOwner).let { if (declaredOnly) it.getDeclaredField(name) else it.getField(name) } }.getOrNull()
        if (hostF != null) return DvmField(FieldRef(hostOwner, name, classDesc(hostF.type)), java.lang.reflect.Modifier.isStatic(hostF.modifiers), hostF.modifiers)
        return DvmField(FieldRef(owner, name, OBJECT), false)
    }

    private fun fieldHandles(owner: String, declaredOnly: Boolean): List<Any?> {
        val out = ArrayList<Any?>()
        val seen = HashSet<String>()
        var cur: String? = owner
        while (cur != null) {
            val info = vm.source.classInfo(cur) ?: break
            for (f in info.fields) if ((declaredOnly || f.accessFlags and ACC_PUBLIC != 0) && seen.add(f.ref.name)) out.add(DvmField(f.ref, f.isStatic, f.accessFlags))
            if (declaredOnly) return out
            cur = info.superType
        }
        val hostOwner = cur ?: return out
        runCatching { hostClass(hostOwner).let { if (declaredOnly) it.declaredFields else it.fields } }.getOrNull()?.forEach {
            if (seen.add(it.name)) out.add(DvmField(FieldRef(hostOwner, it.name, classDesc(it.type)), java.lang.reflect.Modifier.isStatic(it.modifiers), it.modifiers))
        }
        return out
    }

    private fun resolveMethodHandle(owner: String, name: String, params: List<String>, declaredOnly: Boolean): Any? {
        var cur: String? = owner
        while (cur != null) {
            val info = vm.source.classInfo(cur) ?: break
            val cands = vm.source.methodsByName(cur, name)
            val m = cands.firstOrNull { it.ref.argTypes == params } ?: cands.firstOrNull { it.ref.argTypes.size == params.size }
            if (m != null) return DvmMethodHandle(m, null)
            if (declaredOnly) break
            cur = info.superType
        }
        val hostOwner = cur ?: owner
        val hostM = runCatching {
            val hc = hostClass(hostOwner)
            val types = params.map { hostClass(it) }.toTypedArray()
            if (declaredOnly) hc.getDeclaredMethod(name, *types)
            else runCatching { hc.getMethod(name, *types) }.getOrElse { hc.getDeclaredMethod(name, *types) }
        }.getOrNull()
        if (hostM != null) return DvmMethodHandle(null, hostM)
        return DvmMethodHandle(null, null, MethodRef(owner, name, params, OBJECT))
    }

    private fun methodHandles(owner: String, declaredOnly: Boolean): List<Any?> {
        val out = ArrayList<Any?>()
        val seen = HashSet<String>()
        var cur: String? = owner
        while (cur != null) {
            val info = vm.source.classInfo(cur) ?: break
            for (m in vm.source.methodsOf(cur)) {
                if (m.ref.name == "<init>" || m.ref.name == "<clinit>") continue
                if ((declaredOnly || m.accessFlags and ACC_PUBLIC != 0) && seen.add(m.ref.shortId)) out.add(DvmMethodHandle(m, null))
            }
            if (declaredOnly) return out
            cur = info.superType
        }
        val hostOwner = cur ?: return out
        runCatching { hostClass(hostOwner).let { if (declaredOnly) it.declaredMethods else it.methods } }.getOrNull()?.forEach { m ->
            val id = m.name + "(" + m.parameterTypes.joinToString("") { classDesc(it) } + ")" + classDesc(m.returnType)
            if (seen.add(id)) out.add(DvmMethodHandle(null, m))
        }
        return out
    }

    private fun ctorHandles(owner: String): List<Any?> {
        if (vm.source.classInfo(owner) != null) return vm.source.methodsByName(owner, "<init>").map { DvmCtorHandle(owner, it.ref.argTypes) }
        return runCatching { hostClass(owner).declaredConstructors.map { c -> DvmCtorHandle(owner, c.parameterTypes.map { classDesc(it) }) as Any? } }.getOrDefault(emptyList())
    }

    private fun invokeHandle(h: DvmMethodHandle, target: Any?, argArr: Any?): Any? {
        val argList = when (argArr) {
            is Array<*> -> argArr.toList()
            null, 0 -> emptyList()
            else -> return UnknownVal(OBJECT)
        }
        h.dexMethod?.let { return vm.call(it, argList, if (it.isStatic) null else target) }
        h.hostMethod?.let { m -> return vm.hostExec.invokeResolved(m, target, argList, classDesc(m.returnType)) }
        return UnknownVal(OBJECT)
    }

    private fun methodOwner(h: DvmMethodHandle): String = h.dexMethod?.declClass ?: h.hostMethod?.let { classDesc(it.declaringClass) } ?: h.symbol!!.declClass
    private fun methodName(h: DvmMethodHandle): String = h.dexMethod?.ref?.name ?: h.hostMethod?.name ?: h.symbol!!.name
    private fun methodParams(h: DvmMethodHandle): List<String> = h.dexMethod?.ref?.argTypes ?: h.hostMethod?.parameterTypes?.map { classDesc(it) } ?: h.symbol!!.argTypes
    private fun methodReturn(h: DvmMethodHandle): String = h.dexMethod?.ref?.returnType ?: h.hostMethod?.let { classDesc(it.returnType) } ?: h.symbol!!.returnType
    private fun methodModifiers(h: DvmMethodHandle): Int = h.dexMethod?.accessFlags ?: h.hostMethod?.modifiers ?: 0

    private fun ctorModifiers(h: DvmCtorHandle): Int {
        if (vm.source.classInfo(h.owner) != null) return vm.source.methodsByName(h.owner, "<init>").firstOrNull { it.ref.argTypes == h.params }?.accessFlags ?: 0
        return runCatching { hostClass(h.owner).getDeclaredConstructor(*h.params.map { hostClass(it) }.toTypedArray()).modifiers }.getOrDefault(0)
    }

    private fun fieldModifiers(f: DvmField): Int {
        if (f.accessFlags != 0) return f.accessFlags
        return if (f.isStatic) java.lang.reflect.Modifier.STATIC else 0
    }

    private fun classModifiers(desc: String): Int {
        vm.source.classInfo(desc)?.let { return it.accessFlags }
        if (desc.length == 1 || desc.startsWith("[")) return java.lang.reflect.Modifier.PUBLIC or java.lang.reflect.Modifier.FINAL or java.lang.reflect.Modifier.ABSTRACT
        return runCatching { hostClass(desc).modifiers }.getOrDefault(0)
    }

    private fun superclassOf(desc: String): String? {
        if (desc.length == 1) return null
        if (desc.startsWith("[")) return OBJECT
        vm.source.classInfo(desc)?.let { return if (it.isInterface) null else it.superType }
        return runCatching { hostClass(desc).superclass?.let { classDesc(it) } }.getOrNull()
    }

    private fun interfacesOf(desc: String): List<String> {
        if (desc.length == 1) return emptyList()
        if (desc.startsWith("[")) return listOf("Ljava/lang/Cloneable;", "Ljava/io/Serializable;")
        vm.source.classInfo(desc)?.let { return it.interfaces }
        return runCatching { hostClass(desc).interfaces.map { classDesc(it) } }.getOrDefault(emptyList())
    }

    private fun isInterfaceType(desc: String): Boolean {
        vm.source.classInfo(desc)?.let { return it.isInterface }
        return runCatching { hostClass(desc).isInterface }.getOrDefault(false)
    }

    private fun enumConstants(desc: String): Any? {
        val info = vm.source.classInfo(desc)
        if (info != null) {
            if (info.superType != "Ljava/lang/Enum;") return null
            vm.ensureClinit(desc)
            val statics = vm.staticsOf(desc)
            return info.fields.filter { it.isStatic && it.ref.type == desc }.map { statics[it.ref.key] }.toTypedArray()
        }
        return runCatching {
            val c = hostClass(desc)
            if (!vm.host.canHandle(c)) UnknownVal("[$desc") else c.enumConstants as Array<*>
        }.getOrElse { UnknownVal("[$desc") }
    }

    private fun descOfName(name: String): String = when {
        name.startsWith("[") -> name.replace('.', '/')
        else -> PRIMITIVE_NAMES.entries.firstOrNull { it.value == name }?.key ?: "L" + name.replace('.', '/') + ";"
    }

    private fun className(desc: String): String = when {
        desc.startsWith("[") -> desc.replace('/', '.')
        desc.length == 1 -> PRIMITIVE_NAMES[desc] ?: desc
        else -> desc.removePrefix("L").removeSuffix(";").replace('/', '.')
    }

    private fun typeName(desc: String): String = if (desc.startsWith("[")) typeName(desc.substring(1)) + "[]" else className(desc)
    private fun canonicalName(desc: String): String = typeName(desc).replace('$', '.')
    private fun simpleName(desc: String): String = when {
        desc.startsWith("[") -> simpleName(desc.substring(1)) + "[]"
        desc.length == 1 -> className(desc)
        else -> desc.removePrefix("L").removeSuffix(";").substringAfterLast('/').substringAfterLast('$')
    }
    private fun packageName(desc: String): String = when {
        desc.startsWith("[") || desc.length == 1 -> "java.lang"
        else -> className(desc).substringBeforeLast('.', "")
    }

    private fun classDesc(c: Any?): String = when (c) {
        is DvmClass -> c.desc
        is Class<*> -> when {
            c == Integer.TYPE -> "I"; c == java.lang.Long.TYPE -> "J"; c == java.lang.Boolean.TYPE -> "Z"
            c == java.lang.Byte.TYPE -> "B"; c == Character.TYPE -> "C"; c == java.lang.Short.TYPE -> "S"
            c == java.lang.Float.TYPE -> "F"; c == java.lang.Double.TYPE -> "D"; c == Void.TYPE -> "V"
            c.isArray -> c.name.replace('.', '/')
            else -> "L" + c.name.replace('.', '/') + ";"
        }
        else -> "Ljava/lang/Object;"
    }

    private fun routeThrow(method: DexMethod, offset: Int, t: DvmThrowable, frame: Frame): Step {
        val h = findHandler(method, offset, t.type) ?: throw t
        val ti = method.offsetToIndex[h.offset] ?: throw t
        frame.pendingException = t
        return Next(ti)
    }

    private fun findHandler(method: DexMethod, offset: Int, throwType: String): Handler? {
        for (tb in method.tries) {
            if (offset in tb.start..tb.end) {
                for (h in tb.handlers) if (h.type == null || isAssignable(throwType, h.type)) return h
            }
        }
        return null
    }

    private fun isAssignable(sub: String, sup: String): Boolean {
        if (sub == sup || sup == "Ljava/lang/Object;") return true
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(sub)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!seen.add(cur)) continue
            if (cur == sup) return true
            val info = vm.source.classInfo(cur)
            if (info == null) {
                if (runCatching { hostClass(sup).isAssignableFrom(hostClass(cur)) }.getOrDefault(false)) return true
                continue
            }
            info.superType?.let { stack.addLast(it) }
            info.interfaces.forEach { stack.addLast(it) }
        }
        return false
    }

    private fun valueType(o: Any?): String? = when (o) {
        is DvmObject -> o.type
        is DvmClass -> "Ljava/lang/Class;"
        is DvmMethodHandle -> "Ljava/lang/reflect/Method;"
        is DvmCtorHandle -> "Ljava/lang/reflect/Constructor;"
        is DvmField -> "Ljava/lang/reflect/Field;"
        is String -> "Ljava/lang/String;"
        is BooleanArray -> "[Z"; is ByteArray -> "[B"; is CharArray -> "[C"; is ShortArray -> "[S"
        is IntArray -> "[I"; is LongArray -> "[J"; is FloatArray -> "[F"; is DoubleArray -> "[D"
        is Array<*> -> "[Ljava/lang/Object;"
        is UninitHost, is UnknownVal, null -> null
        else -> runCatching { "L" + o.javaClass.name.replace('.', '/') + ";" }.getOrNull()
    }

    private fun asThrowable(v: Any?): DvmThrowable = when (v) {
        is DvmThrowable -> v
        is DvmObject -> DvmThrowable(v.type, v.fields["detailMessage"] as? String, v)
        is UninitHost -> DvmThrowable(v.type, null, v)
        is Throwable -> DvmThrowable("L" + v.javaClass.name.replace('.', '/') + ";", v.message, v)
        else -> DvmThrowable("Ljava/lang/Throwable;", v?.toString())
    }

    private fun branch(method: DexMethod, insn: DalvikInsn, pc: Int, taken: Boolean): Step =
        if (taken) Next(idx(method, insn.target)) else Next(pc + 1)

    private fun idx(method: DexMethod, offset: Int): Int = method.offsetToIndex[offset] ?: throw VmAbort("bad target $offset")

    private fun Any?.unk(): Boolean = this is UnknownVal
    private fun key(fr: FieldRef) = fr.key

    private fun agetType(a: Any?, op: Opcode): String {
        if (a != null && !a.unk()) valueType(a)?.takeIf { it.startsWith("[") }?.let { return it.substring(1) }
        return when (op) {
            Opcode.AGET_OBJECT -> "Ljava/lang/Object;"; Opcode.AGET_WIDE -> "J"
            Opcode.AGET_BOOLEAN -> "Z"; Opcode.AGET_BYTE, Opcode.AGET_BYTE_BOOLEAN -> "B"
            Opcode.AGET_CHAR -> "C"; Opcode.AGET_SHORT -> "S"; else -> "I"
        }
    }

    private fun aReg(r: IntArray) = if (r.size >= 3) r[1] else r[0]
    private fun bReg(r: IntArray) = if (r.size >= 3) r[2] else r[1]

    private fun binInt(frame: Frame, r: IntArray, op: (Int, Int) -> Int) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.set(r[0], if (a.unk() || b.unk()) UnknownVal("I") else op(ci(a), ci(b)))
    }
    private fun divInt(method: DexMethod, insn: DalvikInsn, pc: Int, frame: Frame, op: (Int, Int) -> Int): Step {
        val r = insn.regs; val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        if (a.unk() || b.unk()) { frame.set(r[0], UnknownVal("I")); return Next(pc + 1) }
        if (ci(b) == 0) return routeThrow(method, insn.offset, DvmThrowable("Ljava/lang/ArithmeticException;", "/ by zero"), frame)
        frame.set(r[0], op(ci(a), ci(b))); return Next(pc + 1)
    }
    private fun divIntLit(method: DexMethod, insn: DalvikInsn, pc: Int, frame: Frame, lit: Int, op: (Int, Int) -> Int): Step {
        val r = insn.regs; val a = frame.get(r[1])
        if (a.unk()) { frame.set(r[0], UnknownVal("I")); return Next(pc + 1) }
        if (lit == 0) return routeThrow(method, insn.offset, DvmThrowable("Ljava/lang/ArithmeticException;", "/ by zero"), frame)
        frame.set(r[0], op(ci(a), lit)); return Next(pc + 1)
    }
    private fun divLong(method: DexMethod, insn: DalvikInsn, pc: Int, frame: Frame, op: (Long, Long) -> Long): Step {
        val r = insn.regs; val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        if (a.unk() || b.unk()) { frame.setWide(r[0], UnknownVal("J")); return Next(pc + 1) }
        if (cl(b) == 0L) return routeThrow(method, insn.offset, DvmThrowable("Ljava/lang/ArithmeticException;", "/ by zero"), frame)
        frame.setWide(r[0], op(cl(a), cl(b))); return Next(pc + 1)
    }
    private fun litInt(frame: Frame, r: IntArray, lit: Int, op: (Int, Int) -> Int) {
        val a = frame.get(r[1])
        frame.set(r[0], if (a.unk()) UnknownVal("I") else op(ci(a), lit))
    }
    private fun unInt(frame: Frame, r: IntArray, op: (Int) -> Int) {
        val a = frame.get(r[1]); frame.set(r[0], if (a.unk()) UnknownVal("I") else op(ci(a)))
    }
    private fun binLong(frame: Frame, r: IntArray, op: (Long, Long) -> Long) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.setWide(r[0], if (a.unk() || b.unk()) UnknownVal("J") else op(cl(a), cl(b)))
    }
    private fun binLongShift(frame: Frame, r: IntArray, op: (Long, Int) -> Long) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.setWide(r[0], if (a.unk() || b.unk()) UnknownVal("J") else op(cl(a), ci(b) and 0x3f))
    }
    private fun binFloat(frame: Frame, r: IntArray, op: (Float, Float) -> Float) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.set(r[0], if (a.unk() || b.unk()) UnknownVal("F") else op(cf(a), cf(b)))
    }
    private fun binDouble(frame: Frame, r: IntArray, op: (Double, Double) -> Double) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.setWide(r[0], if (a.unk() || b.unk()) UnknownVal("D") else op(cd(a), cd(b)))
    }
    private fun cmpF(frame: Frame, r: IntArray, nan: Int) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.set(r[0], if (a.unk() || b.unk()) UnknownVal("I") else cmpFloat(cf(a), cf(b), nan))
    }
    private fun cmpD(frame: Frame, r: IntArray, nan: Int) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.set(r[0], if (a.unk() || b.unk()) UnknownVal("I") else cmpDouble(cd(a), cd(b), nan))
    }
    private fun conv(frame: Frame, r: IntArray, type: String, op: (Any?) -> Any) {
        val a = frame.get(r[1])
        val v = if (a.unk()) UnknownVal(type) else op(a)
        if (type == "J" || type == "D") frame.setWide(r[0], v) else frame.set(r[0], v)
    }

    private fun cmpFloat(a: Float, b: Float, nan: Int) = if (a.isNaN() || b.isNaN()) nan else a.compareTo(b).coerceIn(-1, 1)
    private fun cmpDouble(a: Double, b: Double, nan: Int) = if (a.isNaN() || b.isNaN()) nan else a.compareTo(b).coerceIn(-1, 1)

    private fun isNumeric(v: Any?) = v is Int || v is Long || v is Boolean || v is Char || v is Byte || v is Short
    private fun isZero(v: Any?): Boolean { if (v.unk()) throw VmAbort("unknown branch"); return if (isNumeric(v)) ci(v) == 0 else v == null }
    private fun ci2(v: Any?): Int { if (v.unk()) throw VmAbort("unknown branch"); return ci(v) }
    private fun eq(a: Any?, b: Any?): Boolean {
        if (a.unk() || b.unk()) throw VmAbort("unknown branch")
        if (isNumeric(a) && isNumeric(b)) return ci(a) == ci(b)
        if (isReflective(a) || isReflective(b) || a is Class<*> || b is Class<*>) return (classOf(a) ?: a) == (classOf(b) ?: b)
        return a === b
    }

    private fun ci(v: Any?): Int = when (v) { is Int -> v; is Boolean -> if (v) 1 else 0; is Char -> v.code; is Byte -> v.toInt(); is Short -> v.toInt(); is Long -> v.toInt(); else -> 0 }
    private fun cl(v: Any?): Long = when (v) { is Long -> v; is Int -> v.toLong(); else -> ci(v).toLong() }
    private fun cf(v: Any?): Float = when (v) { is Float -> v; is Double -> v.toFloat(); is Int -> Float.fromBits(v); is Long -> v.toFloat(); else -> ci(v).toFloat() }
    private fun cd(v: Any?): Double = when (v) { is Double -> v; is Float -> v.toDouble(); is Long -> Double.fromBits(v); else -> ci(v).toDouble() }

    private fun defaultValue(type: String): Any? = when (type) {
        "I", "B", "S", "C" -> 0; "J" -> 0L; "F" -> 0f; "D" -> 0.0; "Z" -> false
        else -> null
    }

    private fun fieldSet(frame: Frame, dest: Int, raw: Any?, t: String) {
        val v = retype(raw, t)
        if (t == "J" || t == "D") frame.setWide(dest, v) else frame.set(dest, v)
    }

    private fun retype(v: Any?, t: String?): Any? = when {
        v.unk() || v == null || !isNumeric(v) -> v
        t == "C" -> ci(v).toChar(); t == "B" -> ci(v).toByte(); t == "S" -> ci(v).toShort(); t == "Z" -> ci(v) != 0
        t == "I" -> ci(v); t == "J" -> cl(v); t == "F" -> cf(v); t == "D" -> cd(v)
        else -> v
    }

    private fun filledArray(desc: String, vals: List<Any?>): Any {
        val a = newArray(desc, vals.size)
        for (i in vals.indices) aput(a, i, vals[i])
        return a
    }

    private fun newArray(desc: String, len: Int): Any = when (desc) {
        "[I" -> IntArray(len); "[J" -> LongArray(len); "[B" -> ByteArray(len); "[C" -> CharArray(len)
        "[S" -> ShortArray(len); "[Z" -> BooleanArray(len); "[F" -> FloatArray(len); "[D" -> DoubleArray(len)
        else -> arrayOfNulls<Any?>(len)
    }

    @Suppress("UNCHECKED_CAST")
    private fun aput(a: Any, ix: Int, v: Any?) {
        when (a) {
            is IntArray -> a[ix] = ci(v); is ByteArray -> a[ix] = ci(v).toByte(); is CharArray -> a[ix] = ci(v).toChar()
            is ShortArray -> a[ix] = ci(v).toShort(); is BooleanArray -> a[ix] = ci(v) != 0; is LongArray -> a[ix] = cl(v)
            is FloatArray -> a[ix] = cf(v); is DoubleArray -> a[ix] = cd(v)
            else -> (a as Array<Any?>)[ix] = v
        }
    }

    private fun fillArray(a: Any, data: Any) {
        val n = java.lang.reflect.Array.getLength(data)
        for (k in 0 until n) {
            val x = java.lang.reflect.Array.get(data, k) as Number
            when (a) {
                is IntArray -> a[k] = x.toInt(); is ByteArray -> a[k] = x.toByte(); is ShortArray -> a[k] = x.toShort()
                is CharArray -> a[k] = x.toInt().toChar(); is BooleanArray -> a[k] = x.toInt() != 0; is LongArray -> a[k] = x.toLong()
                is FloatArray -> a[k] = Float.fromBits(x.toInt()); is DoubleArray -> a[k] = Double.fromBits(x.toLong())
            }
        }
    }
}
