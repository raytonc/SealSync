package com.junkfood.seal

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.junkfood.seal.util.CUSTOM_COMMAND
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.matchUrlFromSharedText

private const val TAG = "ShareActivity"

class QuickDownloadActivity : ComponentActivity() {
    private var url: String = ""
    private fun handleShareIntent(intent: Intent) {
        Log.d(TAG, "handleShareIntent: $intent")
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.dataString?.let {
                    url = it
                }
            }

            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?.let { sharedContent ->
                        intent.removeExtra(Intent.EXTRA_TEXT)
                        matchUrlFromSharedText(sharedContent)
                            .let { matchedUrl ->
                                url = matchedUrl
                            }
                    }
            }
        }
    }

    private fun onDownloadStarted(customCommand: Boolean) {
        if (customCommand)
            Downloader.executeCommandWithUrl(url)
        else
            Downloader.quickDownload(url = url)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)

        if (url.isEmpty()) {
            finish()
            return
        }

        // Start download directly without dialog
        onDownloadStarted(CUSTOM_COMMAND.getBoolean())
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        intent?.let { handleShareIntent(it) }
        super.onNewIntent(intent)
    }
}