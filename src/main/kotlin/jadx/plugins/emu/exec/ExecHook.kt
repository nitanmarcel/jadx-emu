package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.model.DalvikInsn
import jadx.plugins.emu.exec.model.DexMethod

/**
 * Instruction-level tracing callback, attached via [Vm.hook].
 *
 * Callbacks run synchronously on the emulation thread; throwing [VmAbort] from them stops the run.
 */
public interface ExecHook {
    /**
     * Called when [method] is entered, after parameters are bound into [frame].
     */
    public fun onEnter(method: DexMethod, frame: Frame) {}

    /**
     * Called before each instruction executes.
     *
     * @param pc index of [insn] in [DexMethod.insns]
     */
    public fun onStep(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int)

    /**
     * Called when [method] returns or throws.
     */
    public fun onExit(method: DexMethod) {}
}
