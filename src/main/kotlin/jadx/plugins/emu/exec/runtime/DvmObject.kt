package jadx.plugins.emu.exec.runtime

/**
 * An instance of an application (dex) class created during emulation.
 *
 * Host (JVM) objects are represented directly by their Java value instead.
 *
 * @property type descriptor of the runtime class
 * @property fields instance field values keyed by [jadx.plugins.emu.exec.model.FieldRef.key]
 */
public class DvmObject(public val type: String) {
    public val fields: MutableMap<String, Any?> = HashMap()
}

/**
 * A host-class object allocated by `new-instance` whose constructor has not run yet.
 * It is replaced by the real Java object once `<init>` is invoked.
 *
 * @property type descriptor of the class
 */
public class UninitHost(public val type: String)

/**
 * An exception raised inside emulated code and not caught by it.
 *
 * @property type descriptor of the thrown exception class
 * @property obj the emulated exception object (a [DvmObject] or host `Throwable`), if available
 */
public class DvmThrowable @JvmOverloads constructor(public val type: String, message: String?, public val obj: Any? = null) : RuntimeException(message)

/**
 * Static-initializer state of a class inside a [jadx.plugins.emu.exec.Vm].
 */
public enum class ClinitState { RUNNING, DONE }
