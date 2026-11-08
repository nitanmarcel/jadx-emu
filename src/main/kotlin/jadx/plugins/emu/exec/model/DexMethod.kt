package jadx.plugins.emu.exec.model

/**
 * One catch handler of a [TryBlock].
 *
 * @property type descriptor of the caught exception type, or null for a catch-all handler
 * @property offset instruction offset where the handler starts
 */
public class Handler(public val type: String?, public val offset: Int)

/**
 * A protected code range and its handlers.
 *
 * @property start first covered instruction offset (inclusive)
 * @property end last covered instruction offset (inclusive)
 * @property handlers handlers in lookup order; a catch-all, if present, is last
 */
public class TryBlock(public val start: Int, public val end: Int, public val handlers: List<Handler>)

/**
 * A method with its decoded instruction stream, as consumed by the emulator.
 *
 * Methods loaded in signature-only mode (see `DexInputSource.loadFramework`) have an empty [insns] list.
 *
 * @property declClass descriptor of the declaring class (`Lcom/foo/Bar;`)
 * @property ref method reference (name, argument and return types)
 * @property isStatic whether the method is static (no `this` register)
 * @property registersCount total number of registers in the frame
 * @property paramWords number of register words occupied by parameters, including `this`
 * @property argsStartReg first parameter register (`this` for instance methods); dex places parameters in
 *   the last [paramWords] registers, other inputs may not
 * @property insns instructions in code order
 * @property offsetToIndex map from instruction offset to its index in [insns]
 * @property tries try/catch ranges
 * @property codeOffset offset of the code item in the dex file
 * @property accessFlags `java.lang.reflect.Modifier` bits of the method
 */
public class DexMethod @JvmOverloads constructor(
    public val declClass: String,
    public val ref: MethodRef,
    public val isStatic: Boolean,
    public val registersCount: Int,
    public val paramWords: Int,
    public val insns: List<DalvikInsn>,
    public val offsetToIndex: Map<Int, Int>,
    public val tries: List<TryBlock>,
    public val codeOffset: Int,
    public val argsStartReg: Int = registersCount - paramWords,
    public val accessFlags: Int = 0,
)
