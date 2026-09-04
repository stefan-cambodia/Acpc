package dev.stefan.acpc.core.snapshot

import dev.stefan.acpc.core.api.InvalidStateException
import dev.stefan.acpc.core.machine.CpcMachine
import dev.stefan.acpc.core.machine.CpcModel

/**
 * The CPC ".sna" snapshot format (versions 1, 2 and 3) used by most
 * emulators and game collections: a 256-byte header with the Z80, Gate
 * Array, CRTC, PPI, PSG and memory configuration, followed by the RAM dump
 * (64 or 128 KB), or for version 3 by chunks (`MEM0`..`MEM8`, optionally
 * run-length compressed).
 *
 * Loading applies the header to a freshly reset machine; the disc, the
 * clocks and the audio pipeline are left alone.
 */
object SnaFormat {
    private const val HEADER_SIZE = 0x100
    private val MAGIC = "MV - SNA".toByteArray(Charsets.US_ASCII)

    class Info(val version: Int, val model: CpcModel?, val ramKb: Int)

    fun isSna(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_SIZE && (0 until MAGIC.size).all { bytes[it] == MAGIC[it] }

    /** Reads the header without touching a machine. Throws [InvalidStateException] on a malformed file. */
    fun info(bytes: ByteArray): Info {
        if (!isSna(bytes)) throw InvalidStateException("Not a CPC snapshot (missing MV - SNA signature)")
        val version = bytes[0x10].toInt() and 0xFF
        if (version !in 1..3) throw InvalidStateException("Unsupported snapshot version $version")
        var ramKb = u16(bytes, 0x6B)
        if (ramKb == 0 && version == 3) {
            // Version 3 with the memory in chunks: size follows from the chunks.
            ramKb = chunks(bytes).filter { it.first.startsWith("MEM") }.size * 64
        }
        val model = if (version >= 2) {
            when (bytes[0x6D].toInt() and 0xFF) {
                0 -> CpcModel.CPC464
                1 -> CpcModel.CPC664
                2 -> CpcModel.CPC6128
                else -> if (ramKb > 64) CpcModel.CPC6128 else null
            }
        } else if (ramKb > 64) CpcModel.CPC6128 else null
        return Info(version, model, ramKb)
    }

    /** Applies the snapshot to [machine], which must have enough RAM for it. */
    fun load(bytes: ByteArray, machine: CpcMachine) {
        val info = info(bytes)
        val ram = memory(bytes, info)
        if (ram.size > machine.memory.ram.size) {
            throw InvalidStateException("This snapshot needs ${ram.size / 1024} KB of RAM, the ${machine.model.displayName} has ${machine.memory.ram.size / 1024} KB")
        }
        machine.reset()
        System.arraycopy(ram, 0, machine.memory.ram, 0, ram.size)

        val b = { i: Int -> bytes[i].toInt() and 0xFF }
        // Gate Array: pens, then the mode / ROM configuration.
        for (pen in 0 until 17) {
            machine.gateArray.write(pen and 0x0F or (if (pen == 16) 0x10 else 0))
            machine.gateArray.write(0x40 or (b(0x2F + pen) and 0x1F))
        }
        machine.gateArray.write(b(0x2E) and 0x1F)
        val rmr = b(0x40) and 0x1F
        machine.gateArray.write(0x80 or rmr)
        machine.gateArray.forceMode(rmr)
        // RAM configuration and ROM select.
        machine.memory.restoreConfig(b(0x55), rmr and 0x04 == 0, rmr and 0x08 == 0, b(0x41) and 0x3F)
        // CRTC registers.
        for (r in 0 until 18) { machine.crtc.selectRegister(r); machine.crtc.writeRegister(b(0x43 + r)) }
        machine.crtc.selectRegister(b(0x42) and 0x1F)
        // PSG registers.
        for (r in 0 until 16) { machine.psg.selectRegister(r); machine.psg.writeRegister(b(0x5B + r)) }
        machine.psg.selectRegister(b(0x5A) and 0x0F)
        // PPI ports.
        machine.ppi.importState(intArrayOf(b(0x56), b(0x57), b(0x58), b(0x59)))
        if (info.version >= 3) machine.fdc.setMotor(b(0x9C) != 0)
        // Z80 last, so nothing above disturbs it.
        machine.cpu.importState(
            intArrayOf(
                b(0x12), b(0x11), b(0x14), b(0x13), b(0x16), b(0x15), b(0x18), b(0x17),   // AF BC DE HL
                b(0x27), b(0x26), b(0x29), b(0x28), b(0x2B), b(0x2A), b(0x2D), b(0x2C),   // AF' BC' DE' HL'
                u16(bytes, 0x1D), u16(bytes, 0x1F), u16(bytes, 0x21), u16(bytes, 0x23),   // IX IY SP PC
                b(0x1A), b(0x19), 0,                                                    // I R MEMPTR
                if (b(0x1B) != 0) 1 else 0, if (b(0x1C) != 0) 1 else 0, b(0x25) and 3,   // IFF1 IFF2 IM
                0, 0, if (info.version >= 3 && b(0xB5) != 0) 1 else 0,                     // halted, EI pending, INT line
            ),
        )
    }

    /** Writes a version 2 snapshot of [machine] (64 KB dump for a 64 KB machine, 128 KB otherwise). */
    fun save(machine: CpcMachine): ByteArray {
        val ramKb = machine.memory.ram.size / 1024
        val h = ByteArray(HEADER_SIZE)
        MAGIC.copyInto(h)
        h[0x10] = 2
        val cpu = machine.cpu.exportState()
        // exportState layout: a f b c d e h l a' f' b' c' d' e' h' l' ix iy sp pc i r memptr iff1 iff2 im ...
        h[0x11] = cpu[1].toByte(); h[0x12] = cpu[0].toByte(); h[0x13] = cpu[3].toByte(); h[0x14] = cpu[2].toByte()
        h[0x15] = cpu[5].toByte(); h[0x16] = cpu[4].toByte(); h[0x17] = cpu[7].toByte(); h[0x18] = cpu[6].toByte()
        h[0x19] = cpu[21].toByte(); h[0x1A] = cpu[20].toByte(); h[0x1B] = cpu[23].toByte(); h[0x1C] = cpu[24].toByte()
        put16(h, 0x1D, cpu[16]); put16(h, 0x1F, cpu[17]); put16(h, 0x21, cpu[18]); put16(h, 0x23, cpu[19])
        h[0x25] = cpu[25].toByte()
        h[0x26] = cpu[9].toByte(); h[0x27] = cpu[8].toByte(); h[0x28] = cpu[11].toByte(); h[0x29] = cpu[10].toByte()
        h[0x2A] = cpu[13].toByte(); h[0x2B] = cpu[12].toByte(); h[0x2C] = cpu[15].toByte(); h[0x2D] = cpu[14].toByte()
        val ga = machine.gateArray
        h[0x2E] = ga.selectedPen.toByte()
        for (p in 0 until 17) h[0x2F + p] = ga.pens[p].toByte()
        val mem = machine.memory
        h[0x40] = (0x80 or ga.mode or (if (mem.lowerRomEnabled) 0 else 0x04) or (if (mem.upperRomEnabled) 0 else 0x08)).toByte()
        h[0x41] = (0xC0 or (mem.ramConfig and 0x3F)).toByte()
        h[0x42] = machine.crtc.selectedRegister.toByte()
        for (r in 0 until 18) h[0x43 + r] = machine.crtc.regs[r].toByte()
        h[0x55] = mem.upperRomNumber.toByte()
        val ppi = machine.ppi.exportState()
        h[0x56] = ppi[0].toByte(); h[0x57] = ppi[1].toByte(); h[0x58] = ppi[2].toByte(); h[0x59] = ppi[3].toByte()
        h[0x5A] = machine.psg.selectedRegister.toByte()
        for (r in 0 until 16) h[0x5B + r] = machine.psg.regs[r].toByte()
        put16(h, 0x6B, ramKb)
        h[0x6D] = when (machine.model) {
            CpcModel.CPC464 -> 0; CpcModel.CPC664 -> 1; CpcModel.CPC6128 -> 2
            CpcModel.CPC6128PLUS -> 4; CpcModel.GX4000 -> 6
        }.toByte()
        return h + machine.memory.ram.copyOf()
    }

    private fun put16(b: ByteArray, i: Int, v: Int) { b[i] = v.toByte(); b[i + 1] = (v shr 8).toByte() }

    // ---- Memory ------------------------------------------------------------

    private fun memory(bytes: ByteArray, info: Info): ByteArray {
        val declaredKb = u16(bytes, 0x6B)
        if (declaredKb > 0) {
            val size = declaredKb * 1024
            if (bytes.size < HEADER_SIZE + size) throw InvalidStateException("Snapshot truncated: ${bytes.size - HEADER_SIZE} bytes of RAM for $declaredKb KB")
            return bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + size)
        }
        val banks = chunks(bytes).filter { it.first.startsWith("MEM") }.sortedBy { it.first }
        if (banks.isEmpty()) throw InvalidStateException("Snapshot has no memory")
        val out = ByteArray(banks.size * 65536)
        for ((i, c) in banks.withIndex()) {
            val data = if (c.second.size == 65536) c.second else decompress(c.second, 65536)
            System.arraycopy(data, 0, out, i * 65536, minOf(65536, data.size))
        }
        return out
    }

    /** Version 3 chunks after the header (and after the RAM dump when one is declared). */
    private fun chunks(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val result = ArrayList<Pair<String, ByteArray>>()
        var offset = HEADER_SIZE + u16(bytes, 0x6B) * 1024
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = u32(bytes, offset + 4)
            offset += 8
            if (length < 0 || offset + length > bytes.size) break
            result += name to bytes.copyOfRange(offset, offset + length)
            offset += length
        }
        return result
    }

    /** MEM chunk compression: 0xE5 n b = n copies of b (n = 0 means a literal 0xE5). */
    private fun decompress(data: ByteArray, size: Int): ByteArray {
        val out = ByteArray(size)
        var i = 0
        var o = 0
        while (i < data.size && o < size) {
            val v = data[i].toInt() and 0xFF
            if (v == 0xE5) {
                if (i + 1 >= data.size) break
                val n = data[i + 1].toInt() and 0xFF
                if (n == 0) { out[o++] = 0xE5.toByte(); i += 2; continue }
                if (i + 2 >= data.size) break
                val b = data[i + 2]
                repeat(n) { if (o < size) out[o++] = b }
                i += 3
            } else {
                out[o++] = v.toByte()
                i++
            }
        }
        return out
    }

    private fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int): Int = u16(b, i) or (u16(b, i + 2) shl 16)
}
