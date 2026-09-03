package dev.stefan.acpc.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.stefan.acpc.core.api.VideoFrame
import dev.stefan.acpc.settings.AppSettings

/**
 * Displays CPC frames. [present] is called from the emulation thread: it
 * copies the visible part of the frame into a bitmap and draws it, scaled,
 * on the surface using a hardware canvas.
 */
class CpcSurfaceView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    SurfaceView(context, attrs), SurfaceHolder.Callback {

    @Volatile private var surfaceReady = false
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0

    @Volatile var scalingMode: AppSettings.ScalingMode = AppSettings.ScalingMode.FIT
    @Volatile var scanlines: Boolean = false
    @Volatile var smoothing: Boolean = false

    private var bitmap: Bitmap? = null
    private var bitmapWidth = 0
    private var bitmapHeight = 0
    private val matrix = Matrix()
    private val paint = Paint()
    private val scanlinePaint = Paint().apply { color = Color.argb(70, 0, 0, 0) }
    private var scanlineBitmap: Bitmap? = null
    private val lock = Any()

    /** Screen rectangle where the CPC picture was last drawn (for touch mapping). */
    val displayRect = RectF()

    init {
        holder.addCallback(this)
        holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        surfaceReady = true
        clear()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Must not return while the emulation thread is drawing: the surface
        // is destroyed right after this callback and a hardware canvas locked
        // on a dead surface aborts the process.
        synchronized(lock) {
            surfaceReady = false
        }
    }

    private fun clear() {
        if (!surfaceReady) return
        synchronized(lock) {
            // Always use the same canvas API on a surface: mixing software and
            // hardware canvases reconnects the buffer queue and fails.
            val canvas = lockCanvas() ?: return
            try {
                canvas.drawColor(Color.BLACK)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun lockCanvas(): Canvas? = try {
        holder.lockHardwareCanvas()
    } catch (e: Exception) {
        null
    }

    /** Copies and draws [frame]. Safe to call from any thread. */
    fun present(frame: VideoFrame) {
        if (!surfaceReady || surfaceWidth == 0) return
        synchronized(lock) {
            val w = frame.visibleWidth
            val h = frame.visibleHeight
            var bmp = bitmap
            if (bmp == null || bitmapWidth != w || bitmapHeight != h) {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap = bmp
                bitmapWidth = w
                bitmapHeight = h
            }
            bmp.setPixels(frame.pixels, frame.visibleY * frame.stride + frame.visibleX, frame.stride, 0, 0, w, h)
            if (!surfaceReady) return
            val canvas = lockCanvas() ?: return
            try {
                draw(canvas, bmp, w, h, frame.pixelAspect)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun draw(canvas: Canvas, bmp: Bitmap, w: Int, h: Int, aspect: Float) {
        canvas.drawColor(Color.BLACK)
        val sw = surfaceWidth.toFloat()
        val sh = surfaceHeight.toFloat()
        // Natural size: each CPC line is drawn twice (aspect 2.0), so the picture is w × (h × aspect).
        val naturalW = w.toFloat()
        val naturalH = h * aspect
        var scaleX: Float
        var scaleY: Float
        when (scalingMode) {
            AppSettings.ScalingMode.STRETCH -> {
                scaleX = sw / naturalW
                scaleY = sh / naturalH
            }
            AppSettings.ScalingMode.INTEGER, AppSettings.ScalingMode.PIXEL_PERFECT -> {
                // Integer multiple of the mode 1 pixel (2 framebuffer pixels) horizontally and of a line vertically.
                val fit = minOf(sw / naturalW, sh / naturalH)
                val k = kotlin.math.floor(fit * 2).coerceAtLeast(1f) / 2f
                scaleX = k
                scaleY = k
            }
            AppSettings.ScalingMode.FIT -> {
                val fit = minOf(sw / naturalW, sh / naturalH)
                scaleX = fit
                scaleY = fit
            }
        }
        val drawW = naturalW * scaleX
        val drawH = naturalH * scaleY
        val left = (sw - drawW) / 2f
        val top = (sh - drawH) / 2f
        displayRect.set(left, top, left + drawW, top + drawH)
        matrix.reset()
        matrix.postScale(scaleX, scaleY * aspect)
        matrix.postTranslate(left, top)
        paint.isFilterBitmap = smoothing && scalingMode != AppSettings.ScalingMode.PIXEL_PERFECT
        canvas.drawBitmap(bmp, matrix, paint)
        if (scanlines && scaleY * aspect >= 2f) drawScanlines(canvas, h, top, drawH, left, drawW)
    }

    private fun drawScanlines(canvas: Canvas, lines: Int, top: Float, drawH: Float, left: Float, drawW: Float) {
        val lineHeight = drawH / lines
        val thickness = (lineHeight / 3f).coerceAtLeast(1f)
        var y = top + lineHeight - thickness
        val right = left + drawW
        val r = Rect()
        while (y < top + drawH) {
            r.set(left.toInt(), y.toInt(), right.toInt(), (y + thickness).toInt())
            canvas.drawRect(r, scanlinePaint)
            y += lineHeight
        }
    }
}
