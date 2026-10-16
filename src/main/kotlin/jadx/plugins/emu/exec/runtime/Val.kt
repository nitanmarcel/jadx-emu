package jadx.plugins.emu.exec.runtime

/**
 * A value the emulator could not determine.
 *
 * Unknown values propagate: any operation on one yields another [UnknownVal]. Compare with `is`, never by identity.
 *
 * @property type descriptor of the value's static type when known, otherwise null
 */
class UnknownVal(val type: String?)

/**
 * Shared typeless [UnknownVal].
 */
val UNKNOWN = UnknownVal(null)

/**
 * Marker stored in the upper register of a wide (`long`/`double`) pair; the value lives in the lower register.
 */
object WideHigh

/**
 * Whether this value is an [UnknownVal].
 */
fun Any?.isUnknown(): Boolean = this is UnknownVal

internal fun defaultValueOf(type: String): Any? = when (type) {
    "I", "B", "S", "C" -> 0; "J" -> 0L; "F" -> 0f; "D" -> 0.0; "Z" -> false
    else -> null
}
