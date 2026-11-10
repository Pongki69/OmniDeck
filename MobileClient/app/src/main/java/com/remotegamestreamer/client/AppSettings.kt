package com.remotegamestreamer.client

import android.content.Context
import com.remotegamestreamer.client.input.HudLayout
import com.remotegamestreamer.client.net.Protocol

/** Everything the Settings screen and layout editor save. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // There used to be one camera setting for both directions. Up/down starts from it.
        if (!prefs.contains("cameraSensitivityY")) prefs.edit().putInt("cameraSensitivityY", cameraSensitivityPercent).apply()
    }

    var maxHeight: Int
        get() = prefs.getInt("maxHeight", 1080)
        set(v) = prefs.edit().putInt("maxHeight", v).apply()

    var fps: Int
        get() = prefs.getInt("fps", 60)
        set(v) = prefs.edit().putInt("fps", v).apply()

    var bitrateMbps: Int
        get() = prefs.getInt("bitrate", 30)
        set(v) = prefs.edit().putInt("bitrate", v).apply()

    var hevc: Boolean
        get() = prefs.getBoolean("hevc", false)
        set(v) = prefs.edit().putBoolean("hevc", v).apply()

    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    var opacityPercent: Int
        get() = prefs.getInt("opacity", 50)
        set(v) = prefs.edit().putInt("opacity", v).apply()

    var displayScalePercent: Int
        get() = prefs.getInt("displayScale", 100)
        set(v) = prefs.edit().putInt("displayScale", v.coerceIn(50, 100)).apply()

    var stickLocked: Boolean
        get() = prefs.getBoolean("stickLocked", true)
        set(v) = prefs.edit().putBoolean("stickLocked", v).apply()

    var cameraSensitivityPercent: Int
        get() = prefs.getInt("cameraSensitivity", 100)
        set(v) = prefs.edit().putInt("cameraSensitivity", v).apply()

    var cameraSensitivityYPercent: Int
        get() = prefs.getInt("cameraSensitivityY", 100)
        set(v) = prefs.edit().putInt("cameraSensitivityY", v).apply()

    var layout: HudLayout
        get() = HudLayout.parse(prefs.getString("layout", null))
        set(v) = prefs.edit().putString("layout", v.serialize()).apply()

    fun streamRequest() = Protocol.StreamRequest(maxHeight, fps, bitrateMbps, hevc, sound)

    fun hostKey(address: String): String? = prefs.getString(HOST_KEY_PREFIX + address, null)

    fun rememberHostKey(address: String, key: String) = prefs.edit().putString(HOST_KEY_PREFIX + address, key).apply()

    fun forgetHostKeys() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(HOST_KEY_PREFIX) }.forEach(editor::remove)
        editor.apply()
    }

    companion object {
        private const val HOST_KEY_PREFIX = "hostKey_"
        const val MIN_CAMERA_SENSITIVITY = 25
        const val MAX_CAMERA_SENSITIVITY = 400
        val RESOLUTIONS = listOf(720, 900, 1080, 1440, 2160)
        val FRAME_RATES = listOf(30, 60, 90, 120)
        const val MIN_BITRATE = 5
        const val MAX_BITRATE = 100
    }
}
