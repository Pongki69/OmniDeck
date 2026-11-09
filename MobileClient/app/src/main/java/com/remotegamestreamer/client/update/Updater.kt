package com.remotegamestreamer.client.update

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Decides what counts as an update. Contains no Android types, so it can be unit tested. */
object UpdateRules {
    private val VERSION = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")
    private const val DOWNLOAD_PREFIX = "https://github.com/Pongki69/OmniDeck/releases/download/"

    private fun parts(version: String): List<Int>? =
        VERSION.matchEntire(version)?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return null }

    fun isNewer(tag: String, current: String): Boolean {
        val a = parts(tag) ?: return false
        val b = parts(current) ?: return false
        return a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x > y } ?: false
    }

    fun isTrustedDownload(url: String) = url.startsWith(DOWNLOAD_PREFIX)
}

/**
 * Checks GitHub Releases and hands new versions to Android's installer. Android asks the player
 * to confirm, and refuses any APK not signed with the same key as the installed app.
 */
class Updater(private val activity: Activity) {
    private class Release(val tag: String, val apkUrl: String)

    private val currentVersion: String =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()

    val versionLabel: String get() = currentVersion

    /** With [quiet], only speaks up when an update exists. */
    fun check(quiet: Boolean) {
        Thread({
            try {
                val release = fetchLatest()
                when {
                    release != null -> onUi { offer(release) }
                    !quiet -> onUi { toast("OmniDeck $currentVersion is up to date") }
                }
            } catch (_: IOException) {
                if (!quiet) onUi { toast("Couldn't check for updates") }
            } catch (_: JSONException) {
                if (!quiet) onUi { toast("Couldn't check for updates") }
            }
        }, "UpdateCheck").apply { isDaemon = true }.start()
    }

    private fun onUi(action: () -> Unit) = activity.runOnUiThread { if (!activity.isDestroyed) action() }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "OmniDeck-Updater")
    }

    private fun fetchLatest(): Release? {
        val connection = open(LATEST_RELEASE_API)
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null // nothing published yet
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null

            val tag = json.getString("tag_name")
            if (!UpdateRules.isNewer(tag, currentVersion)) return null

            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val url = asset.getString("browser_download_url")
                if (asset.getString("name") == APK_NAME && UpdateRules.isTrustedDownload(url)) return Release(tag, url)
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun offer(release: Release) {
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage("OmniDeck ${release.tag.removePrefix("v")} is ready to install. You have $currentVersion. Update the PC app too, because the two versions need to match.")
            .setPositiveButton("Update") { _, _ -> install(release) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun install(release: Release) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            toast("Allow OmniDeck to install apps, then tap Update again")
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return
        }

        toast("Downloading update")
        Thread({
            val installer = activity.packageManager.packageInstaller
            val sessionId = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            try {
                installer.openSession(sessionId).use { session ->
                    val connection = open(release.apkUrl)
                    try {
                        connection.inputStream.use { input ->
                            session.openWrite("omnideck.apk", 0, connection.contentLengthLong).use { output ->
                                input.copyTo(output)
                                session.fsync(output)
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }

                    val result = Intent(activity, UpdateInstallReceiver::class.java)
                    val pending = PendingIntent.getBroadcast(
                        activity,
                        sessionId,
                        result,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // the installer adds its result to this
                    )
                    session.commit(pending.intentSender)
                }
            } catch (_: IOException) {
                installer.abandonSession(sessionId)
                onUi { toast("Update download failed. Try again.") }
            }
        }, "UpdateDownload").apply { isDaemon = true }.start()
    }

    private companion object {
        const val LATEST_RELEASE_API = "https://api.github.com/repos/Pongki69/OmniDeck/releases/latest"
        const val APK_NAME = "OmniDeck.apk"
    }
}

/** Receives Android's installer result and shows its confirmation screen. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                Toast.makeText(context, "This update is signed differently. Uninstall OmniDeck once, then install the latest APK.", Toast.LENGTH_LONG).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // the player tapped Cancel
            else -> Toast.makeText(context, "The update couldn't be installed", Toast.LENGTH_LONG).show()
        }
    }
}
