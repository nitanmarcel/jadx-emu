package jadx.plugins.emu.api

import jadx.plugins.emu.exec.DiagnosticSink
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.HostBoundary
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.graph.Dataflow
import jadx.plugins.emu.exec.model.DexMethod
import java.nio.file.Path

/**
 * Emulator for the loaded application, shared by all extensions of a project load.
 *
 * Members are created on first access. Static fields written by one VM are visible to every VM
 * created by [newVm].
 *
 * ```kotlin
 * val method = ctx.emu.method("Lcom/app/Crypto;->key()Ljava/lang/String;") ?: return
 * val value = ctx.emu.newVm().invoke(method)
 * ```
 */
interface EmuContext {
    /**
     * Classes and method bodies of the loaded application.
     *
     * Built from the classes jadx has loaded, in any input format jadx supports. When accessed before jadx
     * has loaded the inputs, for example from [EmuExtension.applies], the input files are parsed directly;
     * this supports only dex and apk files.
     */
    val source: MethodSource

    /**
     * Limits used by [newVm] and [newDataflow] unless overridden.
     */
    val limits: ExecLimits

    /**
     * Which host (JVM) classes emulated code may execute.
     */
    val host: HostBoundary

    /**
     * Sinks receiving a report for every call the emulator could not execute.
     */
    val diagnostics: MutableList<DiagnosticSink>

    /**
     * Creates a VM that executes methods of [source] with the configured Android device values.
     */
    fun newVm(limits: ExecLimits = this.limits): Vm

    /**
     * Creates a dataflow analysis over a new VM.
     *
     * The VM does not share static fields with [newVm] and treats Android device values as unknown, so
     * results do not depend on the configured device.
     */
    fun newDataflow(limits: ExecLimits = this.limits): Dataflow

    /**
     * Returns the method `Lcls;->name(args)ret`, or `null` if [source] has no such method.
     */
    fun method(descriptor: String): DexMethod?

    /**
     * Returns the method of a method target, or `null` for a class target or an unknown method.
     */
    fun method(target: EmuTarget): DexMethod?

    /**
     * Loads a dex, apk or jar file.
     */
    fun loadDex(file: Path): MethodSource

    /**
     * Loads a dex file from memory.
     */
    fun loadDex(bytes: ByteArray): MethodSource

    /**
     * An emulator with the same policy, device values and diagnostics but its own static fields.
     *
     * @param source code to execute, for example `emu.source + loadDex(bytes)`
     * @param hooks hooks and stubs to use; `null` for a fresh registry
     */
    fun fork(source: MethodSource = this.source, hooks: Hooks? = null, limits: ExecLimits = this.limits): EmuContext
}
