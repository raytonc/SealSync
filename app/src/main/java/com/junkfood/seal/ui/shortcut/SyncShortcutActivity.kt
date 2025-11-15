package com.junkfood.seal.ui.shortcut

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.junkfood.seal.Downloader
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncShortcutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Just kick off the sync - Downloader manages the service automatically
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val playlists = DatabaseUtil.getPlaylistsFlow().first()
                if (playlists.isNotEmpty()) {
                    Downloader.syncPlaylists(playlists)
                } else {
                    withContext(Dispatchers.Main) {
                        ToastUtil.makeToast("No playlists to sync")
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    finish()
                }
            }
        }
    }
}
