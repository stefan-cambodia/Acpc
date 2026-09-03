package dev.stefan.acpc.core.api

/**
 * A rendered CPC frame.
 *
 * The Gate Array outputs 16 pixels per microsecond; a full 64 µs line is
 * therefore 1024 pixels wide and a PAL frame contains 312 lines. The emulator
 * renders the whole raster into [pixels] (ARGB, row-major, [stride] ints per
 * line) and exposes the visible window ([visibleX], [visibleY],
 * [visibleWidth], [visibleHeight]) that a front-end should display: the
 * border area a real monitor shows, without the sync regions.
 *
 * Each CPC scan line is stored once; the front-end should double it vertically
 * (or use the [pixelAspect] hint) to obtain the correct aspect ratio.
 */
class VideoFrame(
    val stride: Int,
    val lines: Int,
    var visibleX: Int,
    var visibleY: Int,
    var visibleWidth: Int,
    var visibleHeight: Int,
) {
    val pixels: IntArray = IntArray(stride * lines)

    /** Height/width ratio of a rendered pixel (2.0 : each line should be drawn twice). */
    val pixelAspect: Float = 2.0f

    /** Monotonic frame counter, incremented by the machine at each VSYNC. */
    var frameNumber: Long = 0

    fun copyFrom(other: VideoFrame) {
        System.arraycopy(other.pixels, 0, pixels, 0, pixels.size)
        visibleX = other.visibleX
        visibleY = other.visibleY
        visibleWidth = other.visibleWidth
        visibleHeight = other.visibleHeight
        frameNumber = other.frameNumber
    }
}
