package dev.stefan.acpc.core.gatearray

/** The 32 hardware colour numbers of the Gate Array (27 distinct colours). */
object CpcPalette {
    /** ARGB colour for each hardware colour number 0-31. */
    @JvmField
    val ARGB: IntArray = intArrayOf(
        0xFF808080.toInt(), 0xFF808080.toInt(), 0xFF00FF80.toInt(), 0xFFFFFF80.toInt(),
        0xFF000080.toInt(), 0xFFFF0080.toInt(), 0xFF008080.toInt(), 0xFFFF8080.toInt(),
        0xFFFF0080.toInt(), 0xFFFFFF80.toInt(), 0xFFFFFF00.toInt(), 0xFFFFFFFF.toInt(),
        0xFFFF0000.toInt(), 0xFFFF00FF.toInt(), 0xFFFF8000.toInt(), 0xFFFF80FF.toInt(),
        0xFF000080.toInt(), 0xFF00FF80.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(),
        0xFF000000.toInt(), 0xFF0000FF.toInt(), 0xFF008000.toInt(), 0xFF0080FF.toInt(),
        0xFF800080.toInt(), 0xFF80FF80.toInt(), 0xFF80FF00.toInt(), 0xFF80FFFF.toInt(),
        0xFF800000.toInt(), 0xFF8000FF.toInt(), 0xFF808000.toInt(), 0xFF8080FF.toInt(),
    )

    /** Firmware INK number (0-26) to hardware colour number. */
    @JvmField
    val INK_TO_HARDWARE: IntArray = intArrayOf(
        20, 4, 21, 28, 24, 29, 12, 5, 13, 22, 6, 23, 30, 0, 31, 14, 7, 15, 18, 2, 19, 26, 25, 27, 10, 3, 11,
    )

    const val BLACK: Int = 0xFF000000.toInt()
}
