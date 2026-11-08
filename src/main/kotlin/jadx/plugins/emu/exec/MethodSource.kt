package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.model.DexMethod

/**
 * Declared field of a class.
 *
 * @property accessFlags `java.lang.reflect.Modifier` bits of the field
 */
public data class FieldMeta @JvmOverloads constructor(val ref: jadx.plugins.emu.exec.model.FieldRef, val isStatic: Boolean, val accessFlags: Int = 0)

/**
 * Class-level metadata needed by the emulator.
 *
 * @property type descriptor of the class
 * @property superType descriptor of the superclass, or null for `java.lang.Object`
 * @property interfaces descriptors of directly implemented interfaces
 * @property isInterface whether the class is an interface
 * @property staticInits constant initial values of static fields keyed by [jadx.plugins.emu.exec.model.FieldRef.key]
 * @property fields declared fields
 * @property accessFlags `java.lang.reflect.Modifier` bits of the class
 */
public data class ClassInfo @JvmOverloads constructor(
    val type: String,
    val superType: String?,
    val interfaces: List<String>,
    val isInterface: Boolean,
    val staticInits: Map<String, Any?> = emptyMap(),
    val fields: List<FieldMeta> = emptyList(),
    val accessFlags: Int = 0,
)

/**
 * Supplies classes and method bodies to the emulator.
 *
 * Class descriptors use dex form (`Lcom/foo/Bar;`); methods are identified by
 * [jadx.plugins.emu.exec.model.MethodRef.shortId].
 */
public interface MethodSource {
    /**
     * Look up a method by declaring class and short id, or null if not present.
     */
    public fun method(classDesc: String, shortId: String): DexMethod?

    /**
     * Look up class metadata, or null if the class is not part of this source.
     */
    public fun classInfo(classDesc: String): ClassInfo?

    /**
     * All overloads named [name] declared directly on [classDesc].
     */
    public fun methodsByName(classDesc: String, name: String): List<DexMethod>

    /**
     * All methods declared directly on [classDesc].
     */
    public fun methodsOf(classDesc: String): List<DexMethod>

    /**
     * Every method in the source.
     */
    public fun allMethods(): List<DexMethod>

    /**
     * Whether the method is declared `native` (and therefore has no body here).
     */
    public fun isNative(classDesc: String, shortId: String): Boolean = false
}
