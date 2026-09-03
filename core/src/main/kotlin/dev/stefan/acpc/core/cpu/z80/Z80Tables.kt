package dev.stefan.acpc.core.cpu.z80

/** Flag bit masks and precomputed flag lookup tables for the Z80. */
object Z80Flags {
    const val C = 0x01
    const val N = 0x02
    const val PV = 0x04
    const val X = 0x08   // undocumented, copy of result bit 3
    const val H = 0x10
    const val Y = 0x20   // undocumented, copy of result bit 5
    const val Z = 0x40
    const val S = 0x80

    const val XY = X or Y
    const val SZ = S or Z
    const val SZXY = S or Z or X or Y

    /** S, Z, X and Y flags for an 8-bit result. */
    @JvmField
    val SZ53: IntArray = IntArray(256)

    /** S, Z, X, Y and parity (PV) flags for an 8-bit result. */
    @JvmField
    val SZ53P: IntArray = IntArray(256)

    /** Parity flag only (PV set when the number of 1 bits is even). */
    @JvmField
    val PARITY: IntArray = IntArray(256)

    init {
        for (i in 0 until 256) {
            val parityEven = Integer.bitCount(i) and 1 == 0
            val p = if (parityEven) PV else 0
            var v = i and XY
            if (i == 0) v = v or Z
            if (i and 0x80 != 0) v = v or S
            SZ53[i] = v
            SZ53P[i] = v or p
            PARITY[i] = p
        }
    }
}
