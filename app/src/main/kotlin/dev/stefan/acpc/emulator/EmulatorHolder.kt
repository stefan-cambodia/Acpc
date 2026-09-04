package dev.stefan.acpc.emulator

import android.content.Context
import dev.stefan.acpc.core.api.RomSet
import dev.stefan.acpc.core.machine.CpcModel
import dev.stefan.acpc.core.machine.CrtcType
import dev.stefan.acpc.settings.AppSettings

/** Process-wide owner of the single running emulator session. */
object EmulatorHolder {
    @Volatile var session: EmulatorSession? = null
        private set

    @Synchronized
    fun start(
        context: Context,
        model: CpcModel,
        crtcType: CrtcType,
        roms: RomSet?,
        settings: AppSettings,
        cartridge: dev.stefan.acpc.core.cartridge.Cartridge? = null,
    ): EmulatorSession {
        session?.stop()
        val s = EmulatorSession(context.applicationContext, model, crtcType, roms, settings, cartridge)
        session = s
        return s
    }

    @Synchronized
    fun stop() {
        session?.stop()
        session = null
    }
}
