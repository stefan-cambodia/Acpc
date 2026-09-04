package dev.stefan.acpc.emulator

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import dev.stefan.acpc.R
import dev.stefan.acpc.core.api.EmulatorException
import dev.stefan.acpc.core.cartridge.Cartridge
import dev.stefan.acpc.core.machine.CpcModel
import dev.stefan.acpc.core.snapshot.SnaFormat
import dev.stefan.acpc.settings.AppSettings
import dev.stefan.acpc.storage.GameEntry
import dev.stefan.acpc.storage.GameLibrary
import dev.stefan.acpc.storage.RomStore
import dev.stefan.acpc.ui.emulator.EmulatorActivity
import dev.stefan.acpc.ui.roms.RomSetupActivity

/** Creates the emulator session for a game (or a bare CPC) and opens the emulator screen. */
object GameLauncher {

    fun launch(activity: Activity, entry: GameEntry?, library: GameLibrary, resumeExisting: Boolean = false) {
        val settings = AppSettings(activity)
        val romStore = RomStore(activity)
        val bytes = entry?.let { runCatching { library.diskFile(it).readBytes() }.getOrNull() }
        if (entry != null && bytes == null) {
            Toast.makeText(activity, activity.getString(R.string.error_cannot_read_file), Toast.LENGTH_LONG).show()
            return
        }
        // A cartridge is the machine's firmware: a game boots a GX4000, a system cartridge a 6128 Plus.
        val gameCartridge = if (entry != null && entry.isCartridge && bytes != null) {
            runCatching { Cartridge.parse(bytes, entry.title) }.getOrElse {
                Toast.makeText(activity, activity.getString(R.string.error_invalid_cpr, it.message ?: ""), Toast.LENGTH_LONG).show()
                return
            }
        } else null
        // A snapshot dictates its machine unless the user overrode it; a 128 KB dump needs a 6128.
        val snapshotModel = if (entry != null && entry.isSnapshot && bytes != null) {
            runCatching { SnaFormat.info(bytes) }.getOrNull()?.let { info -> info.model ?: if (info.ramKb > 64) CpcModel.CPC6128 else null }
        } else null
        var model = entry?.modelOverride?.let { runCatching { CpcModel.valueOf(it) }.getOrNull() }
            ?: snapshotModel
            ?: gameCartridge?.let { if (it.isSystemCartridge) CpcModel.CPC6128PLUS else CpcModel.GX4000 }
            ?: settings.model
        if (gameCartridge != null && !model.isPlus) model = CpcModel.GX4000
        if (gameCartridge == null && model == CpcModel.GX4000) {
            // A console cannot load discs or tapes: use the computer of the same family.
            model = if (romStore.canBoot(CpcModel.CPC6128PLUS)) CpcModel.CPC6128PLUS else CpcModel.CPC6128
        }
        if (gameCartridge == null && !romStore.canBoot(model)) {
            Toast.makeText(activity, activity.getString(R.string.roms_missing_toast, model.displayName), Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(activity, RomSetupActivity::class.java))
            return
        }
        if (resumeExisting && EmulatorHolder.session != null) {
            activity.startActivity(Intent(activity, EmulatorActivity::class.java))
            return
        }
        val cartridge = gameCartridge ?: if (model.isPlus) romStore.loadSystemCartridge() else null
        val roms = if (model.isPlus) null else (romStore.load(model) ?: return)
        val session = try {
            EmulatorHolder.start(activity, model, settings.crtcType, roms, settings, cartridge)
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.error_start_emulator, e.message ?: ""), Toast.LENGTH_LONG).show()
            return
        }
        if (entry != null && bytes != null) {
            try {
                if (entry.isSnapshot) {
                    session.loadSnapshot(bytes)
                } else if (entry.isTape) {
                    session.insertTape(bytes, entry.title, entry.autoStart ?: settings.autoStart, resetFirst = true)
                } else if (entry.isCartridge) {
                    Unit // the cartridge booted with the machine
                } else {
                    val autoStart = entry.autoStart ?: settings.autoStart
                    session.insertDisk(bytes, entry.title, autoStart, resetFirst = true)
                }
                session.currentEntry = entry
                entry.lastPlayed = System.currentTimeMillis()
                entry.playCount++
                library.update(entry)
            } catch (e: EmulatorException) {
                EmulatorHolder.stop()
                Toast.makeText(activity, activity.getString(R.string.error_cannot_read_dsk), Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {
                EmulatorHolder.stop()
                Toast.makeText(activity, activity.getString(R.string.error_cannot_read_dsk), Toast.LENGTH_LONG).show()
                return
            }
        }
        activity.startActivity(Intent(activity, EmulatorActivity::class.java))
    }
}
