package com.remotegamestreamer.client

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.remotegamestreamer.client.update.Updater

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = getSharedPreferences("connection", Context.MODE_PRIVATE)
        setContent {
            OmniDeckTheme {
                ConnectionScreen(
                    initialAddress = preferences.getString("address", "").orEmpty(),
                    onConnect = { address, pin ->
                        if (address.isBlank() || pin.length != 6) {
                            Toast.makeText(this, "Enter the PC address and 6-digit PIN", Toast.LENGTH_SHORT).show()
                        } else {
                            preferences.edit().putString("address", address).apply()
                            startActivity(
                                Intent(this, StreamActivity::class.java)
                                    .putExtra(StreamActivity.EXTRA_HOST, address)
                                    .putExtra(StreamActivity.EXTRA_PIN, pin.toInt()),
                            )
                        }
                    },
                    onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                )
            }
        }

        val isDebugBuild = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (savedInstanceState == null && !isDebugBuild) Updater(this).check(quiet = true)
    }
}
