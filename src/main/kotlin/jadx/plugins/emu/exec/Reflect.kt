package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.model.FieldRef
import jadx.plugins.emu.exec.model.MethodRef

/**
 * Emulated `java.lang.Class` value for an application class (from `const-class`, `Class.forName`, ...).
 *
 * @property desc descriptor of the class
 */
public data class DvmClass(val desc: String)

/**
 * Emulated `java.lang.reflect.Method` value.
 *
 * Exactly one of [dexMethod] or [hostMethod] is set when the target is resolvable; [symbol] alone means the
 * method was named but not found.
 *
 * @property dexMethod the application method, if it lives in the loaded dex
 * @property hostMethod the JVM method, if the target is a host class
 * @property symbol the reference as named by the reflective lookup
 */
public data class DvmMethodHandle @JvmOverloads constructor(val dexMethod: DexMethod?, val hostMethod: java.lang.reflect.Method?, val symbol: MethodRef? = null)

/**
 * Emulated `java.lang.reflect.Constructor` value.
 *
 * @property owner descriptor of the class being constructed
 * @property params parameter type descriptors as named by the reflective lookup
 */
public data class DvmCtorHandle(val owner: String, val params: List<String>)

/**
 * Emulated `java.lang.reflect.Field` value.
 *
 * @property accessFlags `java.lang.reflect.Modifier` bits, or 0 when unknown
 */
public data class DvmField @JvmOverloads constructor(val ref: FieldRef, val isStatic: Boolean, val accessFlags: Int = 0)
