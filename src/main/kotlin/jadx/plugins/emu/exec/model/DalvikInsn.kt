package jadx.plugins.emu.exec.model

import jadx.api.plugins.input.insns.Opcode

/**
 * Out-of-line data attached to an instruction (switch tables and `fill-array-data` payloads).
 */
public sealed interface InsnPayload

/**
 * Payload of a `packed-switch` / `sparse-switch` instruction.
 *
 * @property keys case keys, parallel to [targets]
 * @property targets branch targets as offsets relative to the switch instruction
 */
public class SwitchPayload(public val keys: IntArray, public val targets: IntArray) : InsnPayload

/**
 * Payload of a `fill-array-data` instruction.
 *
 * @property size number of elements
 * @property elementSize size of one element in bytes
 * @property data the decoded primitive array (e.g. `IntArray`, `ByteArray`), or null if unavailable
 */
public class ArrayPayload(public val size: Int, public val elementSize: Int, public val data: Any?) : InsnPayload

/**
 * One decoded Dalvik instruction.
 *
 * @property opcode normalized jadx opcode
 * @property offset code-unit offset of this instruction inside the method
 * @property regs register operands in encoding order; for defining instructions the destination is `regs[0]`
 * @property literal immediate literal for `const*` and `*-lit` instructions, otherwise 0
 * @property target absolute offset of the branch target for `goto`/`if-*`, or of the payload table for
 *   switch and `fill-array-data`; unused for other opcodes
 * @property ref the constant-pool item referenced by this instruction (string, type, field, method, call site), if any
 * @property payload resolved switch/array payload for instructions that have one
 * @property resultReg register that receives the invoke result directly, without a following `move-result`;
 *   -1 for dex input, set by jadx's Java bytecode input
 */
public class DalvikInsn(
    public val opcode: Opcode,
    public val offset: Int,
    public val regs: IntArray,
    public val literal: Long,
    public val target: Int,
    public val ref: InsnRef?,
    public var payload: InsnPayload?,
    public val resultReg: Int = -1,
)
