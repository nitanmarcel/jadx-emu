package jadx.plugins.emu.exec.debug

import jadx.plugins.emu.exec.ExecHook
import jadx.plugins.emu.exec.Frame
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.VmAbort
import jadx.plugins.emu.exec.model.DalvikInsn
import jadx.plugins.emu.exec.model.DexMethod
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Lifecycle of a debugged run.
 *
 * `RUNNING` — executing; `STOPPED` — paused at a breakpoint or step; `FINISHED` — the top-level method
 * returned; `DETACHED` — [EmuController.detach] was called and execution was aborted.
 */
public enum class EmuState { RUNNING, STOPPED, FINISHED, DETACHED }

/**
 * One activation on the debugged call stack.
 *
 * @property method the executing method
 * @property frame its live registers; may be modified while stopped
 * @property pc offset of the current instruction
 * @property insn the current instruction
 * @property descriptor the method as `Lcls;->shortId`
 */
public class EmuFrame(public val method: DexMethod, public val frame: Frame) {
    public var pc: Int = 0
    public var insn: DalvikInsn? = null
    public val descriptor: String get() = "${method.declClass}->${method.ref.shortId}"
}

/**
 * Interactive debugger for the emulator, implemented as an [ExecHook].
 *
 * Construct a [Vm] with `hook = controller`, then [start] a method; execution runs on a background thread
 * and blocks inside [onStep] whenever [state] is [EmuState.STOPPED]. All control methods are thread-safe.
 *
 * @property state current lifecycle state
 * @property returnValue result of the top-level method once [EmuState.FINISHED]
 * @property onStop callback invoked on the emulation thread whenever execution stops or finishes
 */
public class EmuController : ExecHook {

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val stack = ArrayDeque<EmuFrame>()
    private val breakpoints = HashSet<String>()
    private var oneShot: String? = null

    private enum class Mode { RUN, INTO, OVER, OUT }
    private var mode = Mode.RUN
    private var baseDepth = 0
    private var entryTarget: String? = null

    @Volatile
    public var state: EmuState = EmuState.RUNNING
        private set

    @Volatile
    public var returnValue: Any? = null
        private set

    public var onStop: Runnable? = null

    private fun bpKey(descriptor: String, pc: Int) = "$descriptor@$pc"

    /**
     * Stop before the instruction at offset [dexPc] of method [descriptor] (`Lcls;->shortId`).
     */
    public fun addBreakpoint(descriptor: String, dexPc: Int): Boolean = lock.withLock { breakpoints.add(bpKey(descriptor, dexPc)) }

    public fun removeBreakpoint(descriptor: String, dexPc: Int): Boolean = lock.withLock { breakpoints.remove(bpKey(descriptor, dexPc)) }

    /**
     * Call stack, innermost frame first.
     */
    public fun frames(): List<EmuFrame> = lock.withLock { stack.toList().asReversed() }

    /**
     * Innermost frame, or null when nothing is executing.
     */
    public fun top(): EmuFrame? = lock.withLock { stack.lastOrNull() }

    /**
     * Begin executing [method] on a new daemon thread.
     *
     * @param vm the VM to run in; must have been created with this controller as its hook
     * @param pauseAtEntry stop at the first instruction of [method]
     * @param runTo if set, run until the instruction at this offset of [method] instead of pausing at entry
     * @throws IllegalArgumentException if [vm] does not use this controller as its hook
     */
    @JvmOverloads
    public fun start(vm: Vm, method: DexMethod, args: List<Any?> = emptyList(), receiver: Any? = null, pauseAtEntry: Boolean = true, runTo: Int? = null) {
        require(vm.hook === this) { "vm must be constructed with this controller as its hook" }
        lock.withLock {
            stack.clear()
            mode = Mode.RUN
            baseDepth = 0
            val descriptor = "${method.declClass}->${method.ref.shortId}"
            oneShot = runTo?.let { bpKey(descriptor, it) }
            entryTarget = if (pauseAtEntry && runTo == null) descriptor else null
            state = EmuState.RUNNING
            returnValue = null
        }
        Thread({
            val r = runCatching { vm.invoke(method, args, receiver) }.getOrNull()
            lock.withLock {
                if (state != EmuState.DETACHED) { returnValue = r; state = EmuState.FINISHED }
                cond.signalAll()
            }
            onStop?.run()
        }, "jdex-emu").apply { isDaemon = true; start() }
    }

    override fun onEnter(method: DexMethod, frame: Frame): Unit = lock.withLock { stack.addLast(EmuFrame(method, frame)) }

    override fun onExit(method: DexMethod) { lock.withLock { stack.removeLastOrNull() } }

    override fun onStep(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int) {
        lock.withLock {
            val top = stack.lastOrNull() ?: return
            top.pc = insn.offset; top.insn = insn
            if (state == EmuState.DETACHED) throw VmAbort("detached")
            if (!shouldStop(top, insn.offset)) return
            state = EmuState.STOPPED
            mode = Mode.RUN
            oneShot = null
            cond.signalAll()
        }
        onStop?.run()
        lock.withLock {
            while (state == EmuState.STOPPED) cond.await()
            if (state == EmuState.DETACHED) throw VmAbort("detached")
        }
    }

    private fun shouldStop(top: EmuFrame, pc: Int): Boolean {
        val key = bpKey(top.descriptor, pc)
        if (key in breakpoints || oneShot == key) return true
        entryTarget?.let { return if (top.descriptor == it) { entryTarget = null; true } else false }
        return when (mode) {
            Mode.INTO -> true
            Mode.OVER -> stack.size <= baseDepth
            Mode.OUT -> stack.size < baseDepth
            Mode.RUN -> false
        }
    }

    /**
     * Continue until the next breakpoint. No-op unless stopped.
     */
    public fun resume(): Unit = signal(Mode.RUN)

    /**
     * Execute one instruction, entering calls. No-op unless stopped.
     */
    public fun stepInto(): Unit = signal(Mode.INTO)

    /**
     * Execute one instruction, running called methods to completion. No-op unless stopped.
     */
    public fun stepOver(): Unit = signal(Mode.OVER)

    /**
     * Continue until the current method returns. No-op unless stopped.
     */
    public fun stepOut(): Unit = signal(Mode.OUT)

    /**
     * Continue until offset [dexPc] of method [descriptor] is reached once. No-op unless stopped.
     */
    public fun runToCursor(descriptor: String, dexPc: Int) {
        lock.withLock { if (state == EmuState.STOPPED) { oneShot = bpKey(descriptor, dexPc); resumeLocked(Mode.RUN) } }
    }

    /**
     * Request a stop at the next instruction. No-op unless running.
     */
    public fun pause(): Unit = lock.withLock { if (state == EmuState.RUNNING) mode = Mode.INTO }

    /**
     * Abort the run; the emulation thread exits at its next step.
     */
    public fun detach(): Unit = lock.withLock { state = EmuState.DETACHED; cond.signalAll() }

    private fun signal(m: Mode) = lock.withLock { if (state == EmuState.STOPPED) resumeLocked(m) }

    private fun resumeLocked(m: Mode) {
        baseDepth = stack.size
        mode = m
        state = EmuState.RUNNING
        cond.signalAll()
    }

    /**
     * Block until execution is no longer [EmuState.RUNNING].
     *
     * @return false on timeout
     */
    @JvmOverloads
    public fun awaitStop(timeoutMs: Long = 5000): Boolean = lock.withLock {
        var remaining = timeoutMs * 1_000_000
        while (state == EmuState.RUNNING && remaining > 0) remaining = cond.awaitNanos(remaining)
        state != EmuState.RUNNING
    }

    /**
     * Block until the top-level method has returned.
     *
     * @return true if [EmuState.FINISHED]; false on timeout or if detached
     */
    @JvmOverloads
    public fun awaitFinished(timeoutMs: Long = 5000): Boolean = lock.withLock {
        var remaining = timeoutMs * 1_000_000
        while (state != EmuState.FINISHED && state != EmuState.DETACHED && remaining > 0) remaining = cond.awaitNanos(remaining)
        state == EmuState.FINISHED
    }
}
