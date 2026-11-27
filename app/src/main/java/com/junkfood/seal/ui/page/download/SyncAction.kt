package com.junkfood.seal.ui.page.download

import android.Manifest
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.junkfood.seal.Downloader
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.HapticFeedback.slightHapticFeedback
import com.junkfood.seal.util.AutoSyncWorker
import com.junkfood.seal.util.CELLULAR_DOWNLOAD
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.ToastUtil

/**
 * What the sync button does, shared by every screen that has one.
 *
 * Returns the click handler. Tapping while a sync runs stops it. Otherwise it checks the
 * storage permission on old Android versions, then the network, and asks before using
 * mobile data. The mobile-data dialog is shown from here, so callers only wire up the
 * button.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberSyncAction(onNoPlaylists: () -> Unit): () -> Unit {
    val view = LocalView.current
    val context = LocalContext.current
    val playlists by DatabaseUtil.getPlaylistsFlow().collectAsState(initial = emptyList())
    val downloaderState by Downloader.downloaderState.collectAsState()
    var showMeteredNetworkDialog by remember { mutableStateOf(false) }

    val startOrAskForNetwork = {
        if (!PreferenceUtil.isNetworkAvailableForDownload()) {
            showMeteredNetworkDialog = true
        } else {
            Downloader.syncPlaylists(playlists)
        }
    }

    val storagePermission = rememberPermissionState(
        permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) { granted ->
        if (granted) startOrAskForNetwork() else ToastUtil.makeToast(R.string.permission_denied)
    }

    if (showMeteredNetworkDialog) {
        MeteredNetworkDialog(
            onDismissRequest = { showMeteredNetworkDialog = false },
            onAllowOnceConfirm = {
                Downloader.syncPlaylists(playlists)
                showMeteredNetworkDialog = false
            },
            onAllowAlwaysConfirm = {
                // Persist the choice, otherwise "always" behaves the same as "once".
                CELLULAR_DOWNLOAD.updateBoolean(true)
                // The scheduled sync derives its network constraint from this preference,
                // and an already-enqueued schedule keeps the one it was built with.
                AutoSyncWorker.applySettingsChange(context)
                Downloader.syncPlaylists(playlists)
                showMeteredNetworkDialog = false
            }
        )
    }

    return {
        view.slightHapticFeedback()
        when {
            downloaderState is Downloader.State.DownloadingPlaylist -> Downloader.cancelSync()
            playlists.isEmpty() -> onNoPlaylists()
            Build.VERSION.SDK_INT > 29 ||
                    storagePermission.status == PermissionStatus.Granted -> startOrAskForNetwork()

            else -> storagePermission.launchPermissionRequest()
        }
    }
}
