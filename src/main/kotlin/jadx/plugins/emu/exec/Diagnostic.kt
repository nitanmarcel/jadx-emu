package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.model.MethodRef

/**
 * A call the emulator could not execute.
 */
class Diagnostic(
    val kind: Kind,
    val ref: MethodRef,
    val caller: DexMethod?,
    val offset: Int,
    val detail: String,
) {
    /**
     * What prevented the call.
     */
    enum class Kind {
        HOST_DENIED,
        HOST_OUTSIDE_NAMESPACE,
        HOST_DISABLED,
        HOST_NOT_FOUND,
        HOST_UNKNOWN_INPUT,
        HOST_THREW,
        MISSING_STUB,
        ABORTED,
        UNCAUGHT,
    }

    /**
     * Call site as `Lcaller;->m(args)ret@offset`, or null.
     */
    val site: String? = caller?.let { "${it.declClass}->${it.ref.shortId}@${Integer.toHexString(offset)}" }

    /**
     * One-line description.
     */
    val message: String
        get() = "${ref.declClass}->${ref.shortId}: $detail" + (site?.let { " (from $it)" } ?: "")

    override fun toString(): String = message
}

/**
 * Receiver of [Diagnostic]s.
 */
fun interface DiagnosticSink {
    fun report(d: Diagnostic)
}
