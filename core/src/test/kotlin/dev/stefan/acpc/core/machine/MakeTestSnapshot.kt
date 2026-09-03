package dev.stefan.acpc.core.machine

import dev.stefan.acpc.core.TestRoms
import dev.stefan.acpc.core.api.CpcEmulator
import dev.stefan.acpc.core.api.NullAudioSink
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/** Tool: writes ~/.acpc/test-snapshot.sna, a 6128 in BASIC running a small program, for manual tests. */
@Tag("slow")
class MakeTestSnapshot {
    @Test
    fun write() {
        assumeTrue(TestRoms.realAvailable(CpcModel.CPC6128))
        val emu = CpcEmulator.createMachine(CpcModel.CPC6128, TestRoms.real(CpcModel.CPC6128), NullAudioSink())
        repeat(130) { emu.runFrame() }
        emu.typeText("mode 0:border 6:ink 1,24\n10 print\"snapshot ok \";:goto 10\nrun\n")
        repeat(400) { emu.runFrame() }
        File(System.getProperty("user.home") + "/.acpc/test-snapshot.sna").writeBytes(emu.saveSnapshot())
    }
}
