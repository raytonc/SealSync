package com.junkfood.seal.ui.page.download

import android.Manifest
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import com.google.accompanist.permissions.rememberPermissionState
import com.junkfood.seal.App
import com.junkfood.seal.Downloader
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.PlaylistEntry
import com.junkfood.seal.ui.common.HapticFeedback.longPressHapticFeedback
import com.junkfood.seal.ui.common.HapticFeedback.slightHapticFeedback
import com.junkfood.seal.ui.component.EmptyLibraryState
import com.junkfood.seal.ui.component.LibraryHeader
import com.junkfood.seal.ui.component.MissingApiKeyBanner
import com.junkfood.seal.ui.component.NavigationBarSpacer
import com.junkfood.seal.ui.component.PlaylistRow
import com.junkfood.seal.ui.component.PlaylistSyncState
import com.junkfood.seal.ui.component.SwipeToRemove
import com.junkfood.seal.ui.component.SyncProgressCard
import com.junkfood.seal.ui.component.SyncSummaryCard
import com.junkfood.seal.ui.theme.ArtworkShape
import com.junkfood.seal.util.AutoSyncWorker
import com.junkfood.seal.util.CELLULAR_DOWNLOAD
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.YOUTUBE_API_KEY
import com.junkfood.seal.util.YouTubeApiService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadPage(
    navigateToSettings: () -> Unit = {},
    navigateToDownloads: () -> Unit = {},
    navigateToQueue: () -> Unit = {},
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
) {
    val view = LocalView.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val downloaderState by Downloader.downloaderState.collectAsStateWithLifecycle()
    val queueSummary by Downloader.queueSummary.collectAsStateWithLifecycle()
    val activeTitles by Downloader.activeTitles.collectAsStateWithLifecycle()
    val syncResult by Downloader.syncResult.collectAsStateWithLifecycle()
    val errorState by Downloader.errorState.collectAsStateWithLifecycle()
    val playlists by playlistViewModel.playlistsFlow.collectAsStateWithLifecycle()
    val addPlaylistState by playlistViewModel.addPlaylistState.collectAsStateWithLifecycle()
    val channelPlaylistsState by playlistViewModel.channelPlaylistsState.collectAsStateWithLifecycle()

    var showAddPlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var showChannelPlaylistsDialog by rememberSaveable { mutableStateOf(false) }
    var showMeteredNetworkDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val isSyncing = downloaderState is Downloader.State.DownloadingPlaylist
    val runState = downloaderState as? Downloader.State.DownloadingPlaylist
    val syncPhase = runState?.phase ?: Downloader.Phase.Fetching
    val deletedSoFar = runState?.deleted ?: 0

    // The key lives outside the observable settings flow, and the only way to set it is the
    // Settings screen, so re-read it whenever this screen comes back to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasApiKey by remember { mutableStateOf(YOUTUBE_API_KEY.getString().isNotBlank()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasApiKey = YOUTUBE_API_KEY.getString().isNotBlank()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A finished sync deserves a moment of acknowledgement, then it gets out of the way --
    // except when it failed, where the card is the only account of what went wrong and
    // timing it out just hides the problem. Those wait for the dismiss button.
    LaunchedEffect(syncResult) {
        val result = syncResult ?: return@LaunchedEffect
        view.slightHapticFeedback()
        if (result.error != null) return@LaunchedEffect
        delay(6000)
        Downloader.clearSyncResult()
    }

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
        // Tapping the button mid-sync now stops the run instead of doing nothing at all,
        // which used to read as the app being frozen.
        if (isSyncing) {
            Downloader.cancelSync()
            return@downloadAllCallback
        }
        if (playlists.isEmpty()) {
            scope.launch {
                snackbarHostState.showSnackbar("Add a playlist first")
            }
            return@downloadAllCallback
        }
        checkPermissionOrDownload()
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
                // The scheduled sync derives its network constraint from this same
                // preference, and an already-enqueued schedule keeps the constraint it was
                // built with -- so it would stay pinned to unmetered until something else
                // happened to rebuild it.
                AutoSyncWorker.applySettingsChange(context)
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

    if (showChannelPlaylistsDialog) {
        ChannelPlaylistsDialog(
            onDismiss = {
                showChannelPlaylistsDialog = false
                playlistViewModel.resetChannelPlaylistsState()
            },
            onPlaylistSelected = { channelPlaylist ->
                view.slightHapticFeedback()
                playlistViewModel.addPlaylistFromChannel(channelPlaylist)
            },
            onPlaylistDeselected = { channelPlaylist ->
                view.slightHapticFeedback()
                playlistViewModel.removePlaylistByChannelId(channelPlaylist.id)
            },
            channelPlaylistsState = channelPlaylistsState,
            onFetch = {
                playlistViewModel.fetchChannelPlaylists()
            },
            existingPlaylists = playlists
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.app_name)) },
                scrollBehavior = scrollBehavior,
                // No navigation icon: this is the root screen, and the leading slot is
                // where up/back belongs. Both tools live together on the trailing side.
                actions = {
                    TooltipBox(
                        state = rememberTooltipState(),
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(text = stringResource(id = R.string.download_queue)) } }
                    ) {
                        IconButton(
                            onClick = {
                                view.slightHapticFeedback()
                                navigateToQueue()
                            }
                        ) {
                            // Badged while a run is going, so the queue is findable without
                            // having to guess that anything is happening behind this icon.
                            BadgedBox(
                                badge = {
                                    // Only once there is a queue to badge. The earlier
                                    // phases have nothing downloading, and a badge reading
                                    // "0" says the run is idle when it is not.
                                    if (isSyncing && queueSummary.downloading > 0) {
                                        Badge {
                                            Text(text = queueSummary.downloading.toString())
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Downloading,
                                    contentDescription = stringResource(id = R.string.download_queue)
                                )
                            }
                        }
                    }
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
                                imageVector = Icons.Outlined.LibraryMusic,
                                contentDescription = stringResource(id = R.string.downloads_history)
                            )
                        }
                    }
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
                }
            )
        },
        floatingActionButton = {
            SyncFabStack(
                isSyncing = isSyncing,
                hasPlaylists = playlists.isNotEmpty(),
                onAddByUrl = {
                    view.slightHapticFeedback()
                    showAddPlaylistDialog = true
                },
                onAddFromChannel = {
                    view.slightHapticFeedback()
                    showChannelPlaylistsDialog = true
                },
                onSync = downloadAllCallback,
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Status region: sync progress, the post-run summary, missing key, and errors.
            // Animated so these appear and leave rather than snapping in.
            Column(Modifier.padding(horizontal = 20.dp)) {
                AnimatedVisibility(
                    visible = isSyncing,
                    enter = fadeIn() + expandVertically(
                        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)
                    ),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    SyncProgressCard(
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        summary = queueSummary,
                        phase = syncPhase,
                        deleted = deletedSoFar,
                        activeTitles = activeTitles,
                        onCancel = {
                            view.slightHapticFeedback()
                            Downloader.cancelSync()
                        },
                        onOpenQueue = {
                            view.slightHapticFeedback()
                            navigateToQueue()
                        },
                    )
                }

                AnimatedVisibility(
                    visible = syncResult != null && !isSyncing,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    syncResult?.let { result ->
                        SyncSummaryCard(
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            result = result,
                            onDismiss = { Downloader.clearSyncResult() },
                        )
                    }
                }

                AnimatedVisibility(visible = !hasApiKey && !isSyncing) {
                    MissingApiKeyBanner(
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        onOpenSettings = navigateToSettings,
                    )
                }

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
                EmptyLibraryState(
                    onAddFromChannel = { showChannelPlaylistsDialog = true },
                    onAddByUrl = { showAddPlaylistDialog = true },
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "header") {
                        LibraryHeader(
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            playlistCount = playlists.size,
                            trackCount = playlists.sumOf { it.videoCount },
                            lastSynced = playlists.maxOfOrNull { it.lastSynced } ?: 0L,
                        )
                    }
                    items(playlists, key = { it.id }) { playlist ->
                        SwipeToRemove(
                            modifier = Modifier.animateItem(),
                            onRemove = {
                                playlistViewModel.deletePlaylist(playlist)
                                scope.launch {
                                    val action = snackbarHostState.showSnackbar(
                                        message = "Removed ${playlist.title}",
                                        actionLabel = "Undo",
                                        withDismissAction = true,
                                    )
                                    if (action == SnackbarResult.ActionPerformed) {
                                        playlistViewModel.restorePlaylist(playlist)
                                    }
                                }
                            },
                        ) {
                            PlaylistRow(
                                playlist = playlist,
                                syncState = playlist.syncStateFor(isSyncing = isSyncing),
                            )
                        }
                    }
                    item(key = "spacer") {
                        NavigationBarSpacer()
                        Spacer(modifier = Modifier.height(150.dp))
                    }
                }
            }
        }
    }
}

/**
 * Derives the row's status chip. During a run everything is either being worked on or
 * queued behind it; the downloader syncs the whole set as one pass rather than per-playlist,
 * so a run marks them all as in-flight.
 */
private fun PlaylistEntry.syncStateFor(isSyncing: Boolean): PlaylistSyncState = when {
    isSyncing -> PlaylistSyncState.Syncing
    lastSynced > 0 -> PlaylistSyncState.Synced
    else -> PlaylistSyncState.NeverSynced
}

/**
 * Three one-tap actions, ordered by how often they get used: sync at the bottom under the
 * thumb, adding from the channel above it as the main way playlists get in, and the URL
 * fallback at the top. All three are the same small icon-only button in the same accent so
 * the stack reads as one control rather than three competing shapes. Nothing is behind a
 * menu — every action is a single tap.
 */
@Composable
private fun SyncFabStack(
    isSyncing: Boolean,
    hasPlaylists: Boolean,
    onAddByUrl: () -> Unit,
    onAddFromChannel: () -> Unit,
    onSync: () -> Unit,
) {
    Column(
        modifier = Modifier.imePadding(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The URL path is the fallback for a playlist that is not on your own channel.
        SmallFloatingActionButton(
            onClick = onAddByUrl,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(Icons.Outlined.Link, contentDescription = "Add a playlist by URL")
        }

        SmallFloatingActionButton(
            onClick = onAddFromChannel,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(Icons.Outlined.Subscriptions, contentDescription = "Add a playlist from your channel")
        }

        // Hidden until there is something to sync: on a fresh install the button could only
        // ever report "add a playlist first", so it was noise sitting in the primary slot.
        AnimatedVisibility(
            visible = hasPlaylists,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            SmallFloatingActionButton(
                onClick = onSync,
                containerColor = if (isSyncing) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (isSyncing) MaterialTheme.colorScheme.onTertiaryContainer
                else MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                } else {
                    Icon(Icons.Outlined.Sync, contentDescription = "Sync playlists")
                }
            }
        }
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
        icon = { Icon(Icons.Outlined.Link, contentDescription = null) },
        title = { Text("Add a playlist") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Playlist link") },
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
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                                8.dp
                            )
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                "Looking up the playlist…",
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
fun ChannelPlaylistsDialog(
    onDismiss: () -> Unit,
    onPlaylistSelected: (YouTubeApiService.ChannelPlaylistInfo) -> Unit,
    onPlaylistDeselected: (YouTubeApiService.ChannelPlaylistInfo) -> Unit,
    channelPlaylistsState: ChannelPlaylistsState,
    onFetch: () -> Unit,
    existingPlaylists: List<PlaylistEntry>
) {
    // Auto-fetch when dialog opens
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (channelPlaylistsState is ChannelPlaylistsState.Idle) {
            onFetch()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Subscriptions, contentDescription = null) },
        title = { Text("Your channel playlists") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                when (channelPlaylistsState) {
                    is ChannelPlaylistsState.Loading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Loading your playlists…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    is ChannelPlaylistsState.Error -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Error,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = channelPlaylistsState.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }

                    is ChannelPlaylistsState.Success -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            item(key = "hint") {
                                val addedCount = channelPlaylistsState.playlists.count { p ->
                                    existingPlaylists.any {
                                        it.playlistId == p.id || it.url.contains(p.id)
                                    }
                                }
                                Text(
                                    text = if (addedCount > 0)
                                        "$addedCount selected · tap to add or remove"
                                    else "Tap a playlist to add it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            items(channelPlaylistsState.playlists, key = { it.id }) { playlist ->
                                val isAlreadyAdded = existingPlaylists.any {
                                    it.playlistId == playlist.id || it.url.contains(playlist.id)
                                }

                                ChannelPlaylistItem(
                                    playlist = playlist,
                                    isAlreadyAdded = isAlreadyAdded,
                                    // Tapping an added row takes it back out, so the picker
                                    // works as a checklist instead of a one-way door.
                                    onClick = {
                                        if (isAlreadyAdded) onPlaylistDeselected(playlist)
                                        else onPlaylistSelected(playlist)
                                    }
                                )
                            }
                        }
                    }

                    is ChannelPlaylistsState.Idle -> {
                        // Should not reach here due to LaunchedEffect
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
fun ChannelPlaylistItem(
    playlist: YouTubeApiService.ChannelPlaylistInfo,
    isAlreadyAdded: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            // Always clickable now: an added row toggles back off.
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isAlreadyAdded)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(ArtworkShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (playlist.thumbnailUrl != null) {
                    AsyncImage(
                        model = playlist.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Text info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isAlreadyAdded)
                        MaterialTheme.colorScheme.onSecondaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${playlist.itemCount} videos",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isAlreadyAdded)
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Reads as a checkbox so it is obvious the row can be turned back off.
            Icon(
                imageVector = if (isAlreadyAdded) Icons.Rounded.CheckCircle
                else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (isAlreadyAdded) "Added, tap to remove"
                else "Tap to add",
                tint = if (isAlreadyAdded) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(24.dp)
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
