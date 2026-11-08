package jadx.plugins.emu.exec

import jadx.plugins.emu.TestDex
import jadx.plugins.emu.api.AndroidEnv
import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.TestDex.cls
import jadx.plugins.emu.TestDex.staticMethod
import jadx.plugins.emu.exec.model.DalvikInsn
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.runtime.UnknownVal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HooksStubsTest {
    private val c = "LT;"

    private val source = TestDex.assemble(cls(c, staticMethod("twice(I)I", 2, """
            mul-int/lit8 v0, p0, 2
            return v0
        """) + staticMethod("f(I)I", 2, """
            invoke-static {p0}, LT;->twice(I)I
            move-result v0
            add-int/lit8 v0, v0, 1
            return v0
        """) + staticMethod("radio()Ljava/lang/String;", 1, """
            invoke-static {}, Landroid/os/Build;->getRadioVersion()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """) + staticMethod("model()Ljava/lang/String;", 1, """
            sget-object v0, Landroid/os/Build;->MODEL:Ljava/lang/String;
            return-object v0
        """) + staticMethod("caller()Ljava/lang/String;", 3, """
            invoke-static {}, Ljava/lang/Thread;->currentThread()Ljava/lang/Thread;
            move-result-object v0
            invoke-virtual {v0}, Ljava/lang/Thread;->getStackTrace()[Ljava/lang/StackTraceElement;
            move-result-object v0
            const/4 v1, 2
            aget-object v0, v0, v1
            invoke-virtual {v0}, Ljava/lang/StackTraceElement;->getClassName()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """) + staticMethod("upper(Ljava/lang/String;)Ljava/lang/String;", 1, """
            invoke-virtual {p0}, Ljava/lang/String;->toUpperCase()Ljava/lang/String;
            move-result-object p0
            return-object p0
        """) + staticMethod("boom()I", 2, """
            new-instance v0, Ljava/lang/IllegalStateException;
            invoke-direct {v0}, Ljava/lang/IllegalStateException;-><init>()V
            throw v0
        """) + staticMethod("safe()I", 2, """
            :try
            invoke-static {}, LT;->boom()I
            move-result v0
            :end
            return v0
            :catch
            move-exception v0
            const/4 v0, -1
            return v0
            .catch Ljava/lang/IllegalStateException; {:try .. :end} :catch
        """) + staticMethod("log(Ljava/lang/String;)I", 2, """
            const-string v0, "tag"
            invoke-static {v0, p0}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I
            move-result v0
            return v0
        """)))

    private fun radio(hooks: Hooks): Any? = TestDex.call(TestDex.vm(source, hooks = hooks), source, c, "radio()Ljava/lang/String;")

    @Test
    fun hookReplacesResultAndSeesArguments() {
        val hooks = Hooks()
        val seen = ArrayList<List<Any?>>()
        val reg = hooks.hook("$c->twice(I)I") {
            seen.add(args.toList())
            replace(100)
        }
        val vm = TestDex.vm(source, hooks = hooks)
        assertEquals(101, TestDex.call(vm, source, c, "f(I)I", 7))
        assertEquals(listOf(listOf(7)), seen)
        reg.remove()
        assertEquals(15, TestDex.call(vm, source, c, "f(I)I", 7))
    }

    @Test
    fun hookWithoutReplaceLetsOriginalRun() {
        val hooks = Hooks()
        var calls = 0
        hooks.hook("$c->twice(I)I") { calls++ }
        val vm = TestDex.vm(source, hooks = hooks)
        assertEquals(15, TestDex.call(vm, source, c, "f(I)I", 7))
        assertEquals(1, calls)
    }

    @Test
    fun hookCanRewriteArguments() {
        val hooks = Hooks()
        hooks.hook("$c->twice") { args[0] = 10 }
        assertEquals(21, TestDex.call(TestDex.vm(source, hooks = hooks), source, c, "f(I)I", 7))
    }

    @Test
    fun afterHookSeesAndRewritesResult() {
        val hooks = Hooks()
        val seen = ArrayList<Any?>()
        hooks.hookAfter("$c->twice(I)I") { seen.add(result); replace((result as Int) + 1) }
        hooks.hookAfter("Ljava/lang/String;->toUpperCase()Ljava/lang/String;") { replace(result.toString() + "!") }
        val vm = TestDex.vm(source, hooks = hooks)
        assertEquals(16, TestDex.call(vm, source, c, "f(I)I", 7))
        assertEquals(listOf<Any?>(14), seen)
        assertEquals("ABC!", TestDex.call(vm, source, c, "upper(Ljava/lang/String;)Ljava/lang/String;", "abc"))
    }

    @Test
    fun afterHookRunsAfterBeforeHookAndNotOnThrow() {
        val hooks = Hooks()
        val order = ArrayList<String>()
        hooks.hook("$c->twice") { order.add("before"); replace(10) }
        hooks.hookAfter("$c->twice") { order.add("after " + result) }
        hooks.hookAfter("$c->boom") { order.add("boom") }
        val vm = TestDex.vm(source, hooks = hooks)
        assertEquals(11, TestDex.call(vm, source, c, "f(I)I", 7))
        assertEquals(-1, TestDex.call(vm, source, c, "safe()I"))
        assertEquals(listOf("before", "after 10"), order)
    }

    @Test
    fun hookOnHostMethodAndReceiver() {
        val hooks = Hooks()
        var seen: Any? = null
        hooks.hook("Ljava/lang/String;->toUpperCase()Ljava/lang/String;") {
            seen = receiver
            replace("hooked")
        }
        val vm = TestDex.vm(source, hooks = hooks)
        assertEquals("hooked", TestDex.call(vm, source, c, "upper(Ljava/lang/String;)Ljava/lang/String;", "abc"))
        assertEquals("abc", seen)
    }

    @Test
    fun newestMatchingHandlerRunsFirstAndFirstReplaceWins() {
        val hooks = Hooks()
        val order = ArrayList<String>()
        hooks.hook("$c->twice") { order.add("name"); replace(1) }
        hooks.hook("$c->twice(I)I") { order.add("exact") }
        hooks.hook("$c->twice") { order.add("name2"); replace(2) }
        assertEquals(3, TestDex.call(TestDex.vm(source, hooks = hooks), source, c, "f(I)I", 7))
        assertEquals(listOf("exact", "name2"), order)
    }

    @Test
    fun frameworkMethodWithoutStubIsUnknownAndReported() {
        val diags = ArrayList<Diagnostic>()
        val ctx = TestDex.context(source).apply { diagnostics.add { diags.add(it) } }
        val vm = ctx.newVm(androidEnvUnknown = false)
        val r = TestDex.call(vm, source, c, "radio()Ljava/lang/String;")
        assertInstanceOf(UnknownVal::class.java, r)
        val d = diags.single { it.kind == Diagnostic.Kind.MISSING_STUB }
        assertEquals("Landroid/os/Build;", d.ref.declClass)
        assertEquals("getRadioVersion()Ljava/lang/String;", d.ref.shortId)
        assertEquals("$c->radio()Ljava/lang/String;@0", d.site)
    }

    @Test
    fun stubAnswersFrameworkMethod() {
        val hooks = Hooks()
        hooks.stub("Landroid/os/Build;->getRadioVersion", "1.2.3")
        assertEquals("1.2.3", radio(hooks))
        hooks.clear()
        assertInstanceOf(UnknownVal::class.java, radio(hooks))
    }

    @Test
    fun stubWithoutReplaceFallsThrough() {
        val hooks = Hooks()
        hooks.stub("Landroid/os/Build;->getRadioVersion") { }
        assertInstanceOf(UnknownVal::class.java, radio(hooks))
    }

    @Test
    fun userStubOverridesBuiltinAndClearRestoresIt() {
        val hooks = Hooks()
        val vm = TestDex.vm(source, hooks = hooks)
        assertEquals(0, TestDex.call(vm, source, c, "log(Ljava/lang/String;)I", "x"))
        hooks.stub("Landroid/util/Log;->d") { replace((args[1] as String).length) }
        assertEquals(3, TestDex.call(vm, source, c, "log(Ljava/lang/String;)I", "abc"))
        hooks.clear()
        assertEquals(0, TestDex.call(vm, source, c, "log(Ljava/lang/String;)I", "abc"))
    }

    @Test
    fun buildFieldsComeFromEnvOrRegisteredValue() {
        val hooks = Hooks(AndroidEnv(model = "TestPhone"))
        assertEquals("TestPhone", TestDex.call(TestDex.vm(source, hooks = hooks), source, c, "model()Ljava/lang/String;"))
        val reg = hooks.field("Landroid/os/Build;->MODEL", "Override")
        assertEquals("Override", TestDex.call(TestDex.vm(source, hooks = hooks), source, c, "model()Ljava/lang/String;"))
        reg.remove()
        assertEquals("TestPhone", TestDex.call(TestDex.vm(source, hooks = hooks), source, c, "model()Ljava/lang/String;"))
        val unknownEnv = TestDex.context(source, hooks = hooks).newVm(androidEnvUnknown = true)
        assertInstanceOf(UnknownVal::class.java, TestDex.call(unknownEnv, source, c, "model()Ljava/lang/String;"))
    }

    @Test
    fun scopeRegistrationsAreClearedIndependently() {
        val hooks = Hooks()
        val a = hooks.scope()
        val b = hooks.scope()
        a.stub("Landroid/os/Build;->getRadioVersion", "a")
        b.stub("Landroid/os/Build;->getRadioVersion", "b")
        assertEquals("b", radio(hooks))
        b.clear()
        assertEquals("a", radio(hooks))
        hooks.clear()
        assertInstanceOf(UnknownVal::class.java, radio(hooks))
        assertEquals(0, TestDex.call(TestDex.vm(source, hooks = hooks), source, c, "log(Ljava/lang/String;)I", "x"))
    }

    @Test
    fun failingHandlerIsReportedAndYieldsUnknown() {
        val hooks = Hooks()
        hooks.stub("Landroid/os/Build;->getRadioVersion") { error("boom") }
        hooks.hook("$c->twice") { throw IllegalStateException("bang") }
        val diags = ArrayList<Diagnostic>()
        val vm = TestDex.context(source, hooks = hooks).apply { diagnostics.add { diags.add(it) } }.newVm(androidEnvUnknown = false)
        assertInstanceOf(UnknownVal::class.java, TestDex.call(vm, source, c, "radio()Ljava/lang/String;"))
        assertInstanceOf(UnknownVal::class.java, TestDex.call(vm, source, c, "f(I)I", 7))
        assertEquals(
            listOf("getRadioVersion: java.lang.IllegalStateException: boom", "twice: java.lang.IllegalStateException: bang"),
            diags.filter { it.kind == Diagnostic.Kind.HANDLER_FAILED }.map { it.ref.name + ": " + it.detail },
        )
    }

    @Test
    fun handlerCanThrowEmulatedException() {
        val hooks = Hooks()
        hooks.hook("$c->twice") { throw jadx.plugins.emu.exec.runtime.DvmThrowable("Ljava/lang/IllegalStateException;", "no", null) }
        val diags = ArrayList<Diagnostic>()
        val vm = TestDex.context(source, hooks = hooks).apply { diagnostics.add { diags.add(it) } }.newVm(androidEnvUnknown = false)
        assertInstanceOf(UnknownVal::class.java, vm.invoke(TestDex.method(source, c, "f(I)I"), listOf(7)))
        assertEquals(listOf(Diagnostic.Kind.UNCAUGHT), diags.map { it.kind })
    }

    @Test
    fun syntheticCallerShapesStackTrace() {
        val vm = TestDex.vm(source)
        vm.syntheticCaller = "com.example.App"
        assertEquals("com.example.App", TestDex.call(vm, source, c, "caller()Ljava/lang/String;"))
        val fromEnv = TestDex.vm(source, hooks = Hooks(AndroidEnv(syntheticCaller = "com.example.Env")))
        assertEquals("com.example.Env", TestDex.call(fromEnv, source, c, "caller()Ljava/lang/String;"))
    }

    @Test
    fun execHookSeesEnterStepExitInOrder() {
        val events = ArrayList<String>()
        val hook = object : ExecHook {
            override fun onEnter(method: DexMethod, frame: Frame) { events.add("enter ${method.ref.name}") }
            override fun onStep(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int) {
                if (events.last() != "step ${method.ref.name}") events.add("step ${method.ref.name}")
            }
            override fun onExit(method: DexMethod) { events.add("exit ${method.ref.name}") }
        }
        val vm = TestDex.vm(source, hook = hook)
        assertEquals(15, TestDex.call(vm, source, c, "f(I)I", 7))
        assertEquals(listOf("enter f", "step f", "enter twice", "step twice", "exit twice", "step f", "exit f"), events)
    }
}
