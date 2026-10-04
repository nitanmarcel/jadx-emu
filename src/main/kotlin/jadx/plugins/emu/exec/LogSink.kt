package jadx.plugins.emu.exec

import org.slf4j.LoggerFactory
import java.util.Collections

/**
 * Logs deny-list hits at WARN and other unexecuted host calls at DEBUG, once per callee.
 */
class LogSink : DiagnosticSink {
    private val reported: MutableSet<String> = Collections.synchronizedSet(HashSet())

    override fun report(d: Diagnostic) {
        when (d.kind) {
            Diagnostic.Kind.HOST_DENIED -> once(d) { LOG.warn("jadx-emu: {} is denied by host policy; use host.allow, a stub or a hook to run it", callee(d)) }
            Diagnostic.Kind.HOST_OUTSIDE_NAMESPACE, Diagnostic.Kind.HOST_NOT_FOUND, Diagnostic.Kind.MISSING_STUB ->
                once(d) { LOG.debug("jadx-emu: {} not executed: {}", callee(d), d.detail) }
            else -> {}
        }
    }

    private fun callee(d: Diagnostic): String = "${d.ref.declClass}->${d.ref.shortId}"

    private inline fun once(d: Diagnostic, log: () -> Unit) {
        if (reported.add(callee(d))) log()
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(LogSink::class.java)
    }
}
