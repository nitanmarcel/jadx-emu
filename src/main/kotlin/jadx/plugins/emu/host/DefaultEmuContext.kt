package jadx.plugins.emu.host

import jadx.api.plugins.JadxPluginContext
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.input.DexInputSource
import org.slf4j.LoggerFactory

internal class DefaultEmuContext(host: JadxPluginContext, options: EmuOptions) : EngineEmuContext(
    { load(host) },
    { source -> EngineContext(source, options.limits, options.hostBoundary(), options.hooks()) },
    options.limits,
) {
    private companion object {
        val LOG = LoggerFactory.getLogger(DefaultEmuContext::class.java)

        fun load(host: JadxPluginContext): MethodSource {
            val start = System.currentTimeMillis()
            val root = runCatching { host.decompiler.root }.getOrNull()
            val src = if (root != null) {
                val classes = root.classes.mapNotNull { it.clsData }
                LOG.debug("jadx-emu: building emulator source from {} classes loaded by jadx", classes.size)
                DexInputSource.fromClasses(classes)
            } else {
                val files = host.args.inputFiles
                LOG.debug("jadx-emu: jadx has not loaded inputs yet, parsing {} input file(s) directly", files.size)
                DexInputSource.load(files)
            }
            LOG.info("jadx-emu: emulator source ready, {} methods in {}ms", src.allMethods().size, System.currentTimeMillis() - start)
            return src
        }
    }
}
