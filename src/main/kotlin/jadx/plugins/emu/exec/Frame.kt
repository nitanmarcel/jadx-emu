package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.runtime.WideHigh

/**
 * Register file of one method activation.
 *
 * @property regs register values; wide values occupy the lower register with [WideHigh] in the upper one
 * @property result value produced by the last invoke or `filled-new-array`, consumed by `move-result*`
 * @property resultType descriptor of [result]
 * @property pendingException exception delivered to a handler, consumed by `move-exception`
 */
public class Frame(size: Int) {
    public val regs: Array<Any?> = arrayOfNulls(size)
    public var result: Any? = null
    public var resultType: String? = null
    public var pendingException: Any? = null

    /**
     * Read register [r].
     */
    public fun get(r: Int): Any? = regs[r]

    /**
     * Write a non-wide value to register [r].
     */
    public fun set(r: Int, v: Any?) { regs[r] = v }

    /**
     * Write a wide value to the register pair starting at [r].
     */
    public fun setWide(r: Int, v: Any?) {
        regs[r] = v
        if (r + 1 < regs.size) regs[r + 1] = WideHigh
    }

    /**
     * Replace every register holding exactly [old] (by identity) with [new].
     * Used when an [jadx.plugins.emu.exec.runtime.UninitHost] placeholder becomes a real object.
     */
    public fun replace(old: Any?, new: Any?) {
        if (old == null) return
        for (i in regs.indices) if (regs[i] === old) regs[i] = new
    }
}
