package jadx.plugins.emu.api

import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.gui.JadxGuiContext
import jadx.api.plugins.options.JadxPluginOptions
import jadx.api.plugins.pass.JadxPass
import org.slf4j.Logger
import java.nio.file.Path
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * Services available to an [EmuExtension] during and after [EmuExtension.init].
 *
 * A context is created for each extension on each project load and is valid until [EmuExtension.unload].
 */
public interface EmuExtensionContext {

    /**
     * The jadx plugin context of jadx-emu: decompiler, arguments, events and resource loader.
     */
    public val jadx: JadxPluginContext

    /**
     * The jadx GUI context, or `null` when jadx runs without a user interface.
     *
     * This is `null` in jadx-cli and while jadx-gui collects plugin options.
     */
    public val gui: JadxGuiContext?
        get() = jadx.guiContext

    /**
     * The emulator for the loaded application.
     */
    public val emu: EmuContext

    /**
     * Hooks and stubs registered by this extension.
     */
    public val hooks: Hooks

    /**
     * Logger named `jadx-emu.<extension id>`.
     */
    public val log: Logger

    /**
     * Directory for files owned by this extension. Created on first access.
     *
     * The directory is shared by all projects; use [targets] for per-project state.
     */
    public val dataDir: Path

    /**
     * Targets selected for this extension.
     *
     * In jadx-gui the selection is stored in the jadx project as the `jadx-emu.targets` option and follows
     * the project's save state. In jadx-cli it is read from that option and changes are not persisted.
     *
     * @see ExtensionMode.ON_DEMAND
     */
    public val targets: TargetStore

    /**
     * Registers a jadx pass for the current project load.
     *
     * Accepts prepare, decompile and after-load passes. Passes registered after [EmuExtension.init] returns
     * are ignored by jadx.
     */
    public fun addPass(pass: JadxPass)

    /**
     * Adds an entry to the context menu of the code viewer.
     *
     * The entry is shown when the node under the mouse maps to an [EmuTarget]; see [EmuTarget.of].
     * [enabled] decides whether the entry is enabled for that target; `null` means always enabled.
     * [action] runs on the GUI thread; an exception thrown by it is logged and does not propagate.
     * Does nothing when [gui] is `null`.
     *
     * @param name Text of the menu entry.
     * @param keyBinding Shortcut in the format accepted by [javax.swing.KeyStroke.getKeyStroke], or `null`.
     */
    public fun addCodeAction(name: String, enabled: Predicate<EmuTarget>?, keyBinding: String?, action: Consumer<EmuTarget>)

    public fun addCodeAction(name: String, action: Consumer<EmuTarget>): Unit = addCodeAction(name, null, null, action)

    public fun addCodeAction(name: String, keyBinding: String?, action: Consumer<EmuTarget>): Unit = addCodeAction(name, null, keyBinding, action)

    /**
     * Adds a context menu entry that adds or removes the target under the mouse in [targets] and then
     * calls [reloadActiveTab].
     *
     * Equivalent to:
     * ```kotlin
     * addCodeAction(name, keyBinding = keyBinding) { target ->
     *     targets.toggle(target)
     *     reloadActiveTab()
     * }
     * ```
     * The change is logged at INFO level.
     */
    public fun addTargetToggleAction(name: String, keyBinding: String?) {
        addCodeAction(name, keyBinding) { t ->
            val selected = targets.toggle(t)
            log.info("{} {}", if (selected) "selected" else "deselected", t)
            reloadActiveTab()
        }
    }

    /**
     * Decompiles the class shown in the active tab again, running all passes on it.
     * Does nothing when [gui] is `null`.
     */
    public fun addTargetToggleAction(name: String): Unit = addTargetToggleAction(name, null)

    public fun reloadActiveTab() {
        gui?.reloadActiveTab()
    }

    /**
     * Publishes [options] under the prefix `jadx-emu.<extension id>.`.
     *
     * Option names in [options] must not include the prefix. The published options can be set with
     * `-P` in jadx-cli and appear in the jadx-gui preferences. The current values are applied to [options]
     * before this method returns.
     *
     * ```kotlin
     * class HelloOptions : BasePluginOptionsBuilder() {
     *     var minLength = 1
     *
     *     override fun registerOptions() {
     *         intOption("min-length").description("Minimum string length").defaultValue(1).setter { minLength = it }
     *     }
     * }
     * ```
     * The option above is published as `jadx-emu.hello.min-length`.
     *
     * @param perProject When `true`, jadx-gui stores the values in the project file instead of the global settings.
     */
    public fun registerOptions(options: JadxPluginOptions, perProject: Boolean)

    public fun registerOptions(options: JadxPluginOptions): Unit = registerOptions(options, true)
}
