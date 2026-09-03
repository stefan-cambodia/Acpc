package dev.stefan.acpc.core.keyboard

/**
 * Injects text into the keyboard matrix over time, one key per few frames,
 * so that the firmware keyboard scanner (which runs at 50 Hz) sees every
 * press and release. Used by auto-start (`RUN"DISC`).
 */
class KeyTyper(private val matrix: KeyboardMatrix) {
    private class Stroke(val key: CpcKey, val shift: Boolean, val control: Boolean)

    private val queue = ArrayDeque<Stroke>()
    private var current: Stroke? = null
    private var framesLeft = 0
    private var releasing = false

    val isIdle: Boolean get() = queue.isEmpty() && current == null

    fun type(text: String) {
        for (ch in text) {
            val stroke = strokeFor(ch) ?: continue
            queue.addLast(stroke)
        }
    }

    fun typeKey(key: CpcKey, shift: Boolean = false, control: Boolean = false) {
        queue.addLast(Stroke(key, shift, control))
    }

    fun clear() {
        current?.let { release(it) }
        current = null
        queue.clear()
    }

    /** Call once per frame. */
    fun onFrame() {
        val cur = current
        if (cur != null) {
            framesLeft--
            if (framesLeft > 0) return
            if (!releasing) {
                release(cur)
                releasing = true
                framesLeft = RELEASE_FRAMES
                return
            }
            current = null
        }
        val next = queue.removeFirstOrNull() ?: return
        current = next
        releasing = false
        framesLeft = PRESS_FRAMES
        if (next.shift) matrix.press(CpcKey.SHIFT)
        if (next.control) matrix.press(CpcKey.CONTROL)
        matrix.press(next.key)
    }

    private fun release(s: Stroke) {
        matrix.release(s.key)
        if (s.shift) matrix.release(CpcKey.SHIFT)
        if (s.control) matrix.release(CpcKey.CONTROL)
    }

    private fun strokeFor(ch: Char): Stroke? {
        if (ch in 'a'..'z') return Stroke(CpcKey.valueOf(ch.uppercaseChar().toString()), false, false)
        if (ch in 'A'..'Z') return Stroke(CpcKey.valueOf(ch.toString()), true, false)
        if (ch in '0'..'9') return Stroke(CpcKey.valueOf("DIGIT_$ch"), false, false)
        return when (ch) {
            '\n', '\r' -> Stroke(CpcKey.RETURN, false, false)
            ' ' -> Stroke(CpcKey.SPACE, false, false)
            '"' -> Stroke(CpcKey.DIGIT_2, true, false)
            '!' -> Stroke(CpcKey.DIGIT_1, true, false)
            '#' -> Stroke(CpcKey.DIGIT_3, true, false)
            '$' -> Stroke(CpcKey.DIGIT_4, true, false)
            '%' -> Stroke(CpcKey.DIGIT_5, true, false)
            '&' -> Stroke(CpcKey.DIGIT_6, true, false)
            '\'' -> Stroke(CpcKey.DIGIT_7, true, false)
            '(' -> Stroke(CpcKey.DIGIT_8, true, false)
            ')' -> Stroke(CpcKey.DIGIT_9, true, false)
            '_' -> Stroke(CpcKey.DIGIT_0, true, false)
            '-' -> Stroke(CpcKey.MINUS, false, false)
            '=' -> Stroke(CpcKey.MINUS, true, false)
            '^' -> Stroke(CpcKey.CARET, false, false)
            '£' -> Stroke(CpcKey.CARET, true, false)
            '@' -> Stroke(CpcKey.AT, false, false)
            '|' -> Stroke(CpcKey.AT, true, false)
            '[' -> Stroke(CpcKey.OPEN_BRACKET, false, false)
            '{' -> Stroke(CpcKey.OPEN_BRACKET, true, false)
            ']' -> Stroke(CpcKey.CLOSE_BRACKET, false, false)
            '}' -> Stroke(CpcKey.CLOSE_BRACKET, true, false)
            ';' -> Stroke(CpcKey.SEMICOLON, false, false)
            '+' -> Stroke(CpcKey.SEMICOLON, true, false)
            ':' -> Stroke(CpcKey.COLON, false, false)
            '*' -> Stroke(CpcKey.COLON, true, false)
            ',' -> Stroke(CpcKey.COMMA, false, false)
            '<' -> Stroke(CpcKey.COMMA, true, false)
            '.' -> Stroke(CpcKey.PERIOD, false, false)
            '>' -> Stroke(CpcKey.PERIOD, true, false)
            '/' -> Stroke(CpcKey.SLASH, false, false)
            '?' -> Stroke(CpcKey.SLASH, true, false)
            '\\' -> Stroke(CpcKey.BACKSLASH, false, false)
            '`' -> Stroke(CpcKey.BACKSLASH, true, false)
            else -> null
        }
    }

    companion object {
        const val PRESS_FRAMES = 3
        const val RELEASE_FRAMES = 2
    }
}
