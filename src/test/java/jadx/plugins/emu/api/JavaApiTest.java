package jadx.plugins.emu.api;

import jadx.plugins.emu.TestDex;
import jadx.plugins.emu.exec.EngineContext;
import jadx.plugins.emu.exec.ExecLimits;
import jadx.plugins.emu.exec.HostBoundary;
import jadx.plugins.emu.exec.MethodSources;
import jadx.plugins.emu.exec.Vm;
import jadx.plugins.emu.exec.input.DexInputSource;
import jadx.plugins.emu.exec.model.DexMethod;
import jadx.plugins.emu.exec.runtime.DvmObject;
import jadx.plugins.emu.exec.runtime.Values;
import jadx.plugins.emu.host.EngineEmuContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaApiTest {
    private static final String APP = TestDex.INSTANCE.cls("Lcom/app/A;", ""
            + ".field public static n:I\n"
            + ".method public constructor <init>()V\n"
            + "    .registers 1\n"
            + "    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n"
            + "    return-void\n"
            + ".end method\n"
            + TestDex.INSTANCE.staticMethod("radio()Ljava/lang/String;", 1, ""
            + "    invoke-static {}, Landroid/os/Build;->getRadioVersion()Ljava/lang/String;\n"
            + "    move-result-object v0\n"
            + "    return-object v0\n"), "Ljava/lang/Object;");

    static class Ext implements EmuExtension {
        @Override
        public EmuExtensionInfo info() {
            return new EmuExtensionInfo("java-ext", "Java extension", "written in Java");
        }

        @Override
        public void init(EmuExtensionContext ctx) {
            ctx.getHooks().stub("Landroid/os/Build;->getRadioVersion", "java");
            ctx.getHooks().hook("Lcom/app/A;->radio()Ljava/lang/String;", call -> {
                if (call.getArgs().isEmpty()) call.getVm().setStatic("Lcom/app/A;->n", 5);
            });
            ctx.addCodeAction("Say hi", t -> ctx.getLog().info("hi {}", t));
        }
    }

    @Test
    void javaCanUseTheApi() {
        DexInputSource source = TestDex.INSTANCE.assemble(APP);
        Hooks hooks = new Hooks();
        EmuContext emu = new EngineEmuContext(() -> source, s -> new EngineContext(s, new ExecLimits(), new HostBoundary(), hooks), new ExecLimits());

        hooks.stub("Landroid/os/Build;->getRadioVersion", call -> call.replace("r" + call.getArgs().size()));
        DexMethod radio = emu.method("Lcom/app/A;->radio()Ljava/lang/String;");
        Vm vm = emu.newVm();
        assertEquals("r0", vm.invoke(radio));
        assertEquals("r0", vm.invoke(radio, List.of()));

        vm.setStatic("Lcom/app/A;->n", 7);
        assertEquals(7, vm.getStatic("Lcom/app/A;->n"));
        assertTrue(Values.isUnknown(vm.getStatic("Lcom/app/A;->missing")));

        DvmObject obj = vm.construct("Lcom/app/A;");
        assertEquals("Lcom/app/A;", obj.getType());

        EmuContext fork = emu.fork();
        assertEquals(0, fork.newVm().getStatic("Lcom/app/A;->n"));
        EmuContext layered = emu.fork(MethodSources.plus(source, source), hooks);
        assertEquals("r0", layered.newVm().invoke(radio));

        new Ext().info();
        new ExecLimits(10, 10, 10);
        HostBoundary.disabled();
        new AndroidEnv();
    }
}
