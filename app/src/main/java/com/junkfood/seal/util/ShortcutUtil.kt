package com.junkfood.seal.util

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.junkfood.seal.R
import com.junkfood.seal.ui.shortcut.SyncShortcutActivity

object ShortcutUtil {

    /**
     * Request the launcher to pin a "Sync" shortcut on the home screen (API 26+).
     * This uses the platform ShortcutManager.requestPinShortcut API.
     */
    fun requestPinSyncShortcut(context: Context) {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported) return

        val shortLabel = context.getString(R.string.shortcut_sync_short)
        val longLabel = context.getString(R.string.shortcut_sync_long)

        // Use an explicit Intent targeting the invisible Activity
        val intent = Intent(context, SyncShortcutActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        val shortcut = ShortcutInfo.Builder(context, "sync_shortcut_pinned")
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_sync))
            .setIntent(intent)
            .build()

        // Request the launcher to pin the shortcut (may show confirmation)
        shortcutManager.requestPinShortcut(shortcut, null)
    }
}
