package jadx.plugins.emu.exec

import jadx.plugins.emu.TestDex
import jadx.plugins.emu.TestDex.cls
import jadx.plugins.emu.TestDex.staticMethod
import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.exec.runtime.isUnknown
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VmAccessTest {
    private val base = """
        .class public LBase;
        .super Ljava/lang/Object;
        .field public x:I
        .field public static count:I
        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method
        .method public describe()Ljava/lang/String;
            .registers 2
            const-string v0, "base"
            return-object v0
        .end method
    """.trimIndent()

    private val sub = """
        .class public LSub;
        .super LBase;
        .field public x:Ljava/lang/String;
        .field public static name:Ljava/lang/String;
        .method static constructor <clinit>()V
            .registers 1
            const-string v0, "init"
            sput-object v0, LSub;->name:Ljava/lang/String;
            const/4 v0, 7
            sput v0, LBase;->count:I
            return-void
        .end method
        .method public constructor <init>(Ljava/lang/String;)V
            .registers 2
            invoke-direct {p0}, LBase;-><init>()V
            iput-object p1, p0, LSub;->x:Ljava/lang/String;
            const/4 p1, 5
            iput p1, p0, LBase;->x:I
            return-void
        .end method
        .method public describe()Ljava/lang/String;
            .registers 2
            const-string v0, "sub"
            return-object v0
        .end method
    """.trimIndent()

    private val t = cls("LT;", staticMethod("readName()Ljava/lang/String;", 1, """
            sget-object v0, LSub;->name:Ljava/lang/String;
            return-object v0
        """))

    private val source = TestDex.assemble(base, sub, t)

    @Test
    fun getStaticInitializesClassAndReadsDefaults() {
        val vm = TestDex.vm(source)
        assertEquals("init", vm.getStatic("LSub;->name"))
        assertEquals(7, vm.getStatic("LBase;->count"))
        assertEquals(0, TestDex.vm(source).getStatic("LBase;->count"))
        assertTrue(vm.getStatic("LSub;->missing").isUnknown())
    }

    @Test
    fun setStaticIsSeenByEmulatedCode() {
        val vm = TestDex.vm(source)
        vm.setStatic("LSub;->name", "changed")
        assertEquals("changed", TestDex.call(vm, source, "LT;", "readName()Ljava/lang/String;"))
    }

    @Test
    fun getStaticOnHostClasses() {
        val vm = TestDex.vm(source)
        assertEquals(Int.MAX_VALUE, vm.getStatic("Ljava/lang/Integer;->MAX_VALUE"))
        assertEquals("Pixel 6", vm.getStatic("Landroid/os/Build;->MODEL"))
        assertTrue(vm.getStatic("Ljava/lang/Integer;->nope").isUnknown())
        assertTrue(TestDex.vm(source, host = HostBoundary.disabled()).getStatic("Ljava/lang/Integer;->MAX_VALUE").isUnknown())
    }

    @Test
    fun constructRunsConstructorAndFieldsResolveOnClassChain() {
        val vm = TestDex.vm(source)
        val obj = vm.construct("LSub;", "(Ljava/lang/String;)V", listOf("hello"))
        assertEquals("LSub;", obj.type)
        assertEquals("hello", vm.getField(obj, "x"))
        assertEquals("hello", obj.fields["LSub;.x"])
        assertEquals(5, obj.fields["LBase;.x"])
        vm.setField(obj, "x", "bye")
        assertEquals("bye", obj.fields["LSub;.x"])
        assertEquals(0, vm.getField(vm.construct("LBase;"), "x"))
        assertTrue(vm.getField(obj, "nope").isUnknown())
    }

    @Test
    fun constructFrameworkAndMissing() {
        val vm = TestDex.vm(source, hooks = Hooks())
        assertEquals("Landroid/os/Bundle;", vm.construct("Landroid/os/Bundle;").type)
        assertThrows(IllegalArgumentException::class.java) { vm.construct("LSub;") }
        assertThrows(IllegalArgumentException::class.java) { vm.construct("Landroid/os/Bundle;", "(Ljava/lang/String;)V") }
    }

    @Test
    fun invokeOnObjectDispatchesVirtually() {
        val vm = TestDex.vm(source)
        assertEquals("sub", vm.invoke(vm.construct("LSub;", "(Ljava/lang/String;)V", listOf("hello")), "describe()Ljava/lang/String;"))
        assertEquals("base", vm.invoke(vm.construct("LBase;"), "describe()Ljava/lang/String;"))
        assertEquals("HELLO", vm.invoke("hello", "toUpperCase()Ljava/lang/String;"))
    }
}
