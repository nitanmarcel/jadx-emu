package jadx.plugins.emu.api

import jadx.plugins.emu.TestDex
import jadx.plugins.emu.TestDex.cls
import jadx.plugins.emu.TestDex.staticMethod
import jadx.plugins.emu.exec.Diagnostic
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.HostBoundary
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.plus
import jadx.plugins.emu.exec.runtime.isUnknown
import jadx.plugins.emu.host.EngineEmuContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmuContextTest {
    private val app = cls("Lcom/app/A;", """
        .field public static n:I
    """ + staticMethod("bump()I", 1, """
            sget v0, Lcom/app/A;->n:I
            add-int/lit8 v0, v0, 1
            sput v0, Lcom/app/A;->n:I
            return v0
        """) + staticMethod("radio()Ljava/lang/String;", 1, """
            invoke-static {}, Landroid/os/Build;->getRadioVersion()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """))

    private val patch = cls("Lcom/app/A;", staticMethod("bump()I", 1, """
            const/16 v0, 100
            return v0
        """))

    private val extra = cls("Lcom/app/B;", staticMethod("two()I", 1, """
            const/4 v0, 2
            return v0
        """))

    private fun emu(src: MethodSource = TestDex.assemble(app), hooks: Hooks = Hooks()): EngineEmuContext =
        EngineEmuContext({ src }, { EngineContext(it, ExecLimits(), HostBoundary(), hooks) }, ExecLimits())

    @Test
    fun methodByDescriptorAndTarget() {
        val emu = emu()
        assertNotNull(emu.method("Lcom/app/A;->bump()I"))
        assertNull(emu.method("Lcom/app/A;->nope()I"))
        assertNull(emu.method("garbage"))
        assertNotNull(emu.method(EmuTarget("com.app.A", "bump()I")))
        assertNull(emu.method(EmuTarget("com.app.A")))
    }

    @Test
    fun vmsShareStaticsAndDataflowDoesNot() {
        val emu = emu()
        val bump = emu.method("Lcom/app/A;->bump()I")!!
        assertEquals(1, emu.newVm().invoke(bump))
        assertEquals(2, emu.newVm().invoke(bump))
        assertTrue(emu.newDataflow().analyze(bump).returnValue.isUnknown())
        assertEquals(3, emu.newVm().invoke(bump))
    }

    @Test
    fun forkHasOwnStaticsButSharesPolicyAndDiagnostics() {
        val emu = emu()
        val bump = emu.method("Lcom/app/A;->bump()I")!!
        emu.newVm().invoke(bump)
        val fork = emu.fork()
        assertEquals(1, fork.newVm().invoke(fork.method("Lcom/app/A;->bump()I")!!))
        assertEquals(2, emu.newVm().invoke(bump))
        assertSame(emu.host, fork.host)
        val diags = ArrayList<Diagnostic>()
        emu.diagnostics.add { diags.add(it) }
        val fork2 = emu.fork()
        fork2.newVm().invoke(fork2.method("Lcom/app/A;->radio()Ljava/lang/String;")!!)
        assertEquals(listOf(Diagnostic.Kind.MISSING_STUB), diags.map { it.kind })
    }

    @Test
    fun forkUsesGivenOrFreshHooks() {
        val hooks = Hooks()
        hooks.stub("Landroid/os/Build;->getRadioVersion", "r1")
        val emu = emu(hooks = hooks)
        val radio = "Lcom/app/A;->radio()Ljava/lang/String;"
        assertEquals("r1", emu.newVm().invoke(emu.method(radio)!!))
        val fresh = emu.fork()
        assertTrue(fresh.newVm().invoke(fresh.method(radio)!!).isUnknown())
        val shared = emu.fork(hooks = hooks)
        assertEquals("r1", shared.newVm().invoke(shared.method(radio)!!))
    }

    @Test
    fun forkOverLayeredSource() {
        val emu = emu()
        val patched = emu.fork(source = TestDex.assemble(patch) + emu.source + TestDex.assemble(extra))
        assertEquals(100, patched.newVm().invoke(patched.method("Lcom/app/A;->bump()I")!!))
        assertEquals(2, patched.newVm().invoke(patched.method("Lcom/app/B;->two()I")!!))
        assertNotNull(patched.method("Lcom/app/A;->radio()Ljava/lang/String;"))
        assertEquals(1, emu.newVm().invoke(emu.method("Lcom/app/A;->bump()I")!!))
    }

    @Test
    fun loadDexFromFileAndBytes() {
        val emu = emu()
        val file = TestDex.assembleFile(extra)
        assertNotNull(emu.loadDex(file.toPath()).method("Lcom/app/B;", "two()I"))
        assertNotNull(emu.loadDex(file.readBytes()).method("Lcom/app/B;", "two()I"))
    }
}
