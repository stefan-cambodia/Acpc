package dev.stefan.acpc.core

import dev.stefan.acpc.core.keyboard.CpcKey
import dev.stefan.acpc.core.keyboard.KeyboardMatrix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyboardMatrixTest {
    @Test
    fun `idle matrix reads 0xFF on every line`() {
        val m = KeyboardMatrix()
        for (line in 0..15) assertEquals(0xFF, m.readLine(line))
    }

    @Test
    fun `pressed key pulls its bit low and release restores it`() {
        val m = KeyboardMatrix()
        m.press(CpcKey.SPACE) // line 5 bit 7
        assertEquals(0x7F, m.readLine(5))
        m.press(CpcKey.DIGIT_8) // line 5 bit 0
        assertEquals(0x7E, m.readLine(5))
        assertTrue(m.isPressed(CpcKey.SPACE))
        m.release(CpcKey.SPACE)
        assertEquals(0xFE, m.readLine(5))
        m.releaseAll()
        assertEquals(0xFF, m.readLine(5))
    }

    @Test
    fun `every key has a unique matrix position`() {
        val positions = CpcKey.entries.map { it.line * 8 + it.bit }
        assertEquals(positions.size, positions.toSet().size)
        assertEquals(80, positions.size)
    }
}
