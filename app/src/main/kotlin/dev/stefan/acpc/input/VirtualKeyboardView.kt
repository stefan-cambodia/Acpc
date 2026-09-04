package dev.stefan.acpc.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import dev.stefan.acpc.core.keyboard.CpcKey

/**
 * Touch keyboard with the CPC 6128 layout. Supports several simultaneous
 * keys (one per pointer), sticky SHIFT / CTRL (tap once, applied to the next
 * key) and a resizable height.
 */
class VirtualKeyboardView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onKeyDown(key: CpcKey)
        fun onKeyUp(key: CpcKey)
    }

    var listener: Listener? = null
    var opacity: Float = 0.85f
        set(v) { field = v; invalidate() }
    var haptic: Boolean = true

    private class Key(val key: CpcKey, val label: String, val weight: Float, val shiftLabel: String? = null)

    private val rows: List<List<Key>> = listOf(
        listOf(
            Key(CpcKey.F0, "f0", 1f), Key(CpcKey.F1, "f1", 1f), Key(CpcKey.F2, "f2", 1f), Key(CpcKey.F3, "f3", 1f),
            Key(CpcKey.F4, "f4", 1f), Key(CpcKey.F5, "f5", 1f), Key(CpcKey.F6, "f6", 1f), Key(CpcKey.F7, "f7", 1f),
            Key(CpcKey.F8, "f8", 1f), Key(CpcKey.F9, "f9", 1f), Key(CpcKey.F_DOT, "f.", 1f),
            Key(CpcKey.CURSOR_UP, "↑", 1f), Key(CpcKey.CURSOR_DOWN, "↓", 1f), Key(CpcKey.CURSOR_LEFT, "←", 1f), Key(CpcKey.CURSOR_RIGHT, "→", 1f),
        ),
        listOf(
            Key(CpcKey.ESC, "ESC", 1.2f), Key(CpcKey.DIGIT_1, "1", 1f, "!"), Key(CpcKey.DIGIT_2, "2", 1f, "\""), Key(CpcKey.DIGIT_3, "3", 1f, "#"),
            Key(CpcKey.DIGIT_4, "4", 1f, "$"), Key(CpcKey.DIGIT_5, "5", 1f, "%"), Key(CpcKey.DIGIT_6, "6", 1f, "&"), Key(CpcKey.DIGIT_7, "7", 1f, "'"),
            Key(CpcKey.DIGIT_8, "8", 1f, "("), Key(CpcKey.DIGIT_9, "9", 1f, ")"), Key(CpcKey.DIGIT_0, "0", 1f, "_"),
            Key(CpcKey.MINUS, "-", 1f, "="), Key(CpcKey.CARET, "^", 1f, "£"), Key(CpcKey.CLR, "CLR", 1.2f), Key(CpcKey.DEL, "DEL", 1.4f),
        ),
        listOf(
            Key(CpcKey.TAB, "TAB", 1.5f), Key(CpcKey.Q, "Q", 1f), Key(CpcKey.W, "W", 1f), Key(CpcKey.E, "E", 1f), Key(CpcKey.R, "R", 1f),
            Key(CpcKey.T, "T", 1f), Key(CpcKey.Y, "Y", 1f), Key(CpcKey.U, "U", 1f), Key(CpcKey.I, "I", 1f), Key(CpcKey.O, "O", 1f),
            Key(CpcKey.P, "P", 1f), Key(CpcKey.AT, "@", 1f, "|"), Key(CpcKey.OPEN_BRACKET, "[", 1f, "{"), Key(CpcKey.RETURN, "RETURN", 2.3f),
        ),
        listOf(
            Key(CpcKey.CAPS_LOCK, "CAPS", 1.8f), Key(CpcKey.A, "A", 1f), Key(CpcKey.S, "S", 1f), Key(CpcKey.D, "D", 1f), Key(CpcKey.F, "F", 1f),
            Key(CpcKey.G, "G", 1f), Key(CpcKey.H, "H", 1f), Key(CpcKey.J, "J", 1f), Key(CpcKey.K, "K", 1f), Key(CpcKey.L, "L", 1f),
            Key(CpcKey.COLON, ":", 1f, "*"), Key(CpcKey.SEMICOLON, ";", 1f, "+"), Key(CpcKey.CLOSE_BRACKET, "]", 1f, "}"), Key(CpcKey.ENTER, "ENTER", 2f),
        ),
        listOf(
            Key(CpcKey.SHIFT, "SHIFT", 2.2f), Key(CpcKey.Z, "Z", 1f), Key(CpcKey.X, "X", 1f), Key(CpcKey.C, "C", 1f), Key(CpcKey.V, "V", 1f),
            Key(CpcKey.B, "B", 1f), Key(CpcKey.N, "N", 1f), Key(CpcKey.M, "M", 1f), Key(CpcKey.COMMA, ",", 1f, "<"), Key(CpcKey.PERIOD, ".", 1f, ">"),
            Key(CpcKey.SLASH, "/", 1f, "?"), Key(CpcKey.BACKSLASH, "\\", 1f, "`"), Key(CpcKey.SHIFT, "SHIFT", 2.6f),
        ),
        listOf(
            Key(CpcKey.CONTROL, "CTRL", 1.8f), Key(CpcKey.COPY, "COPY", 1.5f), Key(CpcKey.SPACE, "SPACE", 8f),
            Key(CpcKey.JOY0_FIRE2, "FIRE", 1.5f), Key(CpcKey.F_DOT, ".", 1f),
        ),
    )

    private val keyRects = ArrayList<Pair<Key, RectF>>()
    private val pointerKeys = HashMap<Int, Key>()
    private val pressed = HashSet<CpcKey>()
    private val sticky = HashSet<CpcKey>()

    private val bgPaint = Paint().apply { color = Color.argb(200, 20, 20, 60) }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 70, 70, 110) }
    private val keyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 255, 255, 0) }
    private val keyStickyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 0, 200, 200) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val textPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER }
    private val shiftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 200, 200, 255); textAlign = Paint.Align.RIGHT }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys(w.toFloat(), h.toFloat())
    }

    private fun layoutKeys(w: Float, h: Float) {
        keyRects.clear()
        val rowHeight = h / rows.size
        val gap = rowHeight * 0.08f
        for ((r, row) in rows.withIndex()) {
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            var x = 0f
            val unit = w / totalWeight
            val top = r * rowHeight
            for (key in row) {
                val kw = unit * key.weight
                keyRects += key to RectF(x + gap, top + gap, x + kw - gap, top + rowHeight - gap)
                x += kw
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (opacity * 255).toInt())
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val rowHeight = height / rows.size.toFloat()
        textPaint.textSize = rowHeight * 0.38f
        textPressedPaint.textSize = rowHeight * 0.38f
        shiftPaint.textSize = rowHeight * 0.26f
        for ((key, rect) in keyRects) {
            val isPressed = pressed.contains(key.key)
            val isSticky = sticky.contains(key.key)
            val paint = when {
                isPressed -> keyPressedPaint
                isSticky -> keyStickyPaint
                else -> keyPaint
            }
            canvas.drawRoundRect(rect, rowHeight * 0.15f, rowHeight * 0.15f, paint)
            val tp = if (isPressed || isSticky) textPressedPaint else textPaint
            val label = key.label
            tp.textSize = if (label.length > 3) rowHeight * 0.26f else rowHeight * 0.38f
            canvas.drawText(label, rect.centerX(), rect.centerY() + tp.textSize * 0.35f, tp)
            key.shiftLabel?.let { canvas.drawText(it, rect.right - rowHeight * 0.12f, rect.top + shiftPaint.textSize * 1.1f, shiftPaint) }
        }
        canvas.restore()
    }

    private fun keyAt(x: Float, y: Float): Key? = keyRects.firstOrNull { it.second.contains(x, y) }?.first

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val key = keyAt(event.getX(idx), event.getY(idx)) ?: return true
                pointerKeys[event.getPointerId(idx)] = key
                press(key)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val current = pointerKeys[id] ?: continue
                    val under = keyAt(event.getX(i), event.getY(i))
                    if (under != null && under !== current) {
                        release(current, fromSlide = true)
                        pointerKeys[id] = under
                        press(under)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                pointerKeys.remove(id)?.let { release(it, fromSlide = false) }
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerKeys.values.toList().forEach { release(it, fromSlide = false) }
                pointerKeys.clear()
            }
        }
        return true
    }

    private fun press(key: Key) {
        val k = key.key
        if (k == CpcKey.SHIFT || k == CpcKey.CONTROL) {
            if (sticky.remove(k)) {
                listener?.onKeyUp(k)
                invalidate()
                return
            }
        }
        if (pressed.add(k)) listener?.onKeyDown(k)
        if (haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    private fun release(key: Key, fromSlide: Boolean) {
        val k = key.key
        if ((k == CpcKey.SHIFT || k == CpcKey.CONTROL) && !fromSlide) {
            // A tap on SHIFT/CTRL keeps it held until the next key is released.
            sticky.add(k)
            pressed.remove(k)
            invalidate()
            return
        }
        if (pressed.remove(k)) listener?.onKeyUp(k)
        // Release sticky modifiers after the key they modified.
        if (sticky.isNotEmpty() && pressed.isEmpty()) {
            for (m in sticky) listener?.onKeyUp(m)
            sticky.clear()
        }
        invalidate()
    }

    fun releaseAll() {
        for (k in pressed) listener?.onKeyUp(k)
        for (k in sticky) listener?.onKeyUp(k)
        pressed.clear()
        sticky.clear()
        pointerKeys.clear()
        invalidate()
    }
}
