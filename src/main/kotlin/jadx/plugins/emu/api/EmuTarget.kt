package jadx.plugins.emu.api

import jadx.api.metadata.ICodeNodeRef
import jadx.api.metadata.annotations.VarNode
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.FieldNode
import jadx.core.dex.nodes.MethodNode

/**
 * Identifies a class or a method of the loaded application by its jadx name.
 *
 * Two targets are equal when their [rawClassName] and [shortId] are equal.
 *
 * @property rawClassName Class name as returned by `ClassInfo.getRawName()`, with `$` for inner classes,
 *   for example `com.foo.Bar$Inner`.
 * @property shortId Method short id as returned by `MethodInfo.getShortId()`, for example `run(I)V`,
 *   or `null` when the target is a class.
 * @see TargetStore
 */
public data class EmuTarget @JvmOverloads constructor(val rawClassName: String, val shortId: String? = null) {

    /**
     * `true` when this target is a method.
     */
    val isMethod: Boolean get() = shortId != null

    /**
     * Textual form of this target: `com.foo.Bar` for a class, `com.foo.Bar->run(I)V` for a method.
     *
     * @see parse
     */
    val key: String get() = if (shortId == null) rawClassName else "$rawClassName->$shortId"

    /**
     * The class of this target. Returns this instance for a class target.
     */
    val classTarget: EmuTarget get() = if (shortId == null) this else EmuTarget(rawClassName)

    override fun toString(): String = key

    public companion object {
        /**
         * Parses the textual form produced by [key].
         *
         * Text without `->` is parsed as a class target. Leading and trailing whitespace is removed.
         * The parts are not validated.
         */
        @JvmStatic
        public fun parse(key: String): EmuTarget {
            val i = key.indexOf("->")
            return if (i < 0) EmuTarget(key.trim()) else EmuTarget(key.substring(0, i).trim(), key.substring(i + 2).trim())
        }

        /**
         * Returns the target for a jadx method node.
         */
        @JvmStatic
        public fun of(mth: MethodNode): EmuTarget = EmuTarget(mth.parentClass.classInfo.rawName, mth.methodInfo.shortId)

        /**
         * Returns the target for a jadx class node.
         */
        @JvmStatic
        public fun of(cls: ClassNode): EmuTarget = EmuTarget(cls.classInfo.rawName)

        /**
         * Returns the target for a code node reference, or `null` if the node has no target.
         *
         * A method or a variable maps to the method; a class or a field maps to the class.
         * Other nodes, including `null`, map to `null`.
         */
        @JvmStatic
        public fun of(ref: ICodeNodeRef?): EmuTarget? = when (ref) {
            is MethodNode -> of(ref)
            is ClassNode -> of(ref)
            is FieldNode -> of(ref.parentClass)
            is VarNode -> of(ref.mth)
            else -> null
        }
    }
}

/**
 * The set of [EmuTarget]s selected for one extension.
 *
 * Changes are stored in the jadx project when one is open. Implementations are thread-safe.
 */
public interface TargetStore {

    /**
     * Returns all stored targets.
     */
    public fun all(): Set<EmuTarget>

    /**
     * Returns `true` if [target] itself is stored.
     */
    public fun contains(target: EmuTarget): Boolean

    /**
     * Returns `true` if [target] or the class it belongs to is stored.
     */
    public fun appliesTo(target: EmuTarget): Boolean = contains(target) || contains(target.classTarget)

    /**
     * Returns `true` if [mth] or its class is stored.
     */
    public fun appliesTo(mth: MethodNode): Boolean = appliesTo(EmuTarget.of(mth))

    /**
     * Stores [target]. Does nothing if it is already stored.
     */
    public fun add(target: EmuTarget)

    /**
     * Removes [target]. Does nothing if it is not stored.
     */
    public fun remove(target: EmuTarget)

    /**
     * Removes [target] if it is stored, otherwise stores it.
     *
     * @return `true` if [target] is stored after the call.
     */
    public fun toggle(target: EmuTarget): Boolean

    /**
     * Removes all targets.
     */
    public fun clear()
}
