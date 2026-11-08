package jadx.plugins.emu

import jadx.api.JadxArgs
import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.JadxPluginInfo
import jadx.api.plugins.JadxPluginInfoBuilder
import jadx.api.plugins.gui.JadxGuiContext
import jadx.core.utils.files.FileUtils
import jadx.plugins.emu.host.CompositeOptions
import jadx.plugins.emu.host.DefaultEmuContext
import jadx.plugins.emu.host.EmuOptions
import jadx.plugins.emu.host.EmuVersion
import jadx.plugins.emu.host.ExtensionLoader
import jadx.plugins.emu.host.ExtensionStatus
import jadx.plugins.emu.host.gui.EmuSettingsGroup
import jadx.plugins.emu.host.gui.ProjectOptions
import jadx.plugins.emu.host.TargetRegistry
import jadx.plugins.emu.host.gui.StartupDialog
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JDialog
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/**
 * jadx plugin that provides the Dalvik emulator and loads [jadx.plugins.emu.api.EmuExtension]s.
 *
 * Extensions are read from `extensions/` in the plugin's configuration directory. Options are published
 * under the `jadx-emu.` prefix and can be set with `-P` in jadx-cli or in the jadx-gui preferences.
 */
public class JadxEmuPlugin : JadxPlugin {

    private val options = EmuOptions()
    private val allOptions = CompositeOptions(options)
    private var loader: ExtensionLoader? = null
    private var project: ProjectOptions? = null
    private var session: Pair<JadxPluginContext, TargetRegistry>? = null

    override fun getPluginInfo(): JadxPluginInfo =
        JadxPluginInfoBuilder.pluginId(PLUGIN_ID)
            .name("Jadx Emu")
            .description("Dalvik emulation engine and extension host for jadx")
            .homepage("https://github.com/nitanmarcel/jadx-emu")
            .requiredJadxVersion("1.5.6, r2678")
            .build()

    override fun init(context: JadxPluginContext) {
        context.registerOptions(allOptions)
        val configDir = context.files().pluginConfigDir
        val extensionsDir = configDir.resolve(EXTENSIONS_DIR)
        val gui = context.guiContext
        val project = gui?.let { ProjectOptions(it) }?.takeIf { it.available }
        this.project = project
        context.registerInputsHashSupplier { inputsHash(context.args, extensionsDir) }
        if (!options.enabled) {
            LOG.info("jadx-emu {} disabled by option", EmuVersion.current)
            return
        }
        val targets = TargetRegistry(project, project?.read(EmuOptions.TARGETS_OPT) ?: options.targets)
        val l = ExtensionLoader(context, allOptions, targets, extensionsDir, configDir.resolve(DATA_DIR))
        loader = l
        session = context to targets
        l.discover()

        if (gui != null) {
            val group = EmuSettingsGroup(context, gui, l, allOptions)
            gui.settings().setCustomSettingsGroup(group)
            if (options.startupDialog) showStartupDialog(context, gui, group)
            applyProjectOptions(context, group)
            installMenu(context, gui, l)
        }

        options.hostBoundary().reallowed().takeIf { it.isNotEmpty() }
            ?.let { LOG.warn("jadx-emu: host policy re-allows {}", it.joinToString(", ")) }
        l.initAll(DefaultEmuContext(context, options))
        val active = l.extensions.count { it.status == ExtensionStatus.ACTIVE }
        LOG.info("jadx-emu {} loaded: {} extension(s) found in {}, {} active", EmuVersion.current, l.extensions.size, extensionsDir, active)
    }

    override fun unload() {
        session?.let { (context, targets) -> evictTouchedClasses(context, targets) }
        session = null
        loader?.close()
        loader = null
    }

    private fun evictTouchedClasses(context: JadxPluginContext, targets: TargetRegistry) {
        val touched = targets.touchedClasses()
        if (touched.isEmpty()) return
        val cache = context.args.codeCache
        val root = runCatching { context.decompiler.root }.getOrNull()
        val names = touched.map { raw -> root?.resolveRawClass(raw)?.topParentClass?.rawName ?: raw.substringBefore('$') }.toSet()
        for (name in names) runCatching { cache.remove(name) }.onFailure { LOG.debug("jadx-emu: cache eviction failed for {}", name, it) }
        LOG.debug("jadx-emu: evicted {} class(es) with on-demand results from the code cache", names.size)
    }

    private fun showStartupDialog(context: JadxPluginContext, gui: JadxGuiContext, group: EmuSettingsGroup) {
        val result = runCatching { StartupDialog.show(gui, group) }
            .onFailure { LOG.warn("jadx-emu: startup dialog failed", it) }
            .getOrNull() ?: return
        if (result.dontShowAgain) context.args.pluginOptions[EmuOptions.STARTUP_DIALOG_OPT] = "no"
    }

    private fun applyProjectOptions(context: JadxPluginContext, group: EmuSettingsGroup) {
        val merged = HashMap(context.args.pluginOptions)
        merged[EmuOptions.ENABLED_EXTENSIONS_OPT] = EmuSettingsGroup.enabledValue(group.projectOptions, context.args.pluginOptions)
        allOptions.setOptions(merged)
    }

    private fun installMenu(context: JadxPluginContext, gui: JadxGuiContext, l: ExtensionLoader) {
        gui.addMenuAction("Jadx Emu extensions") {
            val dialog = JDialog(gui.mainFrame, "Jadx Emu extensions", true)
            var reloading = false
            val panel = EmuSettingsGroup(context, gui, l, allOptions, onReload = { reloading = true; dialog.dispose() })
            dialog.contentPane.add(panel.buildComponent())
            dialog.addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosed(e: java.awt.event.WindowEvent) { if (!reloading) panel.close(true) }
            })
            dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            dialog.pack()
            dialog.setLocationRelativeTo(gui.mainFrame)
            dialog.isVisible = true
        }
    }

    private fun inputsHash(args: JadxArgs, extensionsDir: Path): String {
        val jars = if (Files.isDirectory(extensionsDir)) {
            extensionsDir.listDirectoryEntries().sorted().joinToString(";") { p ->
                val stamp = if (p.isRegularFile() && p.extension == "jar") Files.getLastModifiedTime(p).toMillis() else
                    runCatching { p.listDirectoryEntries("*.jar").maxOfOrNull { Files.getLastModifiedTime(it).toMillis() } }.getOrNull() ?: 0L
                "${p.fileName}:$stamp"
            }
        } else ""
        val values = HashMap(args.pluginOptions)
        project?.let { p ->
            for (key in listOf(EmuOptions.ENABLED_EXTENSIONS_OPT, EmuOptions.TARGETS_OPT)) values[key] = p.read(key) ?: ""
        }
        return FileUtils.md5Sum("${EmuVersion.current}|${allOptions.valuesHash(values)}|$jars")
    }

    internal companion object {
        const val PLUGIN_ID = "jadx-emu"
        const val EXTENSIONS_DIR = "extensions"
        const val DATA_DIR = "data"
        private val LOG = LoggerFactory.getLogger(JadxEmuPlugin::class.java)
    }
}
