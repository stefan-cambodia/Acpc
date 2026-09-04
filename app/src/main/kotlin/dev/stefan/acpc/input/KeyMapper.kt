package dev.stefan.acpc.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import dev.stefan.acpc.core.joystick.JoystickButton
import dev.stefan.acpc.core.keyboard.CpcKey
import org.json.JSONObject

/**
 * Maps Android key codes (physical / Bluetooth / USB keyboards) to CPC matrix
 * keys. The default map follows the physical position of the keys; users can
 * override any entry (stored as JSON: keycode → CpcKey name).
 */
class KeyMapper(overrides: String? = null) {
    private val map = HashMap<Int, CpcKey>(DEFAULT)

    init {
        overrides?.let { applyOverrides(it) }
    }

    fun applyOverrides(json: String) {
        runCatching {
            val o = JSONObject(json)
            for (key in o.keys()) {
                val code = key.toIntOrNull() ?: continue
                val name = o.getString(key)
                if (name == "-") map.remove(code) else CpcKey.fromName(name)?.let { map[code] = it }
            }
        }
    }

    fun toJson(): String {
        val o = JSONObject()
        for ((code, key) in map) if (DEFAULT[code] != key) o.put(code.toString(), key.name)
        for (code in DEFAULT.keys) if (!map.containsKey(code)) o.put(code.toString(), "-")
        return o.toString()
    }

    fun set(keyCode: Int, key: CpcKey?) {
        if (key == null) map.remove(keyCode) else map[keyCode] = key
    }

    operator fun get(keyCode: Int): CpcKey? = map[keyCode]

    fun entries(): Map<Int, CpcKey> = map

    /** A resolved key press: the CPC key, plus whether SHIFT must be held to produce the wanted character. */
    class Stroke(val key: CpcKey, val shift: Boolean, val charMapped: Boolean = false)

    /**
     * Resolves a key event. Letters, digits and control keys use the
     * positional map; printable symbols are mapped by character so that a
     * PC keyboard types the symbol printed on the key (the CPC layout differs
     * from PC layouts: '+' is SHIFT+';', '=' is SHIFT+'-', and so on).
     * Explicit user overrides always win.
     */
    fun resolve(event: KeyEvent): Stroke? {
        val code = event.keyCode
        val custom = map[code]
        if (custom != null && DEFAULT[code] != custom) return Stroke(custom, false)
        val ch = event.unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK
        if (ch != 0 && !Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch)) {
            CHAR_MAP[ch.toChar()]?.let { return Stroke(it.key, it.shift, charMapped = true) }
        }
        return custom?.let { Stroke(it, false) }
    }

    companion object {
        /** Symbol → CPC key (+ SHIFT) for character based mapping. */
        val CHAR_MAP: Map<Char, Stroke> = mapOf(
            '!' to Stroke(CpcKey.DIGIT_1, true), '"' to Stroke(CpcKey.DIGIT_2, true), '#' to Stroke(CpcKey.DIGIT_3, true),
            '$' to Stroke(CpcKey.DIGIT_4, true), '%' to Stroke(CpcKey.DIGIT_5, true), '&' to Stroke(CpcKey.DIGIT_6, true),
            '\'' to Stroke(CpcKey.DIGIT_7, true), '(' to Stroke(CpcKey.DIGIT_8, true), ')' to Stroke(CpcKey.DIGIT_9, true),
            '_' to Stroke(CpcKey.DIGIT_0, true), '-' to Stroke(CpcKey.MINUS, false), '=' to Stroke(CpcKey.MINUS, true),
            '^' to Stroke(CpcKey.CARET, false), '£' to Stroke(CpcKey.CARET, true), '@' to Stroke(CpcKey.AT, false),
            '|' to Stroke(CpcKey.AT, true), '[' to Stroke(CpcKey.OPEN_BRACKET, false), '{' to Stroke(CpcKey.OPEN_BRACKET, true),
            ']' to Stroke(CpcKey.CLOSE_BRACKET, false), '}' to Stroke(CpcKey.CLOSE_BRACKET, true),
            ';' to Stroke(CpcKey.SEMICOLON, false), '+' to Stroke(CpcKey.SEMICOLON, true),
            ':' to Stroke(CpcKey.COLON, false), '*' to Stroke(CpcKey.COLON, true),
            ',' to Stroke(CpcKey.COMMA, false), '<' to Stroke(CpcKey.COMMA, true),
            '.' to Stroke(CpcKey.PERIOD, false), '>' to Stroke(CpcKey.PERIOD, true),
            '/' to Stroke(CpcKey.SLASH, false), '?' to Stroke(CpcKey.SLASH, true),
            '\\' to Stroke(CpcKey.BACKSLASH, false), '`' to Stroke(CpcKey.BACKSLASH, true),
        )

        val DEFAULT: Map<Int, CpcKey> = buildMap {
            for (c in 'A'..'Z') put(KeyEvent.KEYCODE_A + (c - 'A'), CpcKey.valueOf(c.toString()))
            for (d in 0..9) put(KeyEvent.KEYCODE_0 + d, CpcKey.valueOf("DIGIT_$d"))
            put(KeyEvent.KEYCODE_SPACE, CpcKey.SPACE)
            put(KeyEvent.KEYCODE_ENTER, CpcKey.RETURN)
            put(KeyEvent.KEYCODE_NUMPAD_ENTER, CpcKey.ENTER)
            put(KeyEvent.KEYCODE_ESCAPE, CpcKey.ESC)
            put(KeyEvent.KEYCODE_TAB, CpcKey.TAB)
            put(KeyEvent.KEYCODE_SHIFT_LEFT, CpcKey.SHIFT)
            put(KeyEvent.KEYCODE_SHIFT_RIGHT, CpcKey.SHIFT)
            put(KeyEvent.KEYCODE_CTRL_LEFT, CpcKey.CONTROL)
            put(KeyEvent.KEYCODE_CTRL_RIGHT, CpcKey.CONTROL)
            put(KeyEvent.KEYCODE_CAPS_LOCK, CpcKey.CAPS_LOCK)
            put(KeyEvent.KEYCODE_DEL, CpcKey.DEL)
            put(KeyEvent.KEYCODE_FORWARD_DEL, CpcKey.CLR)
            put(KeyEvent.KEYCODE_INSERT, CpcKey.COPY)
            put(KeyEvent.KEYCODE_HOME, CpcKey.CLR)
            put(KeyEvent.KEYCODE_DPAD_UP, CpcKey.CURSOR_UP)
            put(KeyEvent.KEYCODE_DPAD_DOWN, CpcKey.CURSOR_DOWN)
            put(KeyEvent.KEYCODE_DPAD_LEFT, CpcKey.CURSOR_LEFT)
            put(KeyEvent.KEYCODE_DPAD_RIGHT, CpcKey.CURSOR_RIGHT)
            put(KeyEvent.KEYCODE_MINUS, CpcKey.MINUS)
            put(KeyEvent.KEYCODE_EQUALS, CpcKey.CARET)
            put(KeyEvent.KEYCODE_LEFT_BRACKET, CpcKey.OPEN_BRACKET)
            put(KeyEvent.KEYCODE_RIGHT_BRACKET, CpcKey.CLOSE_BRACKET)
            put(KeyEvent.KEYCODE_SEMICOLON, CpcKey.SEMICOLON)
            put(KeyEvent.KEYCODE_APOSTROPHE, CpcKey.COLON)
            put(KeyEvent.KEYCODE_COMMA, CpcKey.COMMA)
            put(KeyEvent.KEYCODE_PERIOD, CpcKey.PERIOD)
            put(KeyEvent.KEYCODE_SLASH, CpcKey.SLASH)
            put(KeyEvent.KEYCODE_BACKSLASH, CpcKey.BACKSLASH)
            put(KeyEvent.KEYCODE_GRAVE, CpcKey.AT)
            put(KeyEvent.KEYCODE_AT, CpcKey.AT)
            for (i in 0..9) put(KeyEvent.KEYCODE_NUMPAD_0 + i, CpcKey.valueOf("F$i"))
            put(KeyEvent.KEYCODE_NUMPAD_DOT, CpcKey.F_DOT)
            put(KeyEvent.KEYCODE_F1, CpcKey.F1); put(KeyEvent.KEYCODE_F2, CpcKey.F2); put(KeyEvent.KEYCODE_F3, CpcKey.F3)
            put(KeyEvent.KEYCODE_F4, CpcKey.F4); put(KeyEvent.KEYCODE_F5, CpcKey.F5); put(KeyEvent.KEYCODE_F6, CpcKey.F6)
            put(KeyEvent.KEYCODE_F7, CpcKey.F7); put(KeyEvent.KEYCODE_F8, CpcKey.F8); put(KeyEvent.KEYCODE_F9, CpcKey.F9)
            put(KeyEvent.KEYCODE_F10, CpcKey.F0)
        }
    }
}

/** Gamepad buttons → CPC joystick / keys. */
class GamepadMapper(overrides: String? = null) {
    sealed class Target {
        data class Joy(val button: JoystickButton) : Target()
        data class Key(val key: CpcKey) : Target()
        object Menu : Target()
        object ToggleKeyboard : Target()

        fun serialize(): String = when (this) {
            is Joy -> "JOY:${button.name}"
            is Key -> "KEY:${key.name}"
            Menu -> "MENU"
            ToggleKeyboard -> "KEYBOARD"
        }

        companion object {
            fun parse(s: String): Target? = when {
                s.startsWith("JOY:") -> runCatching { Joy(JoystickButton.valueOf(s.substring(4))) }.getOrNull()
                s.startsWith("KEY:") -> CpcKey.fromName(s.substring(4))?.let { Key(it) }
                s == "MENU" -> Menu
                s == "KEYBOARD" -> ToggleKeyboard
                else -> null
            }
        }
    }

    private val map = HashMap<Int, Target>(DEFAULT)

    init {
        overrides?.let {
            runCatching {
                val o = JSONObject(it)
                for (k in o.keys()) {
                    val code = k.toIntOrNull() ?: continue
                    val t = Target.parse(o.getString(k))
                    if (t == null) map.remove(code) else map[code] = t
                }
            }
        }
    }

    operator fun get(keyCode: Int): Target? = map[keyCode]

    fun set(keyCode: Int, target: Target?) {
        if (target == null) map.remove(keyCode) else map[keyCode] = target
    }

    fun entries(): Map<Int, Target> = map

    fun toJson(): String {
        val o = JSONObject()
        for ((code, t) in map) if (DEFAULT[code] != t) o.put(code.toString(), t.serialize())
        for (code in DEFAULT.keys) if (!map.containsKey(code)) o.put(code.toString(), "-")
        return o.toString()
    }

    companion object {
        val DEFAULT: Map<Int, Target> = mapOf(
            // A = pad button 1 = the CPC's "fire 2" (line 9 bit 4), the main fire of most games.
            KeyEvent.KEYCODE_BUTTON_A to Target.Joy(JoystickButton.FIRE2),
            KeyEvent.KEYCODE_BUTTON_B to Target.Joy(JoystickButton.FIRE1),
            KeyEvent.KEYCODE_BUTTON_X to Target.Joy(JoystickButton.FIRE2),
            KeyEvent.KEYCODE_BUTTON_Y to Target.Key(CpcKey.SPACE),
            KeyEvent.KEYCODE_DPAD_UP to Target.Joy(JoystickButton.UP),
            KeyEvent.KEYCODE_DPAD_DOWN to Target.Joy(JoystickButton.DOWN),
            KeyEvent.KEYCODE_DPAD_LEFT to Target.Joy(JoystickButton.LEFT),
            KeyEvent.KEYCODE_DPAD_RIGHT to Target.Joy(JoystickButton.RIGHT),
            KeyEvent.KEYCODE_BUTTON_START to Target.Key(CpcKey.RETURN),
            KeyEvent.KEYCODE_BUTTON_SELECT to Target.Menu,
            KeyEvent.KEYCODE_BUTTON_MODE to Target.Menu,
            KeyEvent.KEYCODE_BUTTON_L1 to Target.Key(CpcKey.ESC),
            KeyEvent.KEYCODE_BUTTON_R1 to Target.ToggleKeyboard,
            KeyEvent.KEYCODE_BUTTON_L2 to Target.Key(CpcKey.DIGIT_1),
            KeyEvent.KEYCODE_BUTTON_R2 to Target.Key(CpcKey.DIGIT_2),
            KeyEvent.KEYCODE_BUTTON_THUMBL to Target.Key(CpcKey.SPACE),
            KeyEvent.KEYCODE_BUTTON_THUMBR to Target.Key(CpcKey.RETURN),
        )
    }
}
