package jadx.plugins.emu.exec.model

/**
 * A constant-pool item referenced by an instruction.
 */
public sealed interface InsnRef

/**
 * Reference to a method.
 *
 * @property declClass descriptor of the class the reference names (not necessarily where the method is defined)
 * @property name method name
 * @property argTypes parameter type descriptors
 * @property returnType return type descriptor
 * @property shortId `name(argTypes)returnType`, the key used by [jadx.plugins.emu.exec.MethodSource]
 */
public data class MethodRef(
    val declClass: String,
    val name: String,
    val argTypes: List<String>,
    val returnType: String,
) : InsnRef {
    val shortId: String = buildString {
        append(name); append('(')
        argTypes.forEach { append(it) }
        append(')'); append(returnType)
    }

    public companion object {
        /**
         * Parse `shortId` (`name(args)ret`) into a reference on [declClass].
         */
        @JvmStatic
        public fun of(declClass: String, shortId: String): MethodRef {
            val open = shortId.indexOf('(')
            val close = shortId.indexOf(')', open + 1)
            require(open > 0 && close > open) { "malformed method id: $shortId" }
            return MethodRef(declClass, shortId.substring(0, open), splitDescriptors(shortId.substring(open + 1, close)), shortId.substring(close + 1))
        }

        private fun splitDescriptors(s: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < s.length) {
                val start = i
                while (s[i] == '[') i++
                i = if (s[i] == 'L') s.indexOf(';', i) + 1 else i + 1
                require(i > 0) { "malformed descriptor list: $s" }
                out.add(s.substring(start, i))
            }
            return out
        }
    }
}

/**
 * Reference to a field.
 *
 * @property declClass descriptor of the declaring class
 * @property name field name
 * @property type field type descriptor
 * @property key `declClass.name`, the key used for static and instance field storage
 */
public data class FieldRef(val declClass: String, val name: String, val type: String) : InsnRef {
    val key: String = "$declClass.$name"
}

/**
 * Reference to a type (`const-class`, `new-instance`, `check-cast`, ...).
 *
 * @property desc type descriptor
 */
public data class TypeRef(val desc: String) : InsnRef

/**
 * A string constant (`const-string`).
 */
public data class StringRef(val value: String) : InsnRef

/**
 * An `invoke-custom` call site.
 *
 * @property name the name passed to the bootstrap method
 * @property recipe the `StringConcatFactory.makeConcatWithConstants` recipe, if this site is a string concatenation
 * @property constants extra bootstrap constants following the recipe
 * @property argTypes dynamic argument type descriptors
 * @property returnType return type descriptor
 */
public data class CallSiteRef(
    val name: String,
    val recipe: String?,
    val constants: List<Any?>,
    val argTypes: List<String>,
    val returnType: String,
) : InsnRef
