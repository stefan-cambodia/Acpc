package dev.stefan.acpc.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import dev.stefan.acpc.core.machine.CpcModel
import dev.stefan.acpc.core.machine.CrtcType

/** Typed access to the user preferences (backed by the default SharedPreferences). */
class AppSettings(context: Context) {
    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    var model: CpcModel
        get() = runCatching { CpcModel.valueOf(prefs.getString(KEY_MODEL, CpcModel.CPC6128.name)!!) }.getOrDefault(CpcModel.CPC6128)
        set(v) = prefs.edit().putString(KEY_MODEL, v.name).apply()

    val crtcType: CrtcType
        get() = runCatching { CrtcType.valueOf(prefs.getString(KEY_CRTC, CrtcType.TYPE0_HD6845S.name)!!) }.getOrDefault(CrtcType.TYPE0_HD6845S)

    /** Emulation speed in percent (100 = real CPC). */
    val speedPercent: Int get() = prefs.getString(KEY_SPEED, "100")?.toIntOrNull() ?: 100

    val volume: Float get() = prefs.getInt(KEY_VOLUME, 80) / 100f
    var muted: Boolean
        get() = prefs.getBoolean(KEY_MUTED, false)
        set(v) = prefs.edit().putBoolean(KEY_MUTED, v).apply()

    /** Audio buffer size in CPC frames (20 ms each). */
    val audioLatencyFrames: Int get() = prefs.getString(KEY_AUDIO_LATENCY, "4")?.toIntOrNull()?.coerceIn(2, 12) ?: 4

    val scalingMode: ScalingMode
        get() = runCatching { ScalingMode.valueOf(prefs.getString(KEY_SCALING, ScalingMode.FIT.name)!!) }.getOrDefault(ScalingMode.FIT)
    val scanlines: Boolean get() = prefs.getBoolean(KEY_SCANLINES, false)
    val smoothing: Boolean get() = prefs.getBoolean(KEY_SMOOTHING, false)

    val autoStart: Boolean get() = prefs.getBoolean(KEY_AUTOSTART, true)
    val fastDisc: Boolean get() = prefs.getBoolean(KEY_FAST_DISC, false)

    val showJoystick: Boolean get() = prefs.getBoolean(KEY_SHOW_JOYSTICK, true)
    val overlayOpacity: Float get() = prefs.getInt(KEY_OVERLAY_OPACITY, 55) / 100f
    val overlayScale: Float get() = prefs.getInt(KEY_OVERLAY_SCALE, 100) / 100f
    val hapticFeedback: Boolean get() = prefs.getBoolean(KEY_HAPTIC, true)
    var overlayProfile: String
        get() = prefs.getString(KEY_OVERLAY_PROFILE, "platform") ?: "platform"
        set(v) = prefs.edit().putString(KEY_OVERLAY_PROFILE, v).apply()

    val developerOverlay: Boolean get() = prefs.getBoolean(KEY_DEV_OVERLAY, false)

    val keyboardHeightPercent: Int get() = prefs.getInt(KEY_KEYBOARD_HEIGHT, 40)

    /** Orientation of the emulator screen. */
    var screenOrientation: Orientation
        get() = runCatching { Orientation.valueOf(prefs.getString(KEY_ORIENTATION, Orientation.AUTO.name)!!) }.getOrDefault(Orientation.AUTO)
        set(v) = prefs.edit().putString(KEY_ORIENTATION, v.name).apply()

    /** Last remote server or file URL entered in the "remote server" dialog. */
    var lastRemoteUrl: String
        get() = prefs.getString(KEY_LAST_REMOTE_URL, DEFAULT_REMOTE_URL) ?: DEFAULT_REMOTE_URL
        set(v) = prefs.edit().putString(KEY_LAST_REMOTE_URL, v).apply()

    var physicalKeyMap: String?
        get() = prefs.getString(KEY_PHYSICAL_KEYMAP, null)
        set(v) = prefs.edit().putString(KEY_PHYSICAL_KEYMAP, v).apply()

    var gamepadMap: String?
        get() = prefs.getString(KEY_GAMEPAD_MAP, null)
        set(v) = prefs.edit().putString(KEY_GAMEPAD_MAP, v).apply()

    var overlayLayouts: String?
        get() = prefs.getString(KEY_OVERLAY_LAYOUTS, null)
        set(v) = prefs.edit().putString(KEY_OVERLAY_LAYOUTS, v).apply()

    enum class ScalingMode { FIT, INTEGER, STRETCH, PIXEL_PERFECT }

    /**
     * AUTO follows the sensor even when the system auto-rotate is locked (like
     * most games); SYSTEM honours the lock; the other two are fixed.
     */
    enum class Orientation { AUTO, SYSTEM, LANDSCAPE, PORTRAIT }

    companion object {
        const val KEY_MODEL = "cpc_model"
        const val KEY_CRTC = "crtc_type"
        const val KEY_SPEED = "speed_percent"
        const val KEY_VOLUME = "volume"
        const val KEY_MUTED = "muted"
        const val KEY_AUDIO_LATENCY = "audio_latency"
        const val KEY_SCALING = "scaling_mode"
        const val KEY_SCANLINES = "scanlines"
        const val KEY_SMOOTHING = "smoothing"
        const val KEY_AUTOSTART = "auto_start"
        const val KEY_FAST_DISC = "fast_disc"
        const val KEY_SHOW_JOYSTICK = "show_joystick"
        const val KEY_OVERLAY_OPACITY = "overlay_opacity"
        const val KEY_OVERLAY_SCALE = "overlay_scale"
        const val KEY_OVERLAY_PROFILE = "overlay_profile"
        const val KEY_HAPTIC = "haptic"
        const val KEY_DEV_OVERLAY = "developer_overlay"
        const val KEY_KEYBOARD_HEIGHT = "keyboard_height"
        const val KEY_PHYSICAL_KEYMAP = "physical_keymap"
        const val KEY_GAMEPAD_MAP = "gamepad_map"
        const val KEY_OVERLAY_LAYOUTS = "overlay_layouts"
        const val KEY_ORIENTATION = "screen_orientation"
        const val KEY_LAST_REMOTE_URL = "last_remote_url"

        /** A large public collection of CPC discs (zipped DSK files), browsable in the app. */
        const val DEFAULT_REMOTE_URL = "https://archive.org/download/AmstradCPCGameCollectionByGhostware"
    }
}
