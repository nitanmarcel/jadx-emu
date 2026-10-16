package jadx.plugins.emu.exec

import jadx.plugins.emu.TestDex
import jadx.plugins.emu.TestDex.cls
import jadx.plugins.emu.TestDex.staticMethod
import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.exec.runtime.DvmThrowable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AfterHooksTest {
    private val c = "LT;"

    private val source = TestDex.assemble(cls(c, staticMethod("twice(I)I", 2, """
            mul-int/lit8 v0, p0, 2
            return v0
        """) + staticMethod("f(I)I", 2, """
            invoke-static {p0}, LT;->twice(I)I
            move-result v0
            add-int/lit8 v0, v0, 1
            return v0
        """) + staticMethod("upper(Ljava/lang/String;)Ljava/lang/String;", 1, """
            invoke-virtual {p0}, Ljava/lang/String;->toUpperCase()Ljava/lang/String;
            move-result-object p0
            return-object p0
        """) + staticMethod("radio()Ljava/lang/String;", 1, """
            invoke-static {}, Landroid/os/Build;->getRadioVersion()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """) + staticMethod("boom()I", 2, """
            new-instance v0, Ljava/lang/RuntimeException;
            const-string v1, "boom"
            invoke-direct {v0, v1}, Ljava/lang/RuntimeException;-><init>(Ljava/lang/String;)V
            throw v0
        """) + staticMethod("callBoom()I", 1, """
            invoke-static {}, LT;->boom()I
            move-result v0
            return v0
        """) + staticMethod("build()Ljava/lang/String;", 2, """
            new-instance v0, Ljava/lang/StringBuilder;
            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
            const-string v1, "hi"
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            move-result-object v0
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """) + staticMethod("cname()Ljava/lang/String;", 1, """
            const-class v0, Ljava/lang/String;
            invoke-virtual {v0}, Ljava/lang/Class;->getName()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """) + staticMethod("viaNative(I)I", 2, """
            invoke-static {p0}, LT;->nat(I)I
            move-result v0
            return v0
        """) + ".method public static native nat(I)I\n.end method\n"))

    private fun f(hooks: Hooks, x: Int): Any? = TestDex.call(TestDex.vm(source, hooks = hooks), source, c, "f(I)I", x)

    @Test
    fun afterSeesArgumentsAndRealResultOfEmulatedMethod() {
        val hooks = Hooks()
        val seen = ArrayList<Pair<List<Any?>, Any?>>()
        hooks.after("$c->twice(I)I") { result ->
            seen.add(args.toList() to result)
            result
        }
        assertEquals(15, f(hooks, 7))
        assertEquals(listOf(listOf<Any?>(7) to 14), seen)
    }

    @Test
    fun afterCanRewriteTheResult() {
        val hooks = Hooks()
        hooks.after("$c->twice") { 100 }
        assertEquals(101, f(hooks, 7))
    }

    @Test
    fun afterRunsOnHostMethodResults() {
        val hooks = Hooks()
        hooks.after("Ljava/lang/String;->toUpperCase()Ljava/lang/String;") { result -> (result as String) + "!" }
        val vm = TestDex.vm(source, hooks = hooks)
        assertEquals("ABC!", TestDex.call(vm, source, c, "upper(Ljava/lang/String;)Ljava/lang/String;", "abc"))
    }

    @Test
    fun afterSeesTheResultOfAStubbedCall() {
        val hooks = Hooks()
        hooks.stub("Landroid/os/Build;->getRadioVersion", "1.2.3")
        val seen = ArrayList<Any?>()
        hooks.after("Landroid/os/Build;->getRadioVersion") { result -> seen.add(result); (result as String) + "!" }
        val vm = TestDex.vm(source, hooks = hooks)
        assertEquals("1.2.3!", TestDex.call(vm, source, c, "radio()Ljava/lang/String;"))
        assertEquals(listOf<Any?>("1.2.3"), seen)
    }

    @Test
    fun replacedCallIsNotSeenByAfter() {
        val hooks = Hooks()
        var calls = 0
        hooks.hook("$c->twice") { replace(50) }
        hooks.after("$c->twice") { result -> calls++; result }
        assertEquals(51, f(hooks, 7))
        assertEquals(0, calls)
    }

    @Test
    fun afterHandlersChainNewestFirst() {
        val hooks = Hooks()
        val order = ArrayList<String>()
        hooks.after("$c->twice") { result -> order.add("older"); (result as Int) + 1 }
        hooks.after("$c->twice") { result -> order.add("newer"); (result as Int) * 10 }
        // twice(7) = 14 -> newer: 140 -> older: 141 -> f adds 1
        assertEquals(142, f(hooks, 7))
        assertEquals(listOf("newer", "older"), order)
    }

    @Test
    fun failingAfterHandlerIsReportedAndKeepsTheRealResult() {
        val hooks = Hooks()
        hooks.after("$c->twice") { throw IllegalStateException("bang") }
        val diags = ArrayList<Diagnostic>()
        val vm = TestDex.context(source, hooks = hooks).apply { diagnostics.add { diags.add(it) } }.newVm(androidEnvUnknown = false)
        assertEquals(15, TestDex.call(vm, source, c, "f(I)I", 7))
        assertEquals(
            listOf("twice: java.lang.IllegalStateException: bang"),
            diags.filter { it.kind == Diagnostic.Kind.HANDLER_FAILED }.map { it.ref.name + ": " + it.detail },
        )
    }

    @Test
    fun registrationRemoveAndScopeClearStopTheHandler() {
        val hooks = Hooks()
        val reg = hooks.after("$c->twice") { 100 }
        assertEquals(101, f(hooks, 7))
        reg.remove()
        assertEquals(15, f(hooks, 7))

        val scope = hooks.scope()
        scope.after("$c->twice") { 100 }
        assertEquals(101, f(hooks, 7))
        scope.clear()
        assertEquals(15, f(hooks, 7))
    }

    @Test
    fun exactSignatureHandlersRunBeforeNameOnlyOnes() {
        val hooks = Hooks()
        hooks.after("$c->twice(I)I") { result -> (result as Int) * 10 }
        hooks.after("$c->twice") { result -> (result as Int) + 1 }
        // exact first although registered earlier: 14 * 10 = 140 -> + 1 = 141 -> f adds 1
        // (by recency alone it would be (14 + 1) * 10 = 150 -> 151)
        assertEquals(142, f(hooks, 7))
    }

    @Test
    fun afterIsNotRunForACallThatThrew() {
        val hooks = Hooks()
        var calls = 0
        hooks.after("$c->boom") { result -> calls++; result }
        val vm = TestDex.vm(source, hooks = hooks)
        assertThrows(DvmThrowable::class.java) { TestDex.call(vm, source, c, "callBoom()I") }
        assertEquals(0, calls)
    }

    @Test
    fun afterIsNotRunForHostObjectConstruction() {
        val hooks = Hooks()
        var inits = 0
        var toStrings = 0
        hooks.after("Ljava/lang/StringBuilder;-><init>") { result -> inits++; result }
        hooks.after("Ljava/lang/StringBuilder;->toString") { result -> toStrings++; result }
        val vm = TestDex.vm(source, hooks = hooks)
        assertEquals("hi", TestDex.call(vm, source, c, "build()Ljava/lang/String;"))
        assertEquals(0, inits)
        assertEquals(1, toStrings)
    }

    @Test
    fun afterRunsOnReflectionResults() {
        val hooks = Hooks()
        hooks.after("Ljava/lang/Class;->getName") { result -> (result as String) + "!" }
        val vm = TestDex.vm(source, hooks = hooks)
        assertEquals("java.lang.String!", TestDex.call(vm, source, c, "cname()Ljava/lang/String;"))
    }

    @Test
    fun afterRunsOnNativeBridgeResults() {
        val hooks = Hooks()
        val seen = ArrayList<Any?>()
        hooks.after("$c->nat") { result -> seen.add(result); (result as Int) + 1000 }
        val bridge = object : NativeBridge {
            override fun call(className: String, methodName: String, signature: String, args: List<Any?>, receiver: Any?): Any? =
                (args[0] as Int) * 3
        }
        val vm = TestDex.context(source, hooks = hooks).newVm(nativeBridge = bridge, androidEnvUnknown = false)
        assertEquals(1021, TestDex.call(vm, source, c, "viaNative(I)I", 7))
        assertEquals(listOf<Any?>(21), seen)
    }

    @Test
    fun rewrittenResultIsCoercedToTheDeclaredReturnType() {
        val hooks = Hooks()
        hooks.after("$c->twice") { 100L }
        assertEquals(101, f(hooks, 7))
    }

    @Test
    fun replaceInsideAnAfterHandlerIsReportedAndIgnored() {
        val hooks = Hooks()
        hooks.after("$c->twice") { result -> replace(5); result }
        val diags = ArrayList<Diagnostic>()
        val vm = TestDex.context(source, hooks = hooks).apply { diagnostics.add { diags.add(it) } }.newVm(androidEnvUnknown = false)
        assertEquals(15, TestDex.call(vm, source, c, "f(I)I", 7))
        assertEquals(1, diags.count { it.kind == Diagnostic.Kind.HANDLER_FAILED && it.detail.startsWith("replace()") })
    }
}
