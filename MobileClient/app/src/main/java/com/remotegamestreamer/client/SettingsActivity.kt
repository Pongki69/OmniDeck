package com.remotegamestreamer.client

import android.content.Intent
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.remotegamestreamer.client.update.Updater

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = AppSettings(this)
        val hevcAvailable = phoneDecodesHevc()
        if (!hevcAvailable) settings.hevc = false
        val updater = Updater(this)

        setContent {
            OmniDeckTheme {
                SettingsScreen(
                    settings = settings,
                    versionLabel = updater.versionLabel,
                    hevcAvailable = hevcAvailable,
                    onBack = ::finish,
                    onEditLayout = { startActivity(Intent(this, LayoutEditorActivity::class.java)) },
                    onCheckUpdates = { updater.check(quiet = false) },
                    onForgetHosts = {
                        settings.forgetHostKeys()
                        Toast.makeText(this, "The next PC you connect to will be trusted", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    private fun phoneDecodesHevc(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
        }
}
