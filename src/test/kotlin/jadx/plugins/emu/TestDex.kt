package jadx.plugins.emu

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.smali.smaliFlexLexer
import com.android.tools.smali.smali.smaliParser
import com.android.tools.smali.smali.smaliTreeWalker
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.ExecHook
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.exec.HostBoundary
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.input.DexInputSource
import jadx.plugins.emu.exec.model.DexMethod
import org.antlr.runtime.CommonTokenStream
import org.antlr.runtime.tree.CommonTreeNodeStream
import java.io.File
import java.io.StringReader
import java.nio.file.Files

object TestDex {
    private const val API = 33

    fun assemble(vararg classes: String): DexInputSource = DexInputSource.load(assembleFile(*classes))

    fun assembleFile(vararg classes: String): File {
        val builder = DexBuilder(Opcodes.forApi(API))
        for (source in classes) {
            val errors = StringBuilder()
            val lexer = smaliFlexLexer(StringReader(source), API)
            lexer.setSourceFile(File("test.smali"))
            val parser = smaliParser(CommonTokenStream(lexer)).apply {
                setVerboseErrors(true)
                setAllowOdex(false)
                setApiLevel(API)
            }
            val result = parser.smali_file()
            if (parser.numberOfSyntaxErrors > 0 || lexer.numberOfSyntaxErrors > 0) {
                throw IllegalArgumentException("smali syntax errors:\n$errors\n$source")
            }
            val walker = smaliTreeWalker(CommonTreeNodeStream(result.tree).apply { tokenStream = parser.tokenStream })
            walker.setApiLevel(API)
            walker.setDexBuilder(builder)
            walker.smali_file()
            if (walker.numberOfSyntaxErrors > 0) throw IllegalArgumentException("smali semantic errors:\n$source")
        }
        val dir = Files.createTempDirectory("emu-test").toFile()
        val dex = File(dir, "classes.dex")
        builder.writeTo(FileDataStore(dex))
        return dex
    }

    fun method(source: DexInputSource, cls: String, shortId: String): DexMethod =
        source.method(cls, shortId) ?: error("no method $cls->$shortId")

    fun context(
        source: DexInputSource,
        limits: ExecLimits = ExecLimits(),
        host: HostBoundary = HostBoundary(),
        hooks: Hooks = Hooks(),
    ): EngineContext = EngineContext(source, limits, host, hooks)

    fun vm(
        source: DexInputSource,
        limits: ExecLimits = ExecLimits(),
        host: HostBoundary = HostBoundary(),
        hooks: Hooks = Hooks(),
        hook: ExecHook? = null,
    ): Vm = context(source, limits, host, hooks).newVm(limits, hook = hook, androidEnvUnknown = false)

    fun run(source: DexInputSource, cls: String, shortId: String, vararg args: Any?): Any? =
        vm(source).invoke(method(source, cls, shortId), args.toList())

    fun call(vm: Vm, source: DexInputSource, cls: String, shortId: String, vararg args: Any?): Any? {
        val m = method(source, cls, shortId)
        vm.ensureClinit(cls)
        return vm.call(m, args.toList(), null)
    }

    fun cls(name: String, body: String, superType: String = "Ljava/lang/Object;"): String =
        ".class public $name\n.super $superType\n$body\n"

    fun staticMethod(sig: String, registers: Int, body: String): String =
        ".method public static $sig\n    .registers $registers\n$body\n.end method\n"
}
