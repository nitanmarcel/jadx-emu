package jadx.plugins.emu.exec.policy

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.function.Function

/**
 * A set of host API signatures in the forbidden-apis file syntax.
 *
 * Supported lines: `pkg.Cls` (every member of the class and its subclasses), `pkg.Cls#name(arg.Type,…)`,
 * `pkg.Cls#name(**)` (any overload), `pkg.Cls#field`, `pkg.**` / `pkg.*` (class glob), `@defaultMessage …`,
 * `@includeBundled name`, `#` comments, and a trailing ` @ message`. The legacy `Lpkg/Cls;->name` form of
 * earlier plugin versions is accepted as `pkg.Cls#name(**)`.
 */
public class SignatureSet private constructor(
    private val classes: Set<String>,
    private val classGlobs: List<Regex>,
    private val members: Set<String>,
    private val anyOverload: Set<String>,
) {

    /**
     * Whether [cls] or any of its supertypes is listed as a whole class.
     */
    public fun matchesClass(cls: Class<*>): Boolean = hierarchy(cls).any { matchesClassName(it.name) }

    /**
     * Whether [m] is listed, directly or through a class-level entry on its declaring class or any supertype
     * of [receiverClass].
     */
    @JvmOverloads
    public fun matches(m: Executable, receiverClass: Class<*>? = null): Boolean {
        if (matchesClass(m.declaringClass)) return true
        if (receiverClass != null && matchesClass(receiverClass)) return true
        val name = if (m is Constructor<*>) "<init>" else (m as Method).name
        val params = m.parameterTypes.joinToString(",") { it.typeName }
        val types = if (receiverClass == null) hierarchy(m.declaringClass) else hierarchy(m.declaringClass) + hierarchy(receiverClass)
        for (c in types) {
            if ("${c.name}#$name" in anyOverload) return true
            if ("${c.name}#$name($params)" in members) return true
        }
        return false
    }

    /**
     * Whether field [f] is listed, directly or through a class-level entry.
     */
    public fun matches(f: Field): Boolean =
        matchesClass(f.declaringClass) || hierarchy(f.declaringClass).any { "${it.name}#${f.name}" in members }

    public fun isEmpty(): Boolean = classes.isEmpty() && classGlobs.isEmpty() && members.isEmpty() && anyOverload.isEmpty()

    /**
     * Human-readable listing of every entry, for diagnostics.
     */
    public fun entries(): List<String> = (classes + classGlobs.map { it.pattern } + members + anyOverload.map { "$it(**)" }).sorted()

    private fun matchesClassName(name: String): Boolean = name in classes || classGlobs.any { it.matches(name) }

    private fun hierarchy(cls: Class<*>): Sequence<Class<*>> = sequence {
        val seen = HashSet<Class<*>>()
        val stack = ArrayDeque<Class<*>>()
        stack.addLast(cls)
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (!seen.add(c)) continue
            yield(c)
            c.superclass?.let { stack.addLast(it) }
            c.interfaces.forEach { stack.addLast(it) }
        }
    }

    public companion object {
        public val EMPTY: SignatureSet = SignatureSet(emptySet(), emptyList(), emptySet(), emptySet())

        /**
         * Parse signature text; `@includeBundled` references are resolved through [bundled].
         *
         * @throws IllegalArgumentException on a malformed line
         */
        @JvmStatic
        @JvmOverloads
        public fun parse(text: String, bundled: Function<String, String?> = Function { null }): SignatureSet {
            val b = Builder()
            b.addAll(text, bundled, HashSet())
            return b.build()
        }

        /**
         * Union of [sets].
         */
        @JvmStatic
        public fun union(sets: Iterable<SignatureSet>): SignatureSet {
            val b = Builder()
            for (s in sets) {
                b.classes += s.classes; b.classGlobs += s.classGlobs; b.members += s.members; b.anyOverload += s.anyOverload
            }
            return b.build()
        }

        private class Builder {
            val classes = HashSet<String>()
            val classGlobs = ArrayList<Regex>()
            val members = HashSet<String>()
            val anyOverload = HashSet<String>()

            fun build() = SignatureSet(classes, classGlobs, members, anyOverload)

            fun addAll(text: String, bundled: Function<String, String?>, seen: MutableSet<String>) {
                for (raw in text.lineSequence()) {
                    val line = raw.substringBefore(" @ ").trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    if (line.startsWith("@defaultMessage")) continue
                    if (line.startsWith("@includeBundled")) {
                        val name = line.removePrefix("@includeBundled").trim()
                        if (seen.add(name)) addAll(bundled.apply(name) ?: throw IllegalArgumentException("unknown bundled signatures: $name"), bundled, seen)
                        continue
                    }
                    if (line.startsWith("@")) throw IllegalArgumentException("unknown directive: $line")
                    add(line)
                }
            }

            fun add(sig: String) {
                legacy(sig)?.let { add(it); return }
                val hash = sig.indexOf('#')
                if (hash < 0) {
                    val cls = sig.replace('/', '.')
                    if (cls.contains('*')) classGlobs += globToRegex(cls) else classes += cls
                    return
                }
                val cls = sig.substring(0, hash).replace('/', '.')
                val member = sig.substring(hash + 1)
                if (cls.contains('*')) throw IllegalArgumentException("class glob cannot have members: $sig")
                val paren = member.indexOf('(')
                if (paren < 0) { members += "$cls#$member"; return }
                val name = member.substring(0, paren)
                if (name.isEmpty()) throw IllegalArgumentException("method name missing: $sig")
                val params = member.substring(paren + 1).removeSuffix(")").trim()
                if (params == "**") { anyOverload += "$cls#$name"; return }
                val norm = params.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
                members += "$cls#$name($norm)"
            }

            private fun legacy(sig: String): String? {
                if (!sig.startsWith("L")) return null
                val semi = sig.indexOf(';')
                if (semi < 0) return null
                val cls = sig.substring(1, semi).replace('/', '.')
                val rest = sig.substring(semi + 1)
                return when {
                    rest.isEmpty() -> cls
                    rest.startsWith("->") -> "$cls#${rest.removePrefix("->").substringBefore('(')}(**)"
                    else -> null
                }
            }

            private fun globToRegex(glob: String): Regex {
                val sb = StringBuilder("^")
                var i = 0
                while (i < glob.length) {
                    val c = glob[i]
                    when {
                        c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> { sb.append(".*"); i++ }
                        c == '*' -> sb.append("[^.]*")
                        c == '.' -> sb.append("\\.")
                        c == '$' -> sb.append("\\$")
                        else -> sb.append(c)
                    }
                    i++
                }
                return Regex(sb.append('$').toString())
            }
        }
    }
}
