package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.policy.SignatureSet
import java.lang.reflect.Executable
import java.lang.reflect.Field

/**
 * Policy controlling which JVM members emulated code may execute directly on the host.
 *
 * Only classes under `java.` and `javax.crypto.` are reachable at all. Within them every member runs
 * unless denied: the bundled `emu-unsafe` set (file system, network, processes, threads, class loading, JVM
 * state) always applies; the `emu-random`, `emu-time` and `emu-env` sets apply while the matching
 * `restrict*` flag is set; [deny] adds user entries. [allow] re-enables members, including bundled ones,
 * and is applied last. Denied calls evaluate to unknown values.
 *
 * Framework stubs and hooks run before this policy, so an extension can supply a safe reimplementation of
 * any denied member with [AndroidStubs.registerMethod] or a [HookRegistry] interceptor.
 *
 * Signatures use the forbidden-apis syntax; see [SignatureSet].
 */
class HostBoundary(
    private val deny: SignatureSet = SignatureSet.EMPTY,
    private val allow: SignatureSet = SignatureSet.EMPTY,
    private val restrictRandom: Boolean = true,
    private val restrictTime: Boolean = true,
    private val restrictEnv: Boolean = true,
    private val enabled: Boolean = true,
) {
    private val denied: SignatureSet = SignatureSet.union(
        listOfNotNull(
            Bundled.unsafe,
            Bundled.random.takeIf { restrictRandom },
            Bundled.time.takeIf { restrictTime },
            Bundled.env.takeIf { restrictEnv },
            deny,
        ),
    )

    /**
     * Whether any member of [cls] may run.
     */
    fun canHandle(cls: Class<*>): Boolean = enabled && inNamespace(cls) && (!denied.matchesClass(cls) || allow.matchesClass(cls))

    /**
     * Whether [m] may run, optionally dispatched on an instance of [receiverClass].
     */
    fun canExecute(m: Executable, receiverClass: Class<*>? = null): Boolean = denial(m, receiverClass) == null

    /**
     * Why [m] may not run, or null if it may.
     */
    fun denial(m: Executable, receiverClass: Class<*>? = null): String? = when {
        !enabled -> DISABLED
        !inNamespace(m.declaringClass) -> OUTSIDE_NAMESPACE
        denied.matches(m, receiverClass) && !allow.matches(m, receiverClass) -> DENIED
        else -> null
    }

    /**
     * Whether static field [f] may be read.
     */
    fun canRead(f: Field): Boolean = enabled && inNamespace(f.declaringClass) && (!denied.matches(f) || allow.matches(f))

    /**
     * Entries re-enabled through [allow], for reporting at startup.
     */
    fun reallowed(): List<String> = allow.entries()

    private fun inNamespace(cls: Class<*>): Boolean {
        val n = cls.name
        return n.startsWith("java.") || n.startsWith("javax.crypto.")
    }

    companion object {
        const val DISABLED = "host execution disabled"
        const val OUTSIDE_NAMESPACE = "outside host namespace"
        const val DENIED = "denied by host policy"

        /**
         * Policy that denies all host execution.
         */
        fun disabled(): HostBoundary = HostBoundary(enabled = false)

        /**
         * Text of a bundled signature file (`emu-unsafe`, `emu-random`, `emu-time`, `emu-env`), or null.
         */
        fun bundledText(name: String): String? =
            HostBoundary::class.java.getResourceAsStream("/host/policy/$name.txt")?.use { it.readBytes().decodeToString() }

        /**
         * Parse signatures in the forbidden-apis syntax, resolving `@includeBundled` against the bundled files.
         */
        fun parse(text: String): SignatureSet = SignatureSet.parse(text, ::bundledText)

        /**
         * Instance methods whose result depends only on the receiver, so a call on a known receiver
         * can be folded to a constant.
         */
        val PURE_ACCESSORS = setOf(
            "Ljava/lang/Class;->getName", "Ljava/lang/Class;->getSimpleName",
            "Ljava/lang/Class;->getCanonicalName", "Ljava/lang/Class;->getTypeName",
            "Ljava/lang/String;->hashCode", "Ljava/lang/String;->length", "Ljava/lang/String;->isEmpty",
            "Ljava/lang/Integer;->hashCode", "Ljava/lang/Integer;->intValue",
            "Ljava/lang/Long;->hashCode", "Ljava/lang/Long;->longValue",
            "Ljava/lang/Short;->hashCode", "Ljava/lang/Short;->shortValue",
            "Ljava/lang/Byte;->hashCode", "Ljava/lang/Byte;->byteValue",
            "Ljava/lang/Character;->hashCode", "Ljava/lang/Character;->charValue",
            "Ljava/lang/Boolean;->hashCode", "Ljava/lang/Boolean;->booleanValue",
            "Ljava/lang/Double;->hashCode", "Ljava/lang/Double;->doubleValue",
            "Ljava/lang/Float;->hashCode", "Ljava/lang/Float;->floatValue",
            "Ljava/lang/Enum;->name", "Ljava/lang/Enum;->ordinal", "Ljava/lang/Enum;->hashCode",
        )
    }

    private object Bundled {
        val unsafe = load("emu-unsafe")
        val random = load("emu-random")
        val time = load("emu-time")
        val env = load("emu-env")

        private fun load(name: String): SignatureSet =
            parse(bundledText(name) ?: throw IllegalStateException("missing bundled policy $name"))
    }
}
