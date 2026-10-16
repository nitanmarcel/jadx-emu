package jadx.plugins.emu.exec

import jadx.plugins.emu.api.Hooks

/**
 * Mutable emulation state shared between successive [Vm] runs: static fields and hooks.
 *
 * Create one per session and build VMs from it with [worldVm] so that class initialization and
 * static writes persist across calls.
 *
 * @property ctx optional shared engine context providing the host policy, environment and
 *   static-initializer snapshots
 * @property statics static field storage, class descriptor → (field key → value)
 * @property hooks method hooks, framework stubs and device environment; defaults to the context's
 */
class EmuWorld(
    val ctx: EngineContext? = null,
    val statics: HashMap<String, HashMap<String, Any?>> = HashMap(),
    val hooks: Hooks = ctx?.hooks ?: Hooks(),
)

/**
 * Build a concrete-execution [Vm] over [src] that reads and writes the state in [world].
 */
fun worldVm(src: MethodSource, world: EmuWorld, limits: ExecLimits = ExecLimits()): Vm =
    Vm(
        src, host = world.ctx?.host ?: HostBoundary(), limits = limits, hooks = world.hooks, ctx = world.ctx,
        statics = world.statics, androidEnvUnknown = false,
        diagnostics = world.ctx?.diagnostics ?: emptyList(),
    )
