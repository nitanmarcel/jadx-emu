@file:JvmName("MethodSources")

package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.model.DexMethod

/**
 * A [MethodSource] that looks classes up in [layers] in order, first hit wins.
 */
public class LayeredSource(public val layers: List<MethodSource>) : MethodSource {
    override fun method(classDesc: String, shortId: String): DexMethod? =
        layers.firstNotNullOfOrNull { it.method(classDesc, shortId) }

    override fun classInfo(classDesc: String): ClassInfo? =
        layers.firstNotNullOfOrNull { it.classInfo(classDesc) }

    override fun methodsByName(classDesc: String, name: String): List<DexMethod> =
        owner(classDesc)?.methodsByName(classDesc, name) ?: emptyList()

    override fun methodsOf(classDesc: String): List<DexMethod> =
        owner(classDesc)?.methodsOf(classDesc) ?: emptyList()

    override fun allMethods(): List<DexMethod> {
        val seen = HashSet<String>()
        val out = ArrayList<DexMethod>()
        for (layer in layers) for (m in layer.allMethods()) if (seen.add("${m.declClass}->${m.ref.shortId}")) out.add(m)
        return out
    }

    override fun isNative(classDesc: String, shortId: String): Boolean =
        owner(classDesc)?.isNative(classDesc, shortId) ?: false

    private fun owner(classDesc: String): MethodSource? = layers.firstOrNull { it.classInfo(classDesc) != null }
}

/**
 * This source with [other] behind it: classes present in both come from this one.
 */
public operator fun MethodSource.plus(other: MethodSource): MethodSource {
    val a = if (this is LayeredSource) layers else listOf(this)
    val b = if (other is LayeredSource) other.layers else listOf(other)
    return LayeredSource(a + b)
}
