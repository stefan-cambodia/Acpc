package dev.stefan.acpc.core

import dev.stefan.acpc.core.keyboard.CpcKey
import dev.stefan.acpc.core.keyboard.KeyQueue
import dev.stefan.acpc.core.keyboard.KeyboardMatrix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyQueueTest {
    private fun KeyboardMatrix.isDown(key: CpcKey): Boolean = readLine(key.line) and (1 shl key.bit) == 0

    @Test
    fun `three fast presses of the same key are seen as three presses`() {
        val matrix = KeyboardMatrix()
        val q = KeyQueue(matrix)
        repeat(3) { q.push(CpcKey.DIGIT_1, true); q.push(CpcKey.DIGIT_1, false) }
        var presses = 0
        var wasDown = false
        repeat(40) {
            q.onFrame()
            val down = matrix.isDown(CpcKey.DIGIT_1)
            if (down && !wasDown) presses++
            wasDown = down
        }
        assertEquals(3, presses)
        assertTrue(!matrix.isDown(CpcKey.DIGIT_1))
    }

    @Test
    fun `press lasts at least three frames and release at least two`() {
        val matrix = KeyboardMatrix()
        val q = KeyQueue(matrix)
        q.push(CpcKey.A, true); q.push(CpcKey.A, false); q.push(CpcKey.A, true)
        val states = (1..8).map { q.onFrame(); matrix.isDown(CpcKey.A) }
        assertEquals(listOf(true, true, true, false, false, true, true, true), states)
    }

    @Test
    fun `modifier ordering is preserved`() {
        val matrix = KeyboardMatrix()
        val q = KeyQueue(matrix)
        q.push(CpcKey.SHIFT, true); q.push(CpcKey.A, true); q.push(CpcKey.A, false); q.push(CpcKey.SHIFT, false)
        q.onFrame()
        assertTrue(matrix.isDown(CpcKey.SHIFT) && matrix.isDown(CpcKey.A))
        repeat(2) { q.onFrame() }
        assertTrue(matrix.isDown(CpcKey.SHIFT) && matrix.isDown(CpcKey.A))
        q.onFrame()
        // Fourth frame: A has been held three frames, so A is released and then SHIFT (held as long) right after it.
        assertTrue(!matrix.isDown(CpcKey.A) && !matrix.isDown(CpcKey.SHIFT))
    }

    @Test
    fun `clear releases held keys and drops pending events`() {
        val matrix = KeyboardMatrix()
        val q = KeyQueue(matrix)
        q.push(CpcKey.Z, true); q.push(CpcKey.Z, false); q.push(CpcKey.X, true)
        q.onFrame()
        assertTrue(matrix.isDown(CpcKey.Z))
        q.clear()
        assertTrue(!matrix.isDown(CpcKey.Z))
        repeat(5) { q.onFrame() }
        assertTrue(!matrix.isDown(CpcKey.X))
    }
}
