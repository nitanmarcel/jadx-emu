package jadx.plugins.emu.exec.input

import jadx.plugins.emu.exec.ClassInfo
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.model.ArrayPayload
import jadx.plugins.emu.exec.model.DalvikInsn
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.model.FieldRef
import jadx.plugins.emu.exec.model.Handler
import jadx.plugins.emu.exec.model.InsnPayload
import jadx.plugins.emu.exec.model.CallSiteRef
import jadx.plugins.emu.exec.model.InsnRef
import jadx.plugins.emu.exec.model.MethodRef
import jadx.plugins.emu.exec.model.StringRef
import jadx.plugins.emu.exec.model.SwitchPayload
import jadx.plugins.emu.exec.model.TryBlock
import jadx.plugins.emu.exec.model.TypeRef
import jadx.api.plugins.input.data.IClassData
import jadx.api.plugins.input.data.ICodeReader
import jadx.api.plugins.input.data.IFieldData
import jadx.api.plugins.input.data.IMethodData
import jadx.api.plugins.input.data.ISeqConsumer
import jadx.api.plugins.input.data.annotations.EncodedType
import jadx.api.plugins.input.data.annotations.EncodedValue
import jadx.api.plugins.input.insns.InsnData
import jadx.api.plugins.input.insns.InsnIndexType
import jadx.api.plugins.input.insns.Opcode
import jadx.api.plugins.input.insns.custom.IArrayPayload
import jadx.api.plugins.input.insns.custom.ISwitchPayload
import jadx.api.plugins.input.ICodeLoader
import jadx.plugins.input.dex.DexInputPlugin
import jadx.plugins.input.java.JavaInputPlugin
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * [MethodSource] backed by jadx's dex and class-file input plugins.
 *
 * Instances are immutable once built; use [load] for application code and [loadFramework] for
 * signature-only class libraries.
 */
public class DexInputSource private constructor(
    private val byShortId: Map<Pair<String, String>, DexMethod>,
    private val classes: Map<String, ClassInfo>,
    private val byClass: Map<String, List<DexMethod>>,
    private val nativeMethods: Set<Pair<String, String>>,
) : MethodSource {
    override fun method(classDesc: String, shortId: String): DexMethod? = byShortId[classDesc to shortId]
    override fun classInfo(classDesc: String): ClassInfo? = classes[classDesc]
    override fun methodsByName(classDesc: String, name: String): List<DexMethod> =
        byClass[classDesc].orEmpty().filter { it.ref.name == name }
    override fun methodsOf(classDesc: String): List<DexMethod> = byClass[classDesc].orEmpty()
    override fun isNative(classDesc: String, shortId: String): Boolean = (classDesc to shortId) in nativeMethods
    override fun allMethods(): List<DexMethod> = byShortId.values.toList()

    public companion object {
        private val LOG = org.slf4j.LoggerFactory.getLogger(DexInputSource::class.java)
        private const val ACC_STATIC = 0x8
        private const val ACC_INTERFACE = 0x200
        private const val ACC_NATIVE = 0x100
        private val DEX_NAME = Regex("""classes\d*\.dex""")
        private val PAYLOAD_OPS = setOf(Opcode.FILL_ARRAY_DATA, Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH)

        /**
         * Load all code from [input], which may be a `.dex` file or a zip/apk containing `classes*.dex` entries.
         */
        @JvmStatic
        public fun load(input: File): DexInputSource = load(listOf(input))

        /**
         * Load and merge code from several inputs; see [load].
         */
        @JvmStatic
        public fun load(inputs: List<File>): DexInputSource {
            val all = inputs.map { dexPaths(it) }
            try {
                return fromLoader(DexInputPlugin().loadFiles(all.flatMap { it.first }), signaturesOnly = false)
            } finally {
                all.forEach { it.second() }
            }
        }

        /**
         * Load class hierarchy and method signatures (no bodies) from a jar of `.class` files.
         */
        @JvmStatic
        public fun loadFramework(jar: File): DexInputSource =
            fromLoader(JavaInputPlugin.loadClassFiles(listOf(jar.toPath())), signaturesOnly = true)

        /**
         * Build a source from class data already loaded by jadx, e.g. `ClassNode.getClsData()` of every class
         * in a `RootNode`. Avoids parsing the inputs a second time and covers every format jadx can open.
         *
         * @param signaturesOnly skip instruction decoding; methods will have empty bodies
         */
        @JvmStatic
        @JvmOverloads
        public fun fromClasses(classes: Iterable<IClassData>, signaturesOnly: Boolean = false): DexInputSource {
            val byShortId = HashMap<Pair<String, String>, DexMethod>()
            val classInfos = HashMap<String, ClassInfo>()
            val byClass = HashMap<String, List<DexMethod>>()
            val nativeMethods = HashSet<Pair<String, String>>()
            for (cd in classes) readClass(cd, byShortId, classInfos, byClass, nativeMethods, signaturesOnly)
            return DexInputSource(byShortId, classInfos, byClass, nativeMethods)
        }

        private fun fromLoader(loader: ICodeLoader, signaturesOnly: Boolean): DexInputSource {
            val all = ArrayList<IClassData>()
            loader.visitClasses { all += it.copy() }
            try {
                return fromClasses(all, signaturesOnly)
            } finally {
                loader.close()
            }
        }

        private fun readClass(
            cd: IClassData,
            byShortId: MutableMap<Pair<String, String>, DexMethod>,
            classes: MutableMap<String, ClassInfo>,
            byClass: MutableMap<String, List<DexMethod>>,
            nativeMethods: MutableSet<Pair<String, String>>,
            signaturesOnly: Boolean,
        ) {
            val type = cd.type
            val staticInits = HashMap<String, Any?>()
            val fields = ArrayList<jadx.plugins.emu.exec.FieldMeta>()
            val methods = ArrayList<DexMethod>()
            cd.visitFieldsAndMethods(
                seq<IFieldData> { fd ->
                    fieldConst(type, fd)?.let { (k, v) -> staticInits[k] = v }
                    fields += jadx.plugins.emu.exec.FieldMeta(
                        jadx.plugins.emu.exec.model.FieldRef(type, fd.name, fd.type), (fd.accessFlags and ACC_STATIC) != 0, fd.accessFlags)
                },
                seq<IMethodData> { md ->
                    if (signaturesOnly) {
                        val mref = md.methodRef.apply { load() }
                        val ref = MethodRef(type, mref.name, mref.argTypes, mref.returnType)
                        val isStatic = (md.accessFlags and ACC_STATIC) != 0
                        val paramWords = (if (isStatic) 0 else 1) + ref.argTypes.sumOf { if (it == "J" || it == "D") 2 else 1 }
                        val m = DexMethod(type, ref, isStatic, paramWords, paramWords, emptyList(), emptyMap(), emptyList(), 0, accessFlags = md.accessFlags)
                        methods += m
                        byShortId[type to ref.shortId] = m
                    } else {
                        val m = runCatching { decodeMethod(type, md) }
                            .onFailure { LOG.debug("skipping method of {}: {}", type, it.toString()) }
                            .getOrNull()
                        if (m != null) { methods += m; byShortId[type to m.ref.shortId] = m }
                        else if ((md.accessFlags and ACC_NATIVE) != 0) {
                            val mref = md.methodRef.apply { load() }
                            nativeMethods.add(type to "${mref.name}(${mref.argTypes.joinToString("")})${mref.returnType}")
                        }
                    }
                },
            )
            classes[type] = ClassInfo(type, cd.superType, cd.interfacesTypes, (cd.accessFlags and ACC_INTERFACE) != 0, staticInits, fields, cd.accessFlags)
            byClass[type] = methods
        }

        private fun decodeMethod(classType: String, md: IMethodData): DexMethod? {
            val cr = md.codeReader ?: return null
            val mref = md.methodRef.apply { load() }
            val ref = MethodRef(classType, mref.name, mref.argTypes, mref.returnType)
            val insns = ArrayList<DalvikInsn>()
            val payloads = HashMap<Int, InsnPayload>()
            cr.visitInstructions { insn ->
                insn.decode()
                val regsCount = insn.regsCount
                if (regsCount >= 0) {
                    var ref = refOf(insn)
                    var literal = insn.literal
                    var opcode = insn.opcode
                    if (opcode == Opcode.NEW_ARRAY && literal > 0 && ref is TypeRef) {
                        ref = TypeRef("[".repeat(literal.toInt()) + ref.desc)
                        literal = 0
                    }
                    if (opcode == Opcode.INVOKE_SPECIAL && ref is MethodRef) {
                        opcode = if (ref.name == "<init>" || ref.declClass == classType) Opcode.INVOKE_DIRECT else Opcode.INVOKE_SUPER
                    }
                    insns += DalvikInsn(
                        opcode, insn.offset,
                        IntArray(regsCount) { insn.getReg(it) },
                        literal, insn.target, ref, payloadOf(insn),
                        resultReg = insn.resultReg,
                    )
                } else {
                    payloadOf(insn)?.let { payloads[insn.offset] = it }
                }
            }
            if (payloads.isNotEmpty()) {
                for (ins in insns) if (ins.payload == null && ins.opcode in PAYLOAD_OPS) ins.payload = payloads[ins.target]
            }
            val offsetToIndex = HashMap<Int, Int>(insns.size)
            insns.forEachIndexed { i, ins -> offsetToIndex[ins.offset] = i }
            val isStatic = (md.accessFlags and ACC_STATIC) != 0
            val paramWords = (if (isStatic) 0 else 1) + ref.argTypes.sumOf { if (it == "J" || it == "D") 2 else 1 }
            return DexMethod(
                classType, ref, isStatic, cr.registersCount, paramWords,
                insns, offsetToIndex, triesOf(cr), cr.codeOffset,
                argsStartReg = cr.argsStartReg.takeIf { it >= 0 } ?: (cr.registersCount - paramWords),
                accessFlags = md.accessFlags,
            )
        }

        private fun refOf(insn: InsnData): InsnRef? = when (insn.indexType) {
            InsnIndexType.STRING_REF -> StringRef(insn.indexAsString)
            InsnIndexType.TYPE_REF -> TypeRef(insn.indexAsType)
            InsnIndexType.FIELD_REF -> insn.indexAsField.let { FieldRef(it.parentClassType, it.name, it.type) }
            InsnIndexType.METHOD_REF -> insn.indexAsMethod.apply { load() }
                .let { MethodRef(it.parentClassType, it.name, it.argTypes, it.returnType) }
            InsnIndexType.CALL_SITE -> callSiteOf(insn.indexAsCallSite.apply { load() })
            else -> null
        }

        private fun callSiteOf(cs: jadx.api.plugins.input.data.ICallSite): CallSiteRef {
            val values = cs.values
            val name = values.getOrNull(1)?.value as? String ?: ""
            val proto = values.getOrNull(2)?.value as? jadx.api.plugins.input.data.IMethodProto
            val recipe = values.getOrNull(3)?.takeIf { it.type == jadx.api.plugins.input.data.annotations.EncodedType.ENCODED_STRING }?.value as? String
            val constants = if (recipe != null && values.size > 4) values.subList(4, values.size).map { it.value } else emptyList()
            return CallSiteRef(name, recipe, constants, proto?.argTypes ?: emptyList(), proto?.returnType ?: "Ljava/lang/Object;")
        }

        private fun payloadOf(insn: InsnData): InsnPayload? = when (val p = insn.payload) {
            is ISwitchPayload -> SwitchPayload(p.keys, p.targets)
            is IArrayPayload -> ArrayPayload(p.size, p.elementSize, p.data)
            else -> null
        }

        private fun triesOf(cr: ICodeReader): List<TryBlock> = cr.tries.map { t ->
            val c = t.catch
            val handlers = ArrayList<Handler>()
            val types = c.types
            val offs = c.handlers
            for (i in types.indices) handlers += Handler(types[i], offs[i])
            if (c.catchAllHandler >= 0) handlers += Handler(null, c.catchAllHandler)
            TryBlock(t.startOffset, t.endOffset, handlers)
        }

        private fun dexPaths(input: File): Pair<List<Path>, () -> Unit> {
            if (!isZip(input)) return listOf(input.toPath()) to {}
            val temp = ArrayList<File>()
            ZipFile(input).use { zip ->
                zip.entries().asSequence().filter { DEX_NAME.matches(it.name) }.forEach { e ->
                    val f = File.createTempFile("jdex-", ".dex").apply { deleteOnExit() }
                    zip.getInputStream(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    temp += f
                }
            }
            return temp.map { it.toPath() } to { temp.forEach { it.delete() } }
        }

        private fun isZip(f: File): Boolean = runCatching { ZipFile(f).use { } }.isSuccess

        private fun fieldConst(classType: String, fd: IFieldData): Pair<String, Any?>? {
            if ((fd.accessFlags and ACC_STATIC) == 0) return null
            val ev = fd.attributes.filterIsInstance<EncodedValue>().firstOrNull() ?: return null
            val v = when (ev.type) {
                EncodedType.ENCODED_BOOLEAN, EncodedType.ENCODED_BYTE, EncodedType.ENCODED_SHORT,
                EncodedType.ENCODED_CHAR, EncodedType.ENCODED_INT, EncodedType.ENCODED_LONG,
                EncodedType.ENCODED_FLOAT, EncodedType.ENCODED_DOUBLE, EncodedType.ENCODED_STRING -> ev.value
                else -> return null
            }
            return "$classType.${fd.name}" to v
        }

        private fun <T> seq(f: (T) -> Unit): ISeqConsumer<T> = object : ISeqConsumer<T> {
            override fun accept(t: T) = f(t)
        }
    }
}
