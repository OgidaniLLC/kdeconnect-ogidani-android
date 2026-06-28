/*
 * SPDX-FileCopyrightText: 2026 OgidaniLLC <admin@ogidani.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.share

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import org.kde.kdeconnect.KdeConnect

/**
 * Handles taps on the screenshot confirm notification.
 * ACTION_SEND_SCREENSHOT  → send the file to all reachable paired devices
 * ACTION_DISMISS_SCREENSHOT → cancel the notification silently
 */
class ScreenshotSendReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val nm = context.getSystemService<NotificationManager>()!!
        nm.cancel(ScreenshotObserverService.NOTIFICATION_ID_CONFIRM_BASE + uri.hashCode())

        when (intent.action) {
            ScreenshotObserverService.ACTION_SEND_SCREENSHOT -> {
                val devices = KdeConnect.getInstance().devices.values
                    .filter { it.isReachable && it.isPaired }
                if (devices.isEmpty()) {
                    Log.w(TAG, "No reachable paired devices")
                    return
                }
                val uriList = arrayListOf(uri)
                for (device in devices) {
                    val plugin = device.getPlugin(SharePlugin::class.java) ?: continue
                    plugin.sendUriList(uriList)
                    Log.i(TAG, "Screenshot sent to ${device.name}")
                }
            }
            ScreenshotObserverService.ACTION_DISMISS_SCREENSHOT -> {
                Log.d(TAG, "Screenshot dismissed: $uri")
            }
        }
    }

    companion object {
        private const val TAG = "ScreenshotSendReceiver"
    }
}
