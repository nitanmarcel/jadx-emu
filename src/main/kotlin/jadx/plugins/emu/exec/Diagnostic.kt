package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.model.MethodRef

/**
 * A call the emulator could not execute.
 */
public class Diagnostic(
    public val kind: Kind,
    public val ref: MethodRef,
    public val caller: DexMethod?,
    public val offset: Int,
    public val detail: String,
) {
    /**
     * What prevented the call.
     */
    public enum class Kind {
        HOST_DENIED,
        HOST_OUTSIDE_NAMESPACE,
        HOST_DISABLED,
        HOST_NOT_FOUND,
        HOST_UNKNOWN_INPUT,
        HOST_THREW,
        MISSING_STUB,
        ABORTED,
        UNCAUGHT,
        HANDLER_FAILED,
    }

    /**
     * Call site as `Lcaller;->m(args)ret@offset`, or null.
     */
    public val site: String? = caller?.let { "${it.declClass}->${it.ref.shortId}@${Integer.toHexString(offset)}" }

    /**
     * One-line description.
     */
    public val message: String
        get() = "${ref.declClass}->${ref.shortId}: $detail" + (site?.let { " (from $it)" } ?: "")

    override fun toString(): String = message
}

/**
 * Receiver of [Diagnostic]s.
 */
public fun interface DiagnosticSink {
    public fun report(d: Diagnostic)
}
