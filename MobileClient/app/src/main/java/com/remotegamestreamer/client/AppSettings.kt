package com.remotegamestreamer.client

import android.content.Context
import com.remotegamestreamer.client.input.HudLayout
import com.remotegamestreamer.client.net.Protocol

/** Everything the Settings screen and layout editor save. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

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

    var stickLocked: Boolean
        get() = prefs.getBoolean("stickLocked", true)
        set(v) = prefs.edit().putBoolean("stickLocked", v).apply()

    var cameraSensitivityPercent: Int
        get() = prefs.getInt("cameraSensitivity", 100)
        set(v) = prefs.edit().putInt("cameraSensitivity", v).apply()

    var layout: HudLayout
        get() = HudLayout.parse(prefs.getString("layout", null))
        set(v) = prefs.edit().putString("layout", v.serialize()).apply()

    fun streamRequest() = Protocol.StreamRequest(maxHeight, fps, bitrateMbps, hevc, sound)

    companion object {
        val RESOLUTIONS = listOf(720, 900, 1080, 1440, 2160)
        val FRAME_RATES = listOf(30, 60, 90, 120)
        const val MIN_BITRATE = 5
        const val MAX_BITRATE = 100
    }
}
