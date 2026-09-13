package dev.stefan.acpc.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DiscSetTest {

    @Test
    fun `numbered discs list the other discs of the set`() {
        assertEquals(listOf("Out Run (1988)(US Gold)(Disk 2 of 2)"), DiscSet.siblings("Out Run (1988)(US Gold)(Disk 1 of 2)"))
        assertEquals(
            listOf("Megablasters (1994)(Odiesoft)(Disk 1 of 4)", "Megablasters (1994)(Odiesoft)(Disk 3 of 4)", "Megablasters (1994)(Odiesoft)(Disk 4 of 4)"),
            DiscSet.siblings("Megablasters (1994)(Odiesoft)(Disk 2 of 4)"),
        )
        assertEquals(listOf("Game (Disc 1 of 2)[cr NPS]"), DiscSet.siblings("Game (Disc 2 of 2)[cr NPS]"))
    }

    @Test
    fun `sides and sides of numbered discs`() {
        assertEquals(listOf("Rastan (1988)(Imagine Software)(Side B)"), DiscSet.siblings("Rastan (1988)(Imagine Software)(Side A)"))
        assertEquals(
            listOf("X (Disk 1 of 2 Side B)", "X (Disk 2 of 2 Side A)", "X (Disk 2 of 2 Side B)"),
            DiscSet.siblings("X (Disk 1 of 2 Side A)"),
        )
        assertEquals(emptyList<String>(), DiscSet.siblings("Bomb Jack (1986)(Elite Systems)"))
    }

    @Test
    fun `the sibling url sits next to the downloaded file`() {
        val url = "https://archive.org/download/Coll/Out%20Run%20%281988%29%28US%20Gold%29%28Disk%201%20of%202%29.zip"
        assertEquals(
            "https://archive.org/download/Coll/Out%20Run%20%281988%29%28US%20Gold%29%28Disk%202%20of%202%29.zip",
            DiscSet.siblingUrl(url, "Out Run (1988)(US Gold)(Disk 2 of 2)"),
        )
        assertNull(DiscSet.siblingUrl("https://example.com/games/other.zip", "Out Run (1988)(US Gold)(Disk 2 of 2)"))
    }
}
