package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.input.DexInputSource
import jadx.plugins.emu.exec.model.DexMethod
import java.io.File

/**
 * Signatures of the Android framework (API 33), loaded from the bundled stub jar.
 *
 * Provides class hierarchy and method signatures only; behaviour comes from [Hooks] stubs.
 */
internal object FrameworkStubs {

    /**
     * Signature-only source for the framework, or null if the stub jar could not be loaded.
     */
    internal val source: MethodSource? by lazy { loadFromResource() }

    /**
     * Framework method signature, or null if unknown.
     */
    internal fun method(classDesc: String, shortId: String): DexMethod? = source?.method(classDesc, shortId)

    /**
     * Whether [classDesc] is a framework class.
     */
    internal fun hasClass(classDesc: String): Boolean = source?.classInfo(classDesc) != null

    private fun loadFromResource(): MethodSource? = runCatching {
        val stream = FrameworkStubs::class.java.getResourceAsStream("/framework/android-13-stub.jar") ?: return null
        val tmp = File.createTempFile("jdex-android-stub", ".jar").apply { deleteOnExit() }
        stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        try {
            DexInputSource.loadFramework(tmp)
        } finally {
            tmp.delete()
        }
    }.getOrNull()
}
