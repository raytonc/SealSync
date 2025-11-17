package com.junkfood.seal.ui.page.download

import android.Manifest
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.junkfood.seal.App
import com.junkfood.seal.Downloader
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.PlaylistEntry
import com.junkfood.seal.ui.common.HapticFeedback.longPressHapticFeedback
import com.junkfood.seal.ui.common.HapticFeedback.slightHapticFeedback
import com.junkfood.seal.ui.component.NavigationBarSpacer
import com.junkfood.seal.util.NOTIFICATION
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.ToastUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadPage(
    navigateToSettings: () -> Unit = {},
    navigateToDownloads: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") downloadViewModel: DownloadViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
) {
    val view = LocalView.current
    val clipboardManager = LocalClipboardManager.current
    val downloaderState by Downloader.downloaderState.collectAsStateWithLifecycle()
    val errorState by Downloader.errorState.collectAsStateWithLifecycle()
    val playlists by playlistViewModel.playlistsFlow.collectAsStateWithLifecycle()
    val addPlaylistState by playlistViewModel.addPlaylistState.collectAsStateWithLifecycle()

    var showNotificationDialog by remember { mutableStateOf(false) }
    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(permission = Manifest.permission.POST_NOTIFICATIONS) { isGranted: Boolean ->
            showNotificationDialog = false
            if (!isGranted) {
                ToastUtil.makeToast(R.string.permission_denied)
            }
        }
    } else null

    var showAddPlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var showMeteredNetworkDialog by remember { mutableStateOf(false) }

    val checkNetworkOrDownload = {
        if (!PreferenceUtil.isNetworkAvailableForDownload()) {
            showMeteredNetworkDialog = true
        } else {
            Downloader.syncPlaylists(playlists)
        }
    }

    val storagePermission = rememberPermissionState(
        permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) { b: Boolean ->
        if (b) {
            checkNetworkOrDownload()
        } else {
            ToastUtil.makeToast(R.string.permission_denied)
        }
    }

    val checkPermissionOrDownload = {
        if (Build.VERSION.SDK_INT > 29 || storagePermission.status == PermissionStatus.Granted) {
            checkNetworkOrDownload()
        } else {
            storagePermission.launchPermissionRequest()
        }
    }

    val downloadAllCallback = downloadAllCallback@{
        view.slightHapticFeedback()
        if (downloaderState is Downloader.State.DownloadingPlaylist) {
            return@downloadAllCallback
        }
        if (playlists.isEmpty()) {
            ToastUtil.makeToast("No playlists to download")
            return@downloadAllCallback
        }
        if (NOTIFICATION.getBoolean() && notificationPermission?.status?.isGranted == false) {
            showNotificationDialog = true
        } else {
            checkPermissionOrDownload()
        }
    }

    if (showNotificationDialog) {
        NotificationPermissionDialog(
            onDismissRequest = {
                showNotificationDialog = false
                NOTIFICATION.updateBoolean(false)
            },
            onPermissionGranted = {
                notificationPermission?.launchPermissionRequest()
            }
        )
    }

    if (showMeteredNetworkDialog) {
        MeteredNetworkDialog(
            onDismissRequest = { showMeteredNetworkDialog = false },
            onAllowOnceConfirm = {
                Downloader.syncPlaylists(playlists)
                showMeteredNetworkDialog = false
            },
            onAllowAlwaysConfirm = {
                Downloader.syncPlaylists(playlists)
                showMeteredNetworkDialog = false
            }
        )
    }

    if (showAddPlaylistDialog) {
        AddPlaylistDialog(
            onDismiss = {
                showAddPlaylistDialog = false
                playlistViewModel.resetAddPlaylistState()
            },
            onConfirm = { url ->
                playlistViewModel.addPlaylistFromUrl(url)
            },
            addPlaylistState = addPlaylistState
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {},
                modifier = Modifier.padding(horizontal = 8.dp),
                navigationIcon = {
                    TooltipBox(
                        state = rememberTooltipState(),
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(text = stringResource(id = R.string.settings)) } }
                    ) {
                        IconButton(
                            onClick = {
                                view.slightHapticFeedback()
                                navigateToSettings()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = stringResource(id = R.string.settings)
                            )
                        }
                    }
                },
                actions = {
                    TooltipBox(
                        state = rememberTooltipState(),
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(text = stringResource(id = R.string.downloads_history)) } }
                    ) {
                        IconButton(
                            onClick = {
                                view.slightHapticFeedback()
                                navigateToDownloads()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Subscriptions,
                                contentDescription = stringResource(id = R.string.downloads_history)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                modifier = Modifier
                    .padding(6.dp)
                    .imePadding(),
                horizontalAlignment = Alignment.End
            ) {
                ExtendedFloatingActionButton(
                    onClick = { showAddPlaylistDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = "Add Playlist") },
                    text = { Text("Add playlist") },
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                val isSyncing = downloaderState is Downloader.State.DownloadingPlaylist
                ExtendedFloatingActionButton(
                    onClick = downloadAllCallback,
                    icon = { Icon(Icons.Outlined.DownloadForOffline, contentDescription = "Sync") },
                    text = { Text("Sync folder") },
                    modifier = Modifier.padding(vertical = 8.dp),
                    containerColor = if (isSyncing) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (isSyncing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    AnimatedVisibility(visible = errorState != Downloader.ErrorState.None) {
                        ErrorMessage(
                            title = errorState.title,
                            errorReport = errorState.report
                        ) {
                            view.longPressHapticFeedback()
                            clipboardManager.setText(
                                AnnotatedString(App.getVersionReport() + "\nURL: ${errorState.url}\n${errorState.report}")
                            )
                            ToastUtil.makeToast(R.string.error_copied)
                        }
                    }
                }

                if (playlists.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No playlists yet",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tap + to add a playlist",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp)
                    ) {
                        items(playlists, key = { it.id }) { playlist ->
                            PlaylistItem(
                                playlist = playlist,
                                onDelete = { playlistViewModel.deletePlaylist(playlist) }
                            )
                        }
                        item {
                            NavigationBarSpacer()
                            Spacer(modifier = Modifier.height(160.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistItem(
    playlist: PlaylistEntry,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Square Thumbnail (56dp x 56dp)
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                playlist.thumbnailUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = playlist.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } ?: Icon(
                    imageVector = Icons.Outlined.PlaylistPlay,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    playlist.channelTitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    if (playlist.videoCount > 0) {
                        if (playlist.channelTitle != null) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${playlist.videoCount} videos",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Last synced info
                if (playlist.lastSynced > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Synced ${formatRelativeTime(playlist.lastSynced)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            onDelete()
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}

// Helper function for relative time display
private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        diff < 604800_000 -> "${diff / 86400_000}d ago"
        else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
    }
}

@Composable
fun AddPlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    addPlaylistState: AddPlaylistState
) {
    var url by remember { mutableStateOf("") }
    val isLoading = addPlaylistState is AddPlaylistState.Loading

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Add Playlist") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("YouTube Playlist URL") },
                    placeholder = { Text("https://youtube.com/playlist?list=...") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    isError = addPlaylistState is AddPlaylistState.Error
                )

                when (addPlaylistState) {
                    is AddPlaylistState.Loading -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text(
                                "Fetching playlist info...",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    is AddPlaylistState.Error -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = addPlaylistState.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    is AddPlaylistState.Success -> {
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            onDismiss()
                        }
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (url.isNotBlank()) {
                        onConfirm(url)
                    }
                },
                enabled = url.isNotBlank() && !isLoading
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun TitleWithProgressIndicator(
    showProgressIndicator: Boolean = true,
    showDownloadText: Boolean = true,
    isDownloadingPlaylist: Boolean = true,
    currentIndex: Int = 1,
    downloadItemCount: Int = 4,
) {
    Column(modifier = Modifier.padding(start = 12.dp, top = 24.dp)) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.extraLarge)
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp, bottom = 3.dp)
        ) {
            Text(
                modifier = Modifier,
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall
            )
            AnimatedVisibility(visible = showProgressIndicator) {
                Column(
                    modifier = Modifier.padding(start = 12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp), strokeWidth = 3.dp
                    )
                }
            }
        }
        AnimatedVisibility(visible = showDownloadText) {
            Text(
                if (isDownloadingPlaylist) stringResource(R.string.playlist_indicator_text).format(
                    currentIndex,
                    downloadItemCount
                )
                else stringResource(R.string.downloading_indicator_text),
                modifier = Modifier.padding(start = 12.dp, top = 3.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ErrorMessage(
    modifier: Modifier = Modifier,
    title: String,
    errorReport: String,
    onButtonClicked: () -> Unit = {}
) {
    val view = LocalView.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.padding(vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier,
                        text = title,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            var isExpanded by remember { mutableStateOf(false) }

            Text(
                text = errorReport,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                overflow = TextOverflow.Ellipsis,
                maxLines = if (isExpanded) Int.MAX_VALUE else 8,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(
                        enabled = !isExpanded,
                        onClickLabel = stringResource(id = R.string.expand),
                        onClick = {
                            view.slightHapticFeedback()
                            isExpanded = true
                        }
                    )
                    .padding(4.dp),
                onTextLayout = {
                    isExpanded = !it.hasVisualOverflow
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(
                    onClick = onButtonClicked,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(text = stringResource(id = R.string.copy_error_report))
                }
            }
        }
    }
}
