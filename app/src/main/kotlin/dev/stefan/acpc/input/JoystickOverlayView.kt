package dev.stefan.acpc.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import dev.stefan.acpc.core.keyboard.CpcKey
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Transparent overlay with a virtual joystick (8 directions), FIRE buttons
 * and optional extra key buttons. Elements can be dragged in edit mode.
 */
class JoystickOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onJoystick(up: Boolean, down: Boolean, left: Boolean, right: Boolean)
        fun onFire(button: Int, pressed: Boolean)
        fun onExtraKey(key: CpcKey, pressed: Boolean)
        fun onLayoutChanged(layout: OverlayLayout)
    }

    var listener: Listener? = null
    var layout: OverlayLayout = OverlayLayout()
        set(v) { field = v; invalidate() }
    var opacity: Float = 0.55f
        set(v) { field = v; invalidate() }
    var scale: Float = 1f
        set(v) { field = v; invalidate() }
    var haptic: Boolean = true
    var editMode: Boolean = false
        set(v) { field = v; releaseAll(); invalidate() }
    var joystickVisible: Boolean = true
        set(v) { field = v; invalidate() }

    private var joystickPointer = -1
    private var stickDx = 0f
    private var stickDy = 0f
    private var dirUp = false; private var dirDown = false; private var dirLeft = false; private var dirRight = false
    private val firePointers = HashMap<Int, Int>()      // pointer -> button index (1 or 2)
    private val extraPointers = HashMap<Int, Int>()     // pointer -> extra key index
    private var dragTarget: String? = null
    private var dragPointer = -1

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 255, 255); style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val stickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 0) }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255) }
    private val buttonPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 0) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f }

    private fun unit(): Float = min(width, height).toFloat()
    private fun joyRadius() = unit() * layout.joystickSize * scale / 2f
    private fun buttonRadius() = unit() * layout.buttonSize * scale / 2f

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (opacity * 255).toInt())
        if (joystickVisible) {
            val cx = layout.joystickX * width
            val cy = layout.joystickY * height
            val r = joyRadius()
            canvas.drawCircle(cx, cy, r, basePaint)
            canvas.drawCircle(cx, cy, r, ringPaint)
            val sr = r * 0.4f
            canvas.drawCircle(cx + stickDx * (r - sr), cy + stickDy * (r - sr), sr, stickPaint)
            if (editMode) canvas.drawCircle(cx, cy, r + 8f, editPaint)
            drawButton(canvas, layout.fire1X * width, layout.fire1Y * height, buttonRadius(), "FIRE 1", firePointers.containsValue(1))
            drawButton(canvas, layout.fire2X * width, layout.fire2Y * height, buttonRadius() * 0.8f, "FIRE 2", firePointers.containsValue(2))
        }
        for ((i, e) in layout.extraKeys.withIndex()) {
            drawButton(canvas, e.x * width, e.y * height, buttonRadius() * 0.7f, e.label, extraPointers.containsValue(i))
        }
        canvas.restore()
    }

    private fun drawButton(canvas: Canvas, cx: Float, cy: Float, r: Float, label: String, pressed: Boolean) {
        canvas.drawCircle(cx, cy, r, if (pressed) buttonPressedPaint else buttonPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)
        if (editMode) canvas.drawCircle(cx, cy, r + 8f, editPaint)
        textPaint.textSize = r * 0.45f
        textPaint.color = if (pressed) Color.BLACK else Color.WHITE
        canvas.drawText(label, cx, cy + textPaint.textSize * 0.35f, textPaint)
    }

    private fun hit(x: Float, y: Float, cx: Float, cy: Float, r: Float): Boolean = hypot(x - cx, y - cy) <= r * 1.25f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                if (editMode) return startDrag(id, x, y)
                return handleDown(id, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    if (editMode) {
                        if (id == dragPointer) moveDrag(x, y)
                    } else if (id == joystickPointer) {
                        updateStick(x, y)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (editMode) {
                    if (id == dragPointer) { dragPointer = -1; dragTarget = null; listener?.onLayoutChanged(layout) }
                    return true
                }
                handleUp(id)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAll()
                return true
            }
        }
        return false
    }

    private fun handleDown(id: Int, x: Float, y: Float): Boolean {
        val w = width; val h = height
        for ((i, e) in layout.extraKeys.withIndex()) {
            if (hit(x, y, e.x * w, e.y * h, buttonRadius() * 0.7f)) {
                extraPointers[id] = i
                CpcKey.fromName(e.key)?.let { listener?.onExtraKey(it, true) }
                feedback()
                invalidate()
                return true
            }
        }
        if (!joystickVisible) return false
        if (hit(x, y, layout.fire1X * w, layout.fire1Y * h, buttonRadius())) {
            firePointers[id] = 1
            listener?.onFire(1, true)
            feedback()
            invalidate()
            return true
        }
        if (hit(x, y, layout.fire2X * w, layout.fire2Y * h, buttonRadius() * 0.8f)) {
            firePointers[id] = 2
            listener?.onFire(2, true)
            feedback()
            invalidate()
            return true
        }
        // Anywhere on the left half of the screen acts as the joystick when it's not already held.
        val cx = layout.joystickX * w
        val cy = layout.joystickY * h
        if (joystickPointer < 0 && (hit(x, y, cx, cy, joyRadius() * 1.6f) || x < w * 0.45f)) {
            joystickPointer = id
            updateStick(x, y)
            return true
        }
        return false
    }

    private fun handleUp(id: Int) {
        if (id == joystickPointer) {
            joystickPointer = -1
            stickDx = 0f; stickDy = 0f
            setDirections(false, false, false, false)
            invalidate()
        }
        firePointers.remove(id)?.let { listener?.onFire(it, false); invalidate() }
        extraPointers.remove(id)?.let { i -> layout.extraKeys.getOrNull(i)?.let { e -> CpcKey.fromName(e.key)?.let { listener?.onExtraKey(it, false) } }; invalidate() }
    }

    private fun updateStick(x: Float, y: Float) {
        val cx = layout.joystickX * width
        val cy = layout.joystickY * height
        val r = joyRadius()
        var dx = (x - cx) / r
        var dy = (y - cy) / r
        val d = hypot(dx, dy)
        if (d > 1f) { dx /= d; dy /= d }
        stickDx = dx; stickDy = dy
        val dead = 0.25f
        if (d < dead) {
            setDirections(false, false, false, false)
        } else {
            // 8-way with 45° sectors; diagonals need both axes above ~38% (tan 22.5°).
            val angle = atan2(-dy, dx) // 0 = right, positive = up
            val sector = ((Math.toDegrees(angle.toDouble()) + 360 + 22.5) % 360 / 45).toInt()
            val up = sector in 1..3
            val down = sector in 5..7
            val right = sector == 0 || sector == 1 || sector == 7
            val left = sector in 3..5
            setDirections(up, down, left, right)
        }
        invalidate()
    }

    private fun setDirections(up: Boolean, down: Boolean, left: Boolean, right: Boolean) {
        if (up == dirUp && down == dirDown && left == dirLeft && right == dirRight) return
        if (haptic && (up || down || left || right)) feedback()
        dirUp = up; dirDown = down; dirLeft = left; dirRight = right
        listener?.onJoystick(up, down, left, right)
    }

    private fun feedback() {
        if (haptic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun releaseAll() {
        joystickPointer = -1
        stickDx = 0f; stickDy = 0f
        setDirections(false, false, false, false)
        for (b in firePointers.values) listener?.onFire(b, false)
        firePointers.clear()
        for (i in extraPointers.values) layout.extraKeys.getOrNull(i)?.let { e -> CpcKey.fromName(e.key)?.let { listener?.onExtraKey(it, false) } }
        extraPointers.clear()
        invalidate()
    }

    // ---- Edit mode ---------------------------------------------------------

    private fun startDrag(id: Int, x: Float, y: Float): Boolean {
        val w = width; val h = height
        val extra = layout.extraKeys.indexOfFirst { hit(x, y, it.x * w, it.y * h, buttonRadius()) }
        dragTarget = when {
            hit(x, y, layout.fire1X * w, layout.fire1Y * h, buttonRadius()) -> "fire1"
            hit(x, y, layout.fire2X * w, layout.fire2Y * h, buttonRadius()) -> "fire2"
            extra >= 0 -> "extra$extra"
            hit(x, y, layout.joystickX * w, layout.joystickY * h, joyRadius()) -> "joystick"
            else -> null
        } ?: return false
        dragPointer = id
        return true
    }

    private fun moveDrag(x: Float, y: Float) {
        val fx = (x / width).coerceIn(0.05f, 0.95f)
        val fy = (y / height).coerceIn(0.05f, 0.95f)
        when (val t = dragTarget) {
            "joystick" -> { layout.joystickX = fx; layout.joystickY = fy }
            "fire1" -> { layout.fire1X = fx; layout.fire1Y = fy }
            "fire2" -> { layout.fire2X = fx; layout.fire2Y = fy }
            null -> Unit
            else -> if (t.startsWith("extra")) {
                val i = t.substring(5).toInt()
                layout.extraKeys.getOrNull(i)?.let { it.x = fx; it.y = fy }
            }
        }
        invalidate()
    }

    /** Sine/cos helpers kept for potential analog visualisation. */
    @Suppress("unused")
    private fun polar(angle: Float, r: Float): Pair<Float, Float> = (cos(angle) * r) to (sin(angle) * r)

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 0.001f
}
