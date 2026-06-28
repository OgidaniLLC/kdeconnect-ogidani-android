/*
 * SPDX-FileCopyrightText: 2026 OgidaniLLC <admin@ogidani.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.share

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect_tp.R

/**
 * Foreground Service that monitors MediaStore for new screenshots and
 * triggers transfer via SharePlugin according to the configured transfer mode.
 *
 * Transfer modes (per-device setting):
 *   AUTO   — send immediately on detection
 *   NOTIFY — show a "Send to PC?" notification (default)
 *   MANUAL — do nothing; user sends manually via share sheet
 */
class ScreenshotObserverService : Service() {

    private lateinit var screenshotObserver: ContentObserver

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID_PERSISTENT, buildPersistentNotification())
        registerScreenshotObserver()
        Log.i(TAG, "ScreenshotObserverService started")
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(screenshotObserver)
        Log.i(TAG, "ScreenshotObserverService stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── MediaStore observer ──────────────────────────────────────────────────

    private fun registerScreenshotObserver() {
        screenshotObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri ?: return
                val path = resolveFilePath(uri) ?: return
                if (!isScreenshot(path)) return
                Log.d(TAG, "Screenshot detected: $path")
                handleScreenshot(uri, path)
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            screenshotObserver
        )
    }

    private fun resolveFilePath(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.DATA),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve path for $uri", e)
            null
        }
    }

    private fun isScreenshot(path: String): Boolean =
        path.contains("/Screenshots/", ignoreCase = true) ||
        path.contains("/screenshot", ignoreCase = true)

    // ── Transfer dispatch ────────────────────────────────────────────────────

    private fun handleScreenshot(uri: Uri, path: String) {
        val mode = getTransferMode()
        when (mode) {
            ScreenshotTransferMode.AUTO -> sendToAllDevices(uri)
            ScreenshotTransferMode.NOTIFY -> showConfirmNotification(uri, path)
            ScreenshotTransferMode.MANUAL -> { /* user sends via share sheet */ }
        }
    }

    private fun getTransferMode(): ScreenshotTransferMode {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val key = prefs.getString(PREF_SCREENSHOT_MODE, ScreenshotTransferMode.NOTIFY.key)
        return ScreenshotTransferMode.fromKey(key ?: ScreenshotTransferMode.NOTIFY.key)
    }

    private fun sendToAllDevices(uri: Uri) {
        val devices = KdeConnect.getInstance().devices.values
            .filter { it.isReachable && it.isPaired }
        if (devices.isEmpty()) {
            Log.w(TAG, "No reachable paired devices — screenshot not sent")
            return
        }
        val uriList = arrayListOf(uri)
        for (device in devices) {
            val plugin = device.getPlugin(SharePlugin::class.java) ?: continue
            plugin.sendUriList(uriList)
            Log.i(TAG, "Screenshot auto-sent to ${device.name}")
        }
    }

    // ── Confirm notification (NOTIFY mode) ──────────────────────────────────

    private fun showConfirmNotification(uri: Uri, path: String) {
        val filename = path.substringAfterLast('/')

        val sendIntent = Intent(this, ScreenshotSendReceiver::class.java).apply {
            action = ACTION_SEND_SCREENSHOT
            data = uri
        }
        val sendPendingIntent = PendingIntent.getBroadcast(
            this, uri.hashCode(), sendIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(this, ScreenshotSendReceiver::class.java).apply {
            action = ACTION_DISMISS_SCREENSHOT
            data = uri
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            this, uri.hashCode() + 1, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NotificationHelper.Channels.SCREENSHOT_CONFIRM)
            .setSmallIcon(R.drawable.share_plugin_action_24dp)
            .setContentTitle(getString(R.string.ogidani_screenshot_notify_title))
            .setContentText(filename)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(R.drawable.share_plugin_action_24dp,
                getString(R.string.ogidani_screenshot_notify_send), sendPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.ogidani_screenshot_notify_dismiss), dismissPendingIntent)
            .build()

        val nm = getSystemService<NotificationManager>()!!
        nm.notify(NOTIFICATION_ID_CONFIRM_BASE + uri.hashCode(), notification)
    }

    // ── Persistent foreground notification ──────────────────────────────────

    private fun buildPersistentNotification(): Notification {
        return NotificationCompat.Builder(this, NotificationHelper.Channels.SCREENSHOT_CONFIRM)
            .setSmallIcon(R.drawable.share_plugin_action_24dp)
            .setContentTitle(getString(R.string.ogidani_screenshot_service_title))
            .setContentText(getString(R.string.ogidani_screenshot_service_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ScreenshotObserver"

        const val PREF_SCREENSHOT_MODE = "ogidani_screenshot_transfer_mode"

        const val ACTION_SEND_SCREENSHOT = "org.kde.kdeconnect.plugins.share.SEND_SCREENSHOT"
        const val ACTION_DISMISS_SCREENSHOT = "org.kde.kdeconnect.plugins.share.DISMISS_SCREENSHOT"

        private const val NOTIFICATION_ID_PERSISTENT = 9001
        const val NOTIFICATION_ID_CONFIRM_BASE = 9100

        @JvmStatic
        fun start(context: Context) {
            val intent = Intent(context, ScreenshotObserverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenshotObserverService::class.java))
        }
    }
}
