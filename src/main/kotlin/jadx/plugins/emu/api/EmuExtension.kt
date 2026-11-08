package jadx.plugins.emu.api

/**
 * A unit of functionality loaded by jadx-emu from a jar in its extensions directory.
 *
 * Implementations are discovered with [java.util.ServiceLoader] and must be listed in
 * `META-INF/services/jadx.plugins.emu.api.EmuExtension`. Each implementation needs a public no-argument
 * constructor. An extension is instantiated once per project load and receives an [EmuExtensionContext] in [init].
 *
 * ```kotlin
 * class Hello : EmuExtension {
 *     override fun info() = EmuExtensionInfo("hello", "Hello", "Comments recovered strings")
 *
 *     override fun init(ctx: EmuExtensionContext) {
 *         ctx.addPass(HelloPass(ctx))
 *     }
 * }
 * ```
 *
 * @see EmuExtensionContext
 * @see ExtensionMode
 */
public interface EmuExtension {

    /**
     * Returns the identity and requirements of this extension.
     *
     * The result is read before [init] and each time the extension is listed, so it must not depend on
     * state created in [init].
     */
    public fun info(): EmuExtensionInfo

    /**
     * Returns whether this extension should be initialized for the loaded application.
     *
     * Called only for [ExtensionMode.AUTO] extensions, before [init]. When it returns `false`, [init] is not
     * called and the extension is reported as not applicable. The default implementation returns `true`.
     *
     * Reading [EmuContext.source] here parses the input files, because jadx has not loaded them yet at this point.
     */
    public fun applies(emu: EmuContext): Boolean = true

    /**
     * Initializes the extension.
     *
     * Called once per project load, while jadx is loading plugins. Passes, options and actions registered
     * through [ctx] during this call take part in the current project. An exception thrown here is logged and
     * marks the extension as failed; other extensions are not affected.
     */
    public fun init(ctx: EmuExtensionContext)

    /**
     * Releases resources held by this extension.
     *
     * Called when jadx-emu unloads, before the class loader that loaded the extension is closed.
     * The default implementation does nothing.
     */
    public fun unload() {}
}

/**
 * Specifies when an extension runs.
 */
public enum class ExtensionMode {
    /**
     * The extension is initialized on every project load where it is enabled.
     * Enablement is stored per project.
     */
    AUTO,

    /**
     * The extension is initialized on every project load but acts only on targets selected by the user,
     * through actions it registers with [EmuExtensionContext.addCodeAction] or through the `jadx-emu.targets`
     * option. It cannot be disabled.
     *
     * @see TargetStore
     */
    ON_DEMAND,
}

/**
 * Describes an [EmuExtension].
 *
 * @property id Identifies the extension. Must consist of lowercase letters, digits and dashes, and start with a
 *   letter or digit. Used as the option prefix (`jadx-emu.<id>.`), the data directory name and the logger name.
 * @property name Name shown in the user interface.
 * @property description One sentence shown next to the name.
 * @property mode When the extension runs.
 * @property requiredEmuVersion Lowest jadx-emu version the extension supports, as `major.minor.patch`.
 *   The extension is not initialized on a lower version. `null` disables the check.
 * @throws IllegalArgumentException if [id] does not match `[a-z0-9][a-z0-9-]*`.
 */
public data class EmuExtensionInfo @JvmOverloads constructor(
    val id: String,
    val name: String,
    val description: String,
    val mode: ExtensionMode = ExtensionMode.AUTO,
    val requiredEmuVersion: String? = null,
) {
    init {
        require(ID_PATTERN.matches(id)) { "Extension id must match ${ID_PATTERN.pattern}: '$id'" }
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9][a-z0-9-]*")
    }
}
