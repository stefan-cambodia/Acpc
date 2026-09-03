package dev.stefan.acpc.core.machine

import dev.stefan.acpc.core.api.AudioSink
import dev.stefan.acpc.core.api.RomSet
import dev.stefan.acpc.core.api.VideoFrame
import dev.stefan.acpc.core.ay.Ay38912
import dev.stefan.acpc.core.cpu.z80.Z80
import dev.stefan.acpc.core.cpu.z80.Z80Bus
import dev.stefan.acpc.core.crtc.Crtc
import dev.stefan.acpc.core.fdc.Upd765
import dev.stefan.acpc.core.gatearray.GateArray
import dev.stefan.acpc.core.joystick.JoystickState
import dev.stefan.acpc.core.keyboard.KeyQueue
import dev.stefan.acpc.core.keyboard.KeyTyper
import dev.stefan.acpc.core.keyboard.KeyboardMatrix
import dev.stefan.acpc.core.memory.CpcMemory
import dev.stefan.acpc.core.ppi.Ppi8255
import dev.stefan.acpc.core.timing.CpcTiming

/**
 * A complete Amstrad CPC: wires the Z80 to the memory, the Gate Array, the
 * CRTC, the PPI, the AY and the FDC, decodes the I/O address space and runs
 * the emulation frame by frame.
 *
 * ## Clock model
 * The Z80 counts T-states ([Z80.cycles]); one microsecond is 4 T-states. The
 * video subsystem (CRTC + Gate Array) is advanced one microsecond at a time
 * by [syncVideo], called before every instruction and before every I/O
 * access, so that register writes to the CRTC / Gate Array land on the exact
 * microsecond where the real hardware would see them. The AY and the FDC are
 * caught up lazily on access and at the end of each frame.
 *
 * This class is not thread-safe: the front-end drives it from a single
 * emulation thread (see [dev.stefan.acpc.core.api.CpcEmulator]).
 */
class CpcMachine(
    val model: CpcModel,
    val crtcType: CrtcType,
    roms: RomSet,
    var audioSink: AudioSink,
) : Z80Bus {

    val memory = CpcMemory(model, roms)
    val cpu = Z80(this)
    val crtc = Crtc(crtcType)
    val gateArray = GateArray(memory, crtc) { asserted -> cpu.intLine = asserted }
    val psg = Ay38912(audioSink.sampleRate)
    val keyboard = KeyboardMatrix()
    val joystick0 = JoystickState(keyboard, 0)
    val joystick1 = JoystickState(keyboard, 1)
    val keyTyper = KeyTyper(keyboard)
    val keyQueue = KeyQueue(keyboard)
    val fdc = Upd765()

    /**
     * Debugging aid: called before every instruction while set (tests,
     * tracing tools). Null in normal operation, which keeps the fast loop.
     */
    @Volatile var instructionHook: ((CpcMachine) -> Unit)? = null

    private var keyboardLine = 0
    var cassetteMotor = false
        private set

    /** The cassette in the recorder, if any. */
    @Volatile var tape: dev.stefan.acpc.core.tape.Tape? = null
        private set

    fun insertTape(t: dev.stefan.acpc.core.tape.Tape?) {
        tape?.setMotor(false, cpu.cycles)
        tape = t
        t?.lastCyclesHint = cpu.cycles
        t?.setMotor(cassetteMotor, cpu.cycles)
    }

    private val ppiInputs = object : Ppi8255.Inputs {
        override val vsync: Boolean get() = gateArray.vsyncActive
        override val manufacturerId: Int get() = model.manufacturerId
        override val cassetteInput: Boolean get() = tape?.level(cpu.cycles) ?: false
        override fun onKeyboardLineSelected(line: Int) { keyboardLine = line }
        override fun onCassetteMotor(on: Boolean) {
            cassetteMotor = on
            tape?.let { it.lastCyclesHint = cpu.cycles; it.setMotor(on, cpu.cycles) }
        }
    }
    val ppi = Ppi8255(psg, ppiInputs)

    /** Microseconds already emulated by the video subsystem. */
    private var videoUs = 0L

    /** Microseconds already emulated by the sound chip. */
    private var audioUs = 0L

    /** Total number of frames run since reset. */
    var frameCount = 0L
        private set

    init {
        psg.portAInput = { keyboard.readLine(keyboardLine) }
        reset()
    }

    /** Hard reset: everything returns to its power-on state; RAM is cleared. */
    fun reset() {
        memory.reset()
        memory.fillPowerOnPattern()
        cpu.reset()
        crtc.reset()
        gateArray.reset()
        psg.reset()
        ppi.reset()
        fdc.reset()
        keyTyper.clear()
        keyQueue.clear()
        videoUs = 0
        audioUs = 0
        frameCount = 0
    }

    // ---- Frame execution ---------------------------------------------------

    /**
     * Runs exactly one frame worth of CPU time (19 968 µs) and returns the
     * most recently completed video frame.
     */
    fun runFrame(): VideoFrame {
        keyTyper.onFrame()
        keyQueue.onFrame()
        val end = cpu.cycles + CpcTiming.TSTATES_PER_FRAME
        val cpu = this.cpu
        val hook = instructionHook
        if (hook == null) {
            while (cpu.cycles < end) {
                syncVideo()
                cpu.step()
            }
        } else {
            while (cpu.cycles < end) {
                syncVideo()
                hook.invoke(this)
                cpu.step()
            }
        }
        syncVideo()
        syncAudio()
        flushAudio()
        frameCount++
        tape?.lastCyclesHint = cpu.cycles
        return gateArray.takeFrame()
    }

    private fun syncVideo() {
        val target = cpu.cycles ushr 2
        val ga = gateArray
        while (videoUs < target) {
            ga.tick()
            videoUs++
        }
    }

    private fun syncAudio() {
        val target = cpu.cycles ushr 2
        val delta = target - audioUs
        if (delta > 0) {
            psg.advance(delta.toInt())
            audioUs = target
        }
    }

    private fun flushAudio() {
        psg.drain { samples, frames -> audioSink.write(samples, frames) }
    }

    // ---- Z80 bus -------------------------------------------------------------

    override fun readMem(address: Int): Int = memory.read(address)

    override fun writeMem(address: Int, value: Int) = memory.write(address, value)

    override fun interruptAcknowledge(): Int {
        gateArray.acknowledgeInterrupt()
        return 0xFF
    }

    /**
     * I/O decoding. The CPC decodes ports with single address bits, so one
     * OUT can hit several devices at once:
     *  - bit 15 = 0 : Gate Array (&7Fxx)
     *  - bit 14 = 0 : CRTC (&BCxx-&BFxx), function in bits 9-8
     *  - bit 13 = 0 : upper ROM select (&DFxx)
     *  - bit 12 = 0 : printer (&EFxx)
     *  - bit 11 = 0 : PPI (&F4xx-&F7xx), port in bits 9-8
     *  - bit 10 = 0 : expansion; FDC when bit 7 = 0 (&FA7E motor, &FB7E/&FB7F controller)
     */
    override fun readIo(port: Int): Int {
        syncVideo()
        var value = 0xFF
        if (port and 0x4000 == 0) {
            when ((port ushr 8) and 3) {
                2 -> value = value and crtc.readStatus()
                3 -> value = value and crtc.readRegister()
                else -> Unit
            }
        }
        if (port and 0x0800 == 0) {
            value = value and ppi.read((port ushr 8) and 3)
        }
        if (port and 0x0480 == 0x0000 && port and 0x0100 != 0) {
            syncFdc()
            value = value and if (port and 1 == 0) fdc.readStatus() else fdc.readData()
        }
        return value
    }

    override fun writeIo(port: Int, value: Int) {
        syncVideo()
        if (port and 0x8000 == 0) {
            // Register writes to the AY go through the PPI, but the Gate Array
            // colour/mode writes have an audible side effect only via the CPU
            // timing, so no audio sync is needed here.
            gateArray.write(value)
        }
        if (port and 0x4000 == 0) {
            when ((port ushr 8) and 3) {
                0 -> crtc.selectRegister(value)
                1 -> crtc.writeRegister(value)
                else -> Unit
            }
        }
        if (port and 0x2000 == 0) {
            memory.selectUpperRom(value)
        }
        if (port and 0x0800 == 0) {
            syncAudio()
            ppi.write((port ushr 8) and 3, value)
        }
        if (port and 0x0480 == 0) {
            if (port and 0x0100 == 0) {
                fdc.setMotor(value and 1 != 0)
            } else if (port and 1 != 0) {
                syncFdc()
                fdc.writeData(value)
            }
        }
    }

    private fun syncFdc() {
        fdc.catchUp(cpu.cycles ushr 2)
    }

    /** Current machine time in microseconds. */
    val timeUs: Long get() = cpu.cycles ushr 2

    /** Re-aligns the internal clocks after a state restore. */
    fun restoreClocks(cpuCycles: Long, frames: Long) {
        cpu.cycles = cpuCycles
        videoUs = cpuCycles ushr 2
        audioUs = videoUs
        frameCount = frames
        psg.discardSamples()
        keyTyper.clear()
        keyQueue.clear()
    }
}
