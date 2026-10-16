package jadx.plugins.emu.host

import jadx.plugins.emu.api.EmuContext
import jadx.plugins.emu.api.EmuTarget
import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.exec.DiagnosticSink
import jadx.plugins.emu.exec.EmuWorld
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.HostBoundary
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.graph.Dataflow
import jadx.plugins.emu.exec.input.DexInputSource
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.worldVm
import java.nio.file.Files
import java.nio.file.Path

internal open class EngineEmuContext(
    sourceProvider: () -> MethodSource,
    engineProvider: (MethodSource) -> EngineContext,
    override val limits: ExecLimits,
) : EmuContext {

    override val source: MethodSource by lazy(sourceProvider)

    val engine: EngineContext by lazy { engineProvider(source) }

    val world: EmuWorld by lazy { EmuWorld(ctx = engine) }

    override val host: HostBoundary get() = engine.host

    override val diagnostics: MutableList<DiagnosticSink> get() = engine.diagnostics

    override fun newVm(limits: ExecLimits): Vm = worldVm(source, world, limits)

    override fun newDataflow(limits: ExecLimits): Dataflow = Dataflow(engine.newVm(limits))

    override fun method(descriptor: String): DexMethod? {
        val i = descriptor.indexOf("->")
        if (i <= 0) return null
        return source.method(descriptor.substring(0, i), descriptor.substring(i + 2))
    }

    override fun method(target: EmuTarget): DexMethod? {
        val shortId = target.shortId ?: return null
        return source.method("L" + target.rawClassName.replace('.', '/') + ";", shortId)
    }

    override fun loadDex(file: Path): MethodSource = DexInputSource.load(file.toFile())

    override fun loadDex(bytes: ByteArray): MethodSource {
        val tmp = Files.createTempFile("jadx-emu", ".dex")
        try {
            Files.write(tmp, bytes)
            return DexInputSource.load(tmp.toFile())
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override fun fork(source: MethodSource, hooks: Hooks?, limits: ExecLimits): EmuContext {
        val parent = engine
        return EngineEmuContext({ source }, { s ->
            EngineContext(s, limits, parent.host, hooks ?: Hooks(parent.hooks.env)).also {
                it.diagnostics.clear()
                it.diagnostics.addAll(parent.diagnostics)
            }
        }, limits)
    }
}
