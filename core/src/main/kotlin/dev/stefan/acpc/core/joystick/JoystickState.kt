package dev.stefan.acpc.core.joystick

import dev.stefan.acpc.core.keyboard.CpcKey
import dev.stefan.acpc.core.keyboard.KeyboardMatrix

/**
 * Digital joystick input, mapped onto the keyboard matrix exactly as the
 * hardware does (joystick 0 on line 9, joystick 1 on line 6).
 */
class JoystickState(private val matrix: KeyboardMatrix, val port: Int) {
    private val up = if (port == 0) CpcKey.JOY0_UP else CpcKey.JOY1_UP
    private val down = if (port == 0) CpcKey.JOY0_DOWN else CpcKey.JOY1_DOWN
    private val left = if (port == 0) CpcKey.JOY0_LEFT else CpcKey.JOY1_LEFT
    private val right = if (port == 0) CpcKey.JOY0_RIGHT else CpcKey.JOY1_RIGHT
    private val fire1 = if (port == 0) CpcKey.JOY0_FIRE1 else CpcKey.JOY1_FIRE1
    private val fire2 = if (port == 0) CpcKey.JOY0_FIRE2 else CpcKey.JOY1_FIRE2

    /**
     * Sets the whole joystick state at once. Directions are exclusive on a
     * real joystick along each axis; the caller is responsible for that.
     */
    fun set(up: Boolean, down: Boolean, left: Boolean, right: Boolean, fire1: Boolean, fire2: Boolean) {
        matrix.set(this.up.line, this.up.bit, up)
        matrix.set(this.down.line, this.down.bit, down)
        matrix.set(this.left.line, this.left.bit, left)
        matrix.set(this.right.line, this.right.bit, right)
        matrix.set(this.fire1.line, this.fire1.bit, fire1)
        matrix.set(this.fire2.line, this.fire2.bit, fire2)
    }

    fun setButton(button: JoystickButton, pressed: Boolean) {
        val key = when (button) {
            JoystickButton.UP -> up
            JoystickButton.DOWN -> down
            JoystickButton.LEFT -> left
            JoystickButton.RIGHT -> right
            JoystickButton.FIRE1 -> fire1
            JoystickButton.FIRE2 -> fire2
        }
        matrix.set(key.line, key.bit, pressed)
    }

    fun release() = set(false, false, false, false, false, false)
}

enum class JoystickButton { UP, DOWN, LEFT, RIGHT, FIRE1, FIRE2 }
