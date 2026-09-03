package dev.stefan.acpc.core.api

import dev.stefan.acpc.core.joystick.JoystickButton
import dev.stefan.acpc.core.keyboard.CpcKey
import dev.stefan.acpc.core.machine.CpcMachine
import dev.stefan.acpc.core.machine.CpcModel
import dev.stefan.acpc.core.machine.CrtcType
import dev.stefan.acpc.core.state.StateReader
import dev.stefan.acpc.core.state.StateWriter

/**
 * Default [Emulator] implementation wrapping a [CpcMachine].
 *
 * All machine access is serialised on the machine object: the emulation
 * thread holds the monitor while [runFrame] runs, and control methods called
 * from other threads (disk, state, reset) wait for the current frame to end.
 * Input methods are lock-free and never block.
 */
class CpcEmulator(
    override val model: CpcModel,
    override val crtcType: CrtcType,
    roms: RomSet,
    audioSink: AudioSink,
) : Emulator {

    val machine = CpcMachine(model, crtcType, roms, audioSink)

    private val lock = Any()

    override fun reset() = synchronized(lock) { machine.reset() }

    override fun runFrame(): VideoFrame = synchronized(lock) { machine.runFrame() }

    // ---- Disk --------------------------------------------------------------

    override fun loadDisk(drive: Int, image: ByteArray, name: String) = synchronized(lock) {
        machine.fdc.insertDisk(drive, image, name)
    }

    override fun ejectDisk(drive: Int) = synchronized(lock) { machine.fdc.ejectDisk(drive) }

    override fun hasDisk(drive: Int): Boolean = synchronized(lock) { machine.fdc.hasDisk(drive) }

    override fun exportDisk(drive: Int): ByteArray? = synchronized(lock) { machine.fdc.exportDisk(drive) }

    // ---- Tape --------------------------------------------------------------

    override fun insertTape(image: ByteArray, name: String) = synchronized(lock) {
        machine.insertTape(dev.stefan.acpc.core.tape.Tape(dev.stefan.acpc.core.tape.CdtFormat.parse(image), name))
    }

    override fun ejectTape() = synchronized(lock) { machine.insertTape(null) }
    override fun hasTape(): Boolean = machine.tape != null
    override fun rewindTape() = synchronized(lock) { machine.tape?.rewind() ?: Unit }

    override fun tapeStatus(): TapeStatus? = machine.tape?.let {
        TapeStatus(it.name, it.position / 4_000_000f, it.lengthCycles / 4_000_000f, it.isMoving, it.atEnd)
    }

    // ---- Input -------------------------------------------------------------

    override fun pressKey(key: CpcKey) = machine.keyboard.press(key)
    override fun releaseKey(key: CpcKey) = machine.keyboard.release(key)
    override fun queueKey(key: CpcKey, pressed: Boolean) = machine.keyQueue.push(key, pressed)
    override fun releaseAllKeys() = synchronized(lock) { machine.keyQueue.clear(); machine.keyboard.releaseAll() }

    override fun setJoystick(port: Int, button: JoystickButton, pressed: Boolean) =
        (if (port == 0) machine.joystick0 else machine.joystick1).setButton(button, pressed)

    override fun setJoystick(port: Int, up: Boolean, down: Boolean, left: Boolean, right: Boolean, fire1: Boolean, fire2: Boolean) =
        (if (port == 0) machine.joystick0 else machine.joystick1).set(up, down, left, right, fire1, fire2)

    override fun typeText(text: String) = synchronized(lock) { machine.keyTyper.type(text) }

    // ---- Audio -------------------------------------------------------------

    override fun setVolume(volume: Float) {
        machine.psg.volume = volume.coerceIn(0f, 1f)
    }

    override fun setMuted(muted: Boolean) {
        machine.psg.muted = muted
    }

    // ---- State -------------------------------------------------------------

    override fun saveState(): ByteArray = synchronized(lock) {
        val w = StateWriter()
        w.string("MODL", model.name)
        w.string("CRTC", crtcType.name)
        w.ints("Z80 ", machine.cpu.exportState())
        w.longs("CLKS", longArrayOf(machine.cpu.cycles, machine.frameCount))
        w.bytes("RAM ", machine.memory.ram)
        w.ints(
            "MEMC",
            intArrayOf(
                machine.memory.upperRomNumber,
                if (machine.memory.lowerRomEnabled) 1 else 0,
                if (machine.memory.upperRomEnabled) 1 else 0,
                machine.memory.ramConfig,
            ),
        )
        w.ints("CRTR", machine.crtc.exportState())
        w.ints("GATE", machine.gateArray.exportState())
        w.ints("PSG ", machine.psg.exportState())
        w.ints("PPI ", machine.ppi.exportState())
        w.ints("KEYS", machine.keyboard.snapshot())
        machine.fdc.exportState(w)
        machine.tape?.let { w.longs("TAPE", longArrayOf(it.position)) }
        w.toByteArray()
    }

    override fun loadState(state: ByteArray) = synchronized(lock) {
        val r = try {
            StateReader(state)
        } catch (e: IllegalArgumentException) {
            throw InvalidStateException(e.message ?: "Invalid state", e)
        }
        try {
            val stateModel = r.string("MODL")
            if (stateModel != model.name) {
                throw InvalidStateException("State was saved on a ${'$'}stateModel, this machine is a ${model.name}")
            }
            machine.cpu.importState(r.ints("Z80 "))
            val clocks = r.longs("CLKS")
            val ram = r.bytes("RAM ")
            if (ram.size != machine.memory.ram.size) throw InvalidStateException("RAM size mismatch")
            System.arraycopy(ram, 0, machine.memory.ram, 0, ram.size)
            val memc = r.ints("MEMC")
            machine.memory.restoreConfig(memc[0], memc[1] != 0, memc[2] != 0, memc[3])
            machine.crtc.importState(r.ints("CRTR"))
            machine.gateArray.importState(r.ints("GATE"))
            machine.psg.importState(r.ints("PSG "))
            machine.ppi.importState(r.ints("PPI "))
            machine.keyboard.restore(r.ints("KEYS"))
            machine.fdc.importState(r)
            machine.restoreClocks(clocks[0], clocks[1])
            if (r.has("TAPE")) machine.tape?.let { t -> t.lastCyclesHint = machine.cpu.cycles; t.seek(r.longs("TAPE")[0]) }
        } catch (e: InvalidStateException) {
            throw e
        } catch (e: Exception) {
            throw InvalidStateException("Corrupted save state: ${e.message}", e)
        }
    }

    override fun loadSnapshot(sna: ByteArray) = synchronized(lock) {
        try {
            dev.stefan.acpc.core.snapshot.SnaFormat.load(sna, machine)
        } catch (e: InvalidStateException) {
            throw e
        } catch (e: Exception) {
            throw InvalidStateException("Corrupted snapshot: ${e.message}", e)
        }
    }

    override fun saveSnapshot(): ByteArray = synchronized(lock) { dev.stefan.acpc.core.snapshot.SnaFormat.save(machine) }

    // ---- Diagnostics -------------------------------------------------------

    override fun debugInfo(): DebugInfo = synchronized(lock) {
        val cpu = machine.cpu
        val mem = machine.memory
        DebugInfo(
            model = model.displayName,
            pc = cpu.pc, sp = cpu.sp, af = cpu.af, bc = cpu.bc, de = cpu.de, hl = cpu.hl,
            ix = cpu.ix, iy = cpu.iy, iff1 = cpu.iff1, im = cpu.im,
            totalCycles = cpu.cycles, frame = machine.frameCount,
            gaMode = machine.gateArray.mode,
            romConfig = "L${if (mem.lowerRomEnabled) 1 else 0} U${if (mem.upperRomEnabled) 1 else 0} #${mem.upperRomNumber}",
            ramConfig = mem.ramConfig,
            crtcRegisters = machine.crtc.regs.copyOf(),
            fdcStatus = machine.fdc.describe(),
            diskNames = listOf(machine.fdc.diskName(0), machine.fdc.diskName(1)),
        )
    }

    override fun peekRam(address: Int): Int = machine.memory.videoRead(address)

    override fun pokeRam(address: Int, value: Int) = synchronized(lock) { machine.memory.write(address and 0xFFFF, value and 0xFF) }

    companion object {
        /** Factory matching the `createMachine(model)` entry point of the core API. */
        fun createMachine(
            model: CpcModel,
            roms: RomSet,
            audioSink: AudioSink = NullAudioSink(),
            crtcType: CrtcType = CrtcType.TYPE0_HD6845S,
        ): CpcEmulator = CpcEmulator(model, crtcType, roms, audioSink)
    }
}
