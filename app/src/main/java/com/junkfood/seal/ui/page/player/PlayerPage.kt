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
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.LargeTopAppBar
import com.junkfood.seal.util.ToastUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerPage(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val library by PlayerLibrary.state.collectAsStateWithLifecycle()
    val expanded by PlayerLibrary.expanded.collectAsStateWithLifecycle()
    val status by NowPlaying.status.collectAsStateWithLifecycle()

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

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.player)) },
                navigationIcon = { BackButton(onNavigateBack) },
                actions = {
                    IconButton(onClick = { PlayerLibrary.reloadAsync() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Rescan")
                    }
                    IconButton(onClick = {
                        PlayerLibrary.regroup()
                        ToastUtil.makeToast(context.getString(R.string.player_regroup_started))
                    }) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = stringResource(R.string.player_regroup)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = { NowPlayingBar(status = status) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
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
            val progress = if (status.durationMs > 0) {
                (status.positionMs.toFloat() / status.durationMs).coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
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
