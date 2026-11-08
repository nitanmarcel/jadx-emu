@file:JvmName("AbsSupport")

package jadx.plugins.emu.exec.graph

import jadx.plugins.emu.exec.CallResolver
import jadx.plugins.emu.exec.Interpreter
import jadx.plugins.emu.exec.StubNotImplemented
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.model.MethodRef
import jadx.plugins.emu.exec.runtime.DvmThrowable
import jadx.plugins.emu.exec.runtime.UnknownVal
import jadx.api.plugins.input.insns.Opcode

/**
 * Registers of [method] at entry with every parameter, including `this`, set to an [UnknownVal] of its type.
 */
public fun entryFrameRegs(method: DexMethod): Array<Any?> {
    val regs = arrayOfNulls<Any?>(method.registersCount)
    var r = method.argsStartReg
    if (!method.isStatic) { regs[r] = UnknownVal(method.declClass); r++ }
    for (t in method.ref.argTypes) {
        regs[r] = UnknownVal(t); r += if (t == "J" || t == "D") 2 else 1
    }
    return regs
}

/**
 * For each instruction index covered by a try block, the offsets of its handlers.
 */
public fun handlerEdgesOf(method: DexMethod): Map<Int, IntArray> {
    if (method.tries.isEmpty()) return emptyMap()
    val m = HashMap<Int, IntArray>()
    for (i in method.insns.indices) {
        val off = method.insns[i].offset
        val hs = method.tries.filter { off in it.start..it.end }.flatMap { it.handlers }.map { it.offset }.distinct()
        if (hs.isNotEmpty()) m[i] = hs.toIntArray()
    }
    return m
}

/**
 * A [CallResolver] that executes host-class calls through [Vm.hostExec] and treats every other call as unknown.
 */
public fun hostOnlyResolver(vm: Vm, interp: Interpreter): CallResolver = CallResolver { insn, frame ->
    val ref = insn.ref as MethodRef
    val op = insn.opcode
    val hasReceiver = op != Opcode.INVOKE_STATIC && op != Opcode.INVOKE_STATIC_RANGE
    val (recv, args) = interp.gatherArgs(insn, frame, ref, hasReceiver)
    val v = try {
        if (hasReceiver) vm.hostExec.invokeInstance(ref, recv, args) else vm.hostExec.invokeStatic(ref, args)
    } catch (e: StubNotImplemented) {
        UnknownVal(ref.returnType)
    } catch (e: DvmThrowable) {
        UnknownVal(ref.returnType)
    }
    v to ref.returnType
}
