package dev.stefan.acpc.emulator

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import dev.stefan.acpc.R
import dev.stefan.acpc.core.api.EmulatorException
import dev.stefan.acpc.core.machine.CpcModel
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
        val model = entry?.modelOverride?.let { runCatching { CpcModel.valueOf(it) }.getOrNull() } ?: settings.model
        if (!romStore.canBoot(model)) {
            Toast.makeText(activity, activity.getString(R.string.roms_missing_toast, model.displayName), Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(activity, RomSetupActivity::class.java))
            return
        }
        if (resumeExisting && EmulatorHolder.session != null) {
            activity.startActivity(Intent(activity, EmulatorActivity::class.java))
            return
        }
        val roms = romStore.load(model) ?: return
        val session = try {
            EmulatorHolder.start(activity, model, settings.crtcType, roms, settings)
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.error_start_emulator, e.message ?: ""), Toast.LENGTH_LONG).show()
            return
        }
        if (entry != null) {
            try {
                val bytes = library.diskFile(entry).readBytes()
                val autoStart = entry.autoStart ?: settings.autoStart
                session.insertDisk(bytes, entry.title, autoStart, resetFirst = true)
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
