package jadx.plugins.emu.exec

/**
 * Resource limits for one emulation run.
 *
 * @property maxSteps maximum instructions executed per method activation before aborting
 * @property maxMillis wall-clock deadline for a top-level invoke; 0 or negative disables the deadline
 * @property maxDepth maximum nested call depth; deeper calls return an unknown value instead of executing
 */
public class ExecLimits @JvmOverloads constructor(
    public val maxSteps: Int = 500_000,
    public val maxMillis: Long = 2_000,
    public val maxDepth: Int = 64,
)
