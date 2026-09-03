package dev.stefan.acpc.core.machine

/**
 * Decodes the text shown on a firmware mode 1 screen by matching the pixel
 * patterns in video RAM against the character set of the lower ROM.
 * Only pen 1 on pen 0 text is recognised, which is what the firmware prints.
 */
object ScreenReader {
    fun readText(machine: CpcMachine, rows: Int = 25, cols: Int = 40): List<String> {
        val font = machine.memory.lowerRom
        // Glyph bitmap -> character, for the 256 firmware characters.
        val glyphs = HashMap<Long, Char>()
        for (ch in 32 until 256) {
            var key = 0L
            for (l in 0 until 8) key = (key shl 8) or (font[0x3800 + ch * 8 + l].toLong() and 0xFF)
            glyphs.putIfAbsent(key, ch.toChar())
        }
        val crtc = machine.crtc
        val base = ((crtc.regs[12] shl 8) or crtc.regs[13]) and 0x3FFF
        val lines = ArrayList<String>()
        for (r in 0 until rows) {
            val sb = StringBuilder()
            for (c in 0 until cols) {
                val ma = base + r * 40 + c
                var key = 0L
                for (l in 0 until 8) {
                    val addr = ((ma and 0x3000) shl 2) or (l shl 11) or ((ma and 0x3FF) shl 1)
                    val b0 = machine.memory.videoRead(addr)
                    val b1 = machine.memory.videoRead(addr + 1)
                    // Pen 1 pixels are encoded in the high nibble of each mode 1 byte.
                    val row = ((b0 ushr 4) shl 4) or (b1 ushr 4)
                    key = (key shl 8) or row.toLong()
                }
                sb.append(glyphs[key] ?: (if (key == 0L) ' ' else '?'))
            }
            lines.add(sb.toString().trimEnd())
        }
        return lines
    }
}
