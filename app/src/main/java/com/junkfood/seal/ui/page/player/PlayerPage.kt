package com.junkfood.seal.ui.page.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import com.junkfood.seal.Downloader
import com.junkfood.seal.ui.common.HapticFeedback.slightHapticFeedback
import com.junkfood.seal.ui.component.SyncProgressCard
import com.junkfood.seal.ui.component.SyncSummaryCard
import com.junkfood.seal.ui.page.download.rememberSyncAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import android.net.Uri
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.R
import com.junkfood.seal.player.GroupingStatus
import com.junkfood.seal.player.NowPlaying
import com.junkfood.seal.player.Piece
import com.junkfood.seal.player.PlayerActions
import com.junkfood.seal.player.PlayerClient
import com.junkfood.seal.player.PlayerLibrary
import com.junkfood.seal.player.PlayerRow
import com.junkfood.seal.player.buildRows
import com.junkfood.seal.player.formatDuration
import com.junkfood.seal.ui.common.AsyncImageImpl
import com.junkfood.seal.util.ToastUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerPage(
    navigateToPlaylists: () -> Unit,
    navigateToQueue: () -> Unit,
    navigateToSettings: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val library by PlayerLibrary.state.collectAsStateWithLifecycle()
    val expanded by PlayerLibrary.expanded.collectAsStateWithLifecycle()
    val status by NowPlaying.status.collectAsStateWithLifecycle()

    val downloaderState by Downloader.downloaderState.collectAsStateWithLifecycle()
    val queueSummary by Downloader.queueSummary.collectAsStateWithLifecycle()
    val activeTitles by Downloader.activeTitles.collectAsStateWithLifecycle()
    val syncResult by Downloader.syncResult.collectAsStateWithLifecycle()
    val runState = downloaderState as? Downloader.State.DownloadingPlaylist
    val isSyncing = runState != null

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noPlaylistsText = stringResource(R.string.player_no_playlists)
    val addText = stringResource(R.string.player_add_playlists)
    val sync = rememberSyncAction(onNoPlaylists = {
        scope.launch {
            val result = snackbarHostState.showSnackbar(noPlaylistsText, actionLabel = addText)
            if (result == SnackbarResult.ActionPerformed) navigateToPlaylists()
        }
    })

    // A clean result clears itself after a moment; a failed one waits to be dismissed.
    LaunchedEffect(syncResult) {
        val result = syncResult ?: return@LaunchedEffect
        if (result.error != null) return@LaunchedEffect
        delay(6000)
        Downloader.clearSyncResult()
    }

    LaunchedEffect(Unit) { PlayerLibrary.ensureLoaded() }

    val rows = remember(library.pieces, expanded) { buildRows(library.pieces, expanded) }
    val listState = rememberLazyListState()

    // Opens on whatever is playing rather than at the top of a long list.
    LaunchedEffect(library.loaded) {
        if (!library.loaded) return@LaunchedEffect
        val id = status.videoId ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.contains(id) }
        if (index > 0) listState.scrollToItem(index)
    }

    // Expanding a piece brings its header to the top, so its parts fill the screen.
    // Keyed on the open piece, so collapsing leaves the list where it is.
    val openPiece = expanded.firstOrNull()
    LaunchedEffect(openPiece) {
        val id = openPiece ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it is PlayerRow.Header && it.piece.id == id }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = {
                        view.slightHapticFeedback()
                        navigateToPlaylists()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.QueueMusic,
                            contentDescription = stringResource(R.string.playlists),
                        )
                    }
                    IconButton(onClick = sync) {
                        if (isSyncing) {
                            // The spinner doubles as the stop button while a run is going.
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.queue_cancel_sync),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        } else {
                            Icon(
                                Icons.Outlined.Sync,
                                contentDescription = stringResource(R.string.player_sync),
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.player_more),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.download_queue)) },
                                leadingIcon = { Icon(Icons.Outlined.Downloading, null) },
                                onClick = { menuOpen = false; navigateToQueue() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.player_regroup)) },
                                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) },
                                onClick = {
                                    menuOpen = false
                                    PlayerLibrary.regroup()
                                    ToastUtil.makeToast(context.getString(R.string.player_regroup_started))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.player_rescan)) },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                onClick = { menuOpen = false; PlayerLibrary.reloadAsync() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                onClick = { menuOpen = false; navigateToSettings() },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = { NowPlayingBar(status = status) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            AnimatedVisibility(visible = isSyncing) {
                SyncProgressCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    summary = queueSummary,
                    phase = runState?.phase ?: Downloader.Phase.Fetching,
                    deleted = runState?.deleted ?: 0,
                    activeTitles = activeTitles,
                    onCancel = { Downloader.cancelSync() },
                    onOpenQueue = navigateToQueue,
                )
            }
            AnimatedVisibility(visible = syncResult != null && !isSyncing) {
                syncResult?.let { result ->
                    SyncSummaryCard(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        result = result,
                        onDismiss = { Downloader.clearSyncResult() },
                    )
                }
            }
            GroupingBanner(library.grouping)
            if (rows.isEmpty() && (!library.loaded || library.loading)) {
                // The first load reads the tags of every file, which takes a few seconds.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = library.error ?: stringResource(R.string.player_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is PlayerRow.Header -> PieceRow(
                                piece = row.piece,
                                expanded = row.expanded,
                                currentId = status.videoId,
                                isPlaying = status.isPlaying,
                                onClick = { PlayerActions.onPieceClicked(context, row.piece) },
                                modifier = Modifier.animateItem(),
                            )

                            is PlayerRow.Part -> TrackRow(
                                title = row.label,
                                subtitle = null,
                                artUri = null,
                                durationMs = row.track.durationMs,
                                indented = true,
                                isCurrent = row.track.videoId == status.videoId,
                                isPlaying = status.isPlaying,
                                onClick = { PlayerActions.onTrackClicked(context, row.track.videoId) },
                                modifier = Modifier.animateItem(),
                            )

                            is PlayerRow.Single -> TrackRow(
                                title = row.track.title,
                                subtitle = row.piece.performer,
                                artUri = row.track.uri,
                                durationMs = row.track.durationMs,
                                indented = false,
                                isCurrent = row.track.videoId == status.videoId,
                                isPlaying = status.isPlaying,
                                onClick = { PlayerActions.onTrackClicked(context, row.track.videoId) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun PlayerRow.contains(videoId: String): Boolean = when (this) {
    is PlayerRow.Single -> track.videoId == videoId
    is PlayerRow.Part -> track.videoId == videoId
    is PlayerRow.Header -> !expanded && piece.tracks.any { it.videoId == videoId }
}

@Composable
private fun GroupingBanner(status: GroupingStatus) {
    val text = when (status) {
        is GroupingStatus.Running -> stringResource(R.string.player_grouping, status.tracks)
        is GroupingStatus.Failed -> stringResource(R.string.player_grouping_failed, status.message)
        GroupingStatus.NoApiKey -> stringResource(R.string.player_grouping_no_key)
        GroupingStatus.Idle -> null
    }
    AnimatedVisibility(visible = text != null) {
        Column {
            Text(
                text = text.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (status is GroupingStatus.Running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            }
        }
    }
}

@Composable
private fun PieceRow(
    piece: Piece,
    expanded: Boolean,
    currentId: String?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containsCurrent = piece.tracks.any { it.videoId == currentId }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    RowSurface(highlighted = containsCurrent && !expanded, onClick = onClick, modifier = modifier) {
        Artwork(uri = piece.tracks.first().uri, size = 48)
        Column(Modifier.weight(1f)) {
            Text(
                text = piece.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (containsCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (piece.performer.isNotBlank()) {
                Text(
                    text = piece.performer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (containsCurrent && isPlaying && !expanded) PlayingIndicator()
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(
                text = formatDuration(piece.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = pluralStringResource(R.plurals.player_parts, piece.tracks.size, piece.tracks.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(rotation),
        )
    }
}

@Composable
private fun TrackRow(
    title: String,
    subtitle: String?,
    artUri: String?,
    durationMs: Long,
    indented: Boolean,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RowSurface(
        highlighted = isCurrent,
        onClick = onClick,
        modifier = modifier.padding(start = if (indented) 60.dp else 0.dp),
    ) {
        if (artUri != null) Artwork(uri = artUri, size = 48)
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (indented) MaterialTheme.typography.bodyMedium
                else MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isCurrent && isPlaying) PlayingIndicator()
        Text(
            text = formatDuration(durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = if (indented) 8.dp else 32.dp),
        )
    }
}

@Composable
private fun RowSurface(
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (highlighted) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun PlayingIndicator() {
    Icon(
        imageVector = Icons.Rounded.GraphicEq,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun NowPlayingBar(status: NowPlaying.Status) {
    val context = LocalContext.current
    val library by PlayerLibrary.state.collectAsStateWithLifecycle()
    val track = library.track(status.videoId)
    val piece = library.pieceOf(status.videoId)

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding()) {
            SeekBar(status = status, fallbackDurationMs = track?.durationMs ?: 0L)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
            ) {
                track?.let { Artwork(uri = it.uri, size = 44) }
                Column(Modifier.weight(1f)) {
                    val (title, subtitle) = when {
                        track == null -> stringResource(R.string.player_nothing_playing) to ""
                        piece == null || piece.isSingle -> track.title to track.artist
                        else -> piece.partLabels[piece.tracks.indexOf(track)] to
                                listOf(piece.title, piece.performer)
                                    .filter { it.isNotBlank() }.joinToString(" · ")
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { PlayerClient.previous(context) }) {
                    Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous))
                }
                FilledIconButton(onClick = { PlayerClient.toggle(context) }) {
                    Icon(
                        if (status.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (status.isPlaying) R.string.player_pause else R.string.player_play),
                    )
                }
                IconButton(onClick = { PlayerClient.next(context) }) {
                    Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next))
                }
            }
        }
    }
}

/**
 * A draggable progress bar with elapsed and total time.
 *
 * While dragging, the thumb and the elapsed time follow the finger and the player is left
 * alone; the seek is sent once, on release. The service reports the position about once a
 * second, so the thumb is not otherwise animated between updates.
 */
@Composable
private fun SeekBar(status: NowPlaying.Status, fallbackDurationMs: Long) {
    val context = LocalContext.current
    val duration = status.durationMs.takeIf { it > 0 } ?: fallbackDurationMs
    val played = if (duration > 0) (status.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f

    // Non-null only while a drag is in progress.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: played

    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)) {
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { PlayerClient.seek(context, it) }
                dragging = null
            },
            enabled = duration > 0,
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = formatDuration((shown * duration).toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Embedded album art, read lazily through the app's audio-thumbnail fetcher and cached on
 * disk. A file without artwork shows a plain tile, so rows stay aligned.
 */
@Composable
private fun Artwork(uri: String, size: Int) {
    val model = remember(uri) { Uri.parse(uri) }
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(size.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        AsyncImageImpl(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
