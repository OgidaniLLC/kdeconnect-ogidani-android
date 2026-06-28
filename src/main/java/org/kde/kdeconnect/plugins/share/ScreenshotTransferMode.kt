/*
 * SPDX-FileCopyrightText: 2026 OgidaniLLC <admin@ogidani.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.share

enum class ScreenshotTransferMode(val key: String) {
    AUTO("auto"),
    NOTIFY("notify"),
    MANUAL("manual");

    companion object {
        fun fromKey(key: String): ScreenshotTransferMode =
            entries.firstOrNull { it.key == key } ?: NOTIFY
    }
}
