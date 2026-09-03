package dev.stefan.acpc.core.machine

import dev.stefan.acpc.core.disk.AmsdosCatalog
import dev.stefan.acpc.core.disk.DskFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/** Prints the AMSDOS catalogue and auto-start choice of every test disc (diagnostic helper). */
@Tag("slow")
class PrintCatalogsTest {
    @Test
    fun printCatalogs() {
        val dir = File(System.getProperty("acpc.testDiskDir") ?: (System.getProperty("user.home") + "/.acpc/testdisks"))
        val disks = dir.listFiles { f -> f.name.lowercase().endsWith(".dsk") }?.sortedBy { it.name } ?: emptyList()
        assumeTrue(disks.isNotEmpty())
        for (d in disks) {
            val image = DskFormat.read(d.readBytes(), d.name)
            val files = AmsdosCatalog.list(image)
            println("${d.name}: format=${AmsdosCatalog.detectFormat(image)} autostart=${AmsdosCatalog.autoStartCommand(image)?.trim()}")
            println("   " + files.joinToString(" "))
        }
    }
}
