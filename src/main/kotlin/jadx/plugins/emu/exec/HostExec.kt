package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.Diagnostic.Kind
import jadx.plugins.emu.exec.model.MethodRef
import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.UninitHost
import jadx.plugins.emu.exec.runtime.UnknownVal
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Runs host (JVM) methods for the emulator via reflection.
 *
 * Every call first consults [stubs]; otherwise the target member is resolved on the host and it proceeds
 * only if [policy] allows that member and all arguments are concrete host values. Anything else yields an
 * [UnknownVal].
 */
class HostExec(
    private val policy: HostBoundary,
    private val stubs: AndroidStubs = AndroidStubs(),
    private val report: (Kind, MethodRef, String) -> Unit = { _, _, _ -> },
) {

    /**
     * Invoke a static method.
     */
    fun invokeStatic(ref: MethodRef, args: List<Any?>): Any? {
        val sv = stubs.callStatic(ref, args)
        if (sv !== NotHandled) return sv
        if (!argsUsable(args)) return unknown(Kind.HOST_UNKNOWN_INPUT, ref, ref.returnType, UNKNOWN_ARGS)
        val m = resolve(ref) ?: return notFound(ref, ref.returnType)
        if (!Modifier.isStatic(m.modifiers)) return unknown(Kind.HOST_NOT_FOUND, ref, ref.returnType, "not static on host")
        policy.denial(m)?.let { return denied(ref, ref.returnType, it) }
        return runCatching { m.invoke(null, *marshalArgs(ref.argTypes, args)) }.getOrElse { threw(ref, ref.returnType, it) }
    }

    /**
     * Invoke an instance method on a host object.
     */
    fun invokeInstance(ref: MethodRef, receiver: Any?, args: List<Any?>): Any? {
        val sv = stubs.callInstance(ref, receiver, args)
        if (sv !== NotHandled) return sv
        if (!usable(receiver)) return unknown(Kind.HOST_UNKNOWN_INPUT, ref, ref.returnType, UNKNOWN_RECEIVER)
        if (!argsUsable(args)) return unknown(Kind.HOST_UNKNOWN_INPUT, ref, ref.returnType, UNKNOWN_ARGS)
        val eff = redispatch(ref, receiver)
        val m = resolve(eff) ?: return notFound(eff, ref.returnType)
        policy.denial(m, receiver!!.javaClass)?.let { return denied(ref, ref.returnType, it) }
        return runCatching { m.invoke(receiver, *marshalArgs(eff.argTypes, args)) }.getOrElse { threw(ref, ref.returnType, it) }
    }

    private fun redispatch(ref: MethodRef, receiver: Any?): MethodRef =
        if (ref.declClass == "Ljava/lang/Object;" && receiver is String) ref.copy(declClass = "Ljava/lang/String;") else ref

    /**
     * Instantiate host class [type] using the constructor described by [ref].
     */
    fun construct(type: String, ref: MethodRef, args: List<Any?>): Any? {
        if (!argsUsable(args)) return unknown(Kind.HOST_UNKNOWN_INPUT, ref, type, UNKNOWN_ARGS)
        val ctor = runCatching { hostClass(type).getDeclaredConstructor(*paramClasses(ref.argTypes)) }
            .getOrElse { return notFound(ref, type) }
        policy.denial(ctor)?.let { return denied(ref, type, it) }
        return runCatching {
            ctor.isAccessible = true
            ctor.newInstance(*marshalArgs(ref.argTypes, args))
        }.getOrElse { threw(ref, type, it) }
    }

    /**
     * Run [m], already resolved by the caller, under the policy.
     */
    fun invokeResolved(m: Method, receiver: Any?, args: List<Any?>, returnType: String): Any? {
        val types = m.parameterTypes.map { classDesc(it) }
        val ref = MethodRef(classDesc(m.declaringClass), m.name, types, returnType)
        if (!Modifier.isStatic(m.modifiers) && !usable(receiver)) return unknown(Kind.HOST_UNKNOWN_INPUT, ref, returnType, UNKNOWN_RECEIVER)
        if (!argsUsable(args)) return unknown(Kind.HOST_UNKNOWN_INPUT, ref, returnType, UNKNOWN_ARGS)
        policy.denial(m, receiver?.javaClass)?.let { return denied(ref, returnType, it) }
        return runCatching { m.invoke(receiver, *marshalArgs(types, args)) }.getOrElse { threw(ref, returnType, it) }
    }

    private fun resolve(ref: MethodRef): Method? = runCatching {
        hostClass(ref.declClass).getMethod(ref.name, *paramClasses(ref.argTypes))
    }.getOrNull()

    private fun unknown(kind: Kind, ref: MethodRef, type: String, reason: String): UnknownVal {
        report(kind, ref, reason)
        return UnknownVal(type)
    }

    private fun denied(ref: MethodRef, type: String, reason: String): UnknownVal {
        val kind = when (reason) {
            HostBoundary.DENIED -> Kind.HOST_DENIED
            HostBoundary.OUTSIDE_NAMESPACE -> Kind.HOST_OUTSIDE_NAMESPACE
            else -> Kind.HOST_DISABLED
        }
        return unknown(kind, ref, type, reason)
    }

    private fun notFound(ref: MethodRef, type: String): UnknownVal {
        val reason = if (runCatching { hostClass(ref.declClass) }.isFailure) "class not found" else "method not found on host"
        return unknown(Kind.HOST_NOT_FOUND, ref, type, reason)
    }

    private fun threw(ref: MethodRef, type: String, t: Throwable): UnknownVal {
        val cause = if (t is InvocationTargetException) t.targetException ?: t else t
        return unknown(Kind.HOST_THREW, ref, type, "host threw ${cause.javaClass.name}")
    }

    private fun usable(v: Any?) = v != null && v !is UnknownVal && v !is DvmObject && v !is UninitHost
    private fun argsUsable(args: List<Any?>) = args.all { it !is UnknownVal && it !is DvmObject && it !is UninitHost }

    private fun paramClasses(types: List<String>): Array<Class<*>> = Array(types.size) { hostClass(types[it]) }
    private fun marshalArgs(types: List<String>, args: List<Any?>): Array<Any?> = Array(types.size) { marshal(types[it], args[it]) }

    private fun marshal(desc: String, v: Any?): Any? = when (desc) {
        "I" -> (v as Number).toInt()
        "J" -> (v as Number).toLong()
        "S" -> (v as Number).toShort()
        "B" -> (v as Number).toByte()
        "F" -> when (v) { is Int -> Float.fromBits(v); is Float -> v; else -> (v as Number).toFloat() }
        "D" -> when (v) { is Long -> Double.fromBits(v); is Double -> v; else -> (v as Number).toDouble() }
        "Z" -> when (v) { is Boolean -> v; is Number -> v.toInt() != 0; else -> false }
        "C" -> when (v) { is Char -> v; is Number -> v.toInt().toChar(); else -> '\u0000' }
        else -> v
    }

    private companion object {
        const val UNKNOWN_ARGS = "unknown arguments"
        const val UNKNOWN_RECEIVER = "unknown receiver"
    }

    private fun classDesc(c: Class<*>): String = when {
        c == Integer.TYPE -> "I"; c == java.lang.Long.TYPE -> "J"; c == java.lang.Boolean.TYPE -> "Z"
        c == java.lang.Byte.TYPE -> "B"; c == Character.TYPE -> "C"; c == java.lang.Short.TYPE -> "S"
        c == java.lang.Float.TYPE -> "F"; c == java.lang.Double.TYPE -> "D"; c == Void.TYPE -> "V"
        c.isArray -> c.name.replace('.', '/')
        else -> "L" + c.name.replace('.', '/') + ";"
    }
}

/**
 * Load the host class for a type descriptor (`Ljava/lang/String;`, `[I`, `I`) without initializing it.
 *
 * @throws ClassNotFoundException if the class is not on the host classpath
 */
fun hostClass(desc: String): Class<*> = when (desc) {
    "I" -> Integer.TYPE; "J" -> java.lang.Long.TYPE; "Z" -> java.lang.Boolean.TYPE
    "B" -> java.lang.Byte.TYPE; "C" -> Character.TYPE; "S" -> java.lang.Short.TYPE
    "F" -> java.lang.Float.TYPE; "D" -> java.lang.Double.TYPE; "V" -> Void.TYPE
    else -> if (desc.startsWith("[")) Class.forName(desc.replace('/', '.'), false, HostExec::class.java.classLoader)
    else Class.forName(desc.removePrefix("L").removeSuffix(";").replace('/', '.'), false, HostExec::class.java.classLoader)
}
