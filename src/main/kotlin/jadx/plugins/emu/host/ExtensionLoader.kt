package jadx.plugins.emu.host

import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.options.JadxPluginOptions
import jadx.api.plugins.pass.JadxPass
import jadx.plugins.emu.api.EmuExtension
import jadx.plugins.emu.api.EmuExtensionContext
import jadx.plugins.emu.api.EmuExtensionInfo
import jadx.plugins.emu.api.EmuTarget
import jadx.plugins.emu.api.ExtensionMode
import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.api.TargetStore
import jadx.plugins.emu.host.gui.PopupSubmenu
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

internal enum class ExtensionStatus { ACTIVE, DISABLED, NOT_APPLICABLE, INCOMPATIBLE, FAILED, REMOVED }

internal class LoadedExtension(
    val extension: EmuExtension,
    val info: EmuExtensionInfo,
    val origin: Path,
) {
    var status: ExtensionStatus = ExtensionStatus.DISABLED
        internal set
    var error: Throwable? = null
        internal set
    val passes = ArrayList<JadxPass>()
    var hooks: Hooks? = null
        internal set
}

internal class ExtensionLoader(
    private val host: JadxPluginContext,
    private val options: CompositeOptions,
    private val targets: TargetRegistry,
    private val extensionsDir: Path,
    private val dataRoot: Path,
) : Closeable {

    private val classLoaders = ArrayList<URLClassLoader>()
    private val popupSubmenu = PopupSubmenu("Jadx Emu")
    private val _extensions = ArrayList<LoadedExtension>()
    val extensions: List<LoadedExtension> get() = _extensions

    val directory: Path get() = extensionsDir

    fun remove(loaded: LoadedExtension): Boolean {
        val p = loaded.origin
        val result = runCatching { if (p.isDirectory()) p.toFile().deleteRecursively() else Files.deleteIfExists(p) }
        val ok = !Files.exists(p)
        if (ok) {
            LOG.info("jadx-emu: removed extension '{}' ({})", loaded.info.id, p)
        } else {
            LOG.warn("jadx-emu: could not delete {} now, it will be removed on exit", p, result.exceptionOrNull())
            p.toFile().deleteOnExit()
        }
        loaded.status = ExtensionStatus.REMOVED
        _extensions.remove(loaded)
        return ok
    }

    fun discover() {
        Files.createDirectories(extensionsDir)
        val candidates = extensionsDir.listDirectoryEntries().filter { it.isDirectory() || it.extension == "jar" }.sorted()
        val seen = HashMap<String, Path>()
        for (path in candidates) {
            try {
                for (ext in loadFrom(path)) {
                    val info = ext.info()
                    val dup = seen.put(info.id, path)
                    if (dup != null) {
                        LOG.warn("jadx-emu: duplicate extension id '{}' in {} (already loaded from {}), skipping", info.id, path, dup)
                        continue
                    }
                    _extensions += LoadedExtension(ext, info, path)
                }
            } catch (e: Throwable) {
                LOG.error("jadx-emu: failed to load extension from {}", path, e)
            }
        }
    }

    fun initAll(emu: EngineEmuContext) {
        val options = this.options.host
        for (loaded in _extensions) {
            val info = loaded.info
            loaded.status = when {
                !EmuVersion.isCompatible(info.requiredEmuVersion) -> ExtensionStatus.INCOMPATIBLE
                info.mode == ExtensionMode.AUTO && info.id !in options.enabledExtensions -> ExtensionStatus.DISABLED
                else -> ExtensionStatus.ACTIVE
            }
            if (loaded.status == ExtensionStatus.INCOMPATIBLE) {
                LOG.warn("jadx-emu: extension '{}' requires jadx-emu {} but current is {}, skipping",
                    info.id, info.requiredEmuVersion, EmuVersion.current)
                continue
            }
            if (loaded.status != ExtensionStatus.ACTIVE) {
                LOG.debug("jadx-emu: extension '{}' disabled", info.id)
                continue
            }
            try {
                if (info.mode == ExtensionMode.AUTO && !loaded.extension.applies(emu)) {
                    loaded.status = ExtensionStatus.NOT_APPLICABLE
                    LOG.info("jadx-emu: extension '{}' does not apply to this input, skipping", info.id)
                    continue
                }
                val ctx = ExtensionContextImpl(loaded, emu)
                loaded.hooks = ctx.hooks
                loaded.extension.init(ctx)
                LOG.info("jadx-emu: extension '{}' ({}) initialized, {} pass(es)", info.id, info.mode, loaded.passes.size)
            } catch (e: Throwable) {
                loaded.status = ExtensionStatus.FAILED
                loaded.error = e
                LOG.error("jadx-emu: extension '{}' failed to init", info.id, e)
            }
        }
    }

    fun unloadAll() {
        for (loaded in _extensions) {
            if (loaded.status != ExtensionStatus.ACTIVE) continue
            try {
                loaded.extension.unload()
            } catch (e: Throwable) {
                LOG.warn("jadx-emu: extension '{}' failed to unload", loaded.info.id, e)
            } finally {
                loaded.hooks?.clear()
            }
        }
    }

    override fun close() {
        unloadAll()
        popupSubmenu.dispose()
        options.clearExtensions()
        _extensions.clear()
        for (cl in classLoaders) runCatching { cl.close() }
        classLoaders.clear()
    }

    private fun loadFrom(path: Path): List<EmuExtension> {
        val jars = when {
            path.isDirectory() -> path.listDirectoryEntries("*.jar")
            path.isRegularFile() -> listOf(path)
            else -> emptyList()
        }
        if (jars.isEmpty()) return emptyList()
        val cl = URLClassLoader(
            "jadx-emu-extension:${path.name}",
            jars.map { it.toUri().toURL() }.toTypedArray(),
            ExtensionLoader::class.java.classLoader,
        )
        classLoaders += cl
        return ServiceLoader.load(EmuExtension::class.java, cl)
            .stream()
            .filter { it.type().classLoader === cl }
            .map { it.get() }
            .toList()
    }

    private inner class ExtensionContextImpl(
        private val loaded: LoadedExtension,
        override val emu: EngineEmuContext,
    ) : EmuExtensionContext {
        override val jadx: JadxPluginContext get() = host
        override val hooks: Hooks = emu.world.hooks.scope()
        override val log: Logger = LoggerFactory.getLogger("jadx-emu.${loaded.info.id}")
        override val dataDir: Path by lazy { dataRoot.resolve(loaded.info.id).also { Files.createDirectories(it) } }

        override val targets: TargetStore = this@ExtensionLoader.targets.forExtension(loaded.info.id)

        override fun addCodeAction(
            name: String,
            enabled: ((EmuTarget) -> Boolean)?,
            keyBinding: String?,
            action: (EmuTarget) -> Unit,
        ) {
            val g = host.guiContext ?: return
            popupSubmenu.register(name)
            g.addPopupMenuAction(
                name,
                { ref -> EmuTarget.of(ref)?.let { t -> enabled?.invoke(t) ?: true } ?: false },
                keyBinding,
            ) { ref ->
                val t = EmuTarget.of(ref) ?: return@addPopupMenuAction
                try {
                    action(t)
                } catch (e: Throwable) {
                    log.error("action '{}' failed on {}", name, t, e)
                }
            }
        }

        override fun addPass(pass: JadxPass) {
            loaded.passes += pass
            host.addPass(pass)
        }

        override fun registerOptions(options: JadxPluginOptions, perProject: Boolean) {
            this@ExtensionLoader.options.add(loaded.info.id, options, perProject)
        }
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(ExtensionLoader::class.java)
    }
}
