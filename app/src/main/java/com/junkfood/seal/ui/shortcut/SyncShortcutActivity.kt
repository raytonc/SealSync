package com.junkfood.seal.ui.shortcut

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.junkfood.seal.DownloadService

/**
 * Transparent activity that starts the sync service and immediately finishes.
 * The service handles all the work and lifecycle management.
 */
class SyncShortcutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the service with explicit action for syncing playlists
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_SYNC_PLAYLISTS
        }

        // Use startForegroundService on Android O+ to properly start foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Activity can finish immediately - service continues in foreground
        finish()
    }
}
