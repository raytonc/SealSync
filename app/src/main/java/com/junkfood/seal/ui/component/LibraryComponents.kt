package com.junkfood.seal.ui.component

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.junkfood.seal.database.objects.PlaylistEntry
import com.junkfood.seal.ui.common.HapticFeedback.slightHapticFeedback
import com.junkfood.seal.ui.theme.ArtworkShape
import com.junkfood.seal.ui.theme.PreviewThemeLight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.TextButton

/**
 * At-a-glance summary of the library, so the home screen opens with a sense of state
 * rather than a bare list. Sits under the app bar and scrolls with the content.
 */
@Composable
fun LibraryHeader(
    modifier: Modifier = Modifier,
    playlistCount: Int,
    trackCount: Int,
    lastSynced: Long,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Your library",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        val pieces = buildList {
            add(if (playlistCount == 1) "1 playlist" else "$playlistCount playlists")
            if (trackCount > 0) add("$trackCount tracks")
            if (lastSynced > 0) add("synced ${formatRelativeTime(lastSynced)}")
        }
        Text(
            text = pieces.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Per-playlist sync state, shown as a small chip on each row. */
enum class PlaylistSyncState { Synced, Syncing, Pending, NeverSynced }

@Composable
fun PlaylistRow(
    modifier: Modifier = Modifier,
    playlist: PlaylistEntry,
    syncState: PlaylistSyncState,
    onClick: () -> Unit = {},
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistArtwork(url = playlist.thumbnailUrl, title = playlist.title)

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                val meta = buildList {
                    playlist.channelTitle?.let { add(it) }
                    if (playlist.videoCount > 0) add("${playlist.videoCount} videos")
                }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                SyncStateChip(state = syncState, lastSynced = playlist.lastSynced)
            }
        }
    }
}

@Composable
private fun PlaylistArtwork(url: String?, title: String) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(ArtworkShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Small status pill. Color carries the meaning, the label spells it out. */
@Composable
private fun SyncStateChip(state: PlaylistSyncState, lastSynced: Long) {
    val (label, dotColor) = when (state) {
        PlaylistSyncState.Syncing ->
            "Syncing now" to MaterialTheme.colorScheme.primary

        PlaylistSyncState.Synced ->
            "Synced ${formatRelativeTime(lastSynced)}" to MaterialTheme.colorScheme.primary

        PlaylistSyncState.Pending ->
            "Waiting to sync" to MaterialTheme.colorScheme.tertiary

        PlaylistSyncState.NeverSynced ->
            "Never synced" to MaterialTheme.colorScheme.outline
    }
    val animatedDot by animateColorAsState(dotColor, label = "syncDotColor")

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(animatedDot),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Swipe-to-remove wrapper for a playlist row. Deleting used to live behind a three-dot menu
 * and happened instantly with no way back; a swipe plus an undo snackbar is both quicker
 * and safer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToRemove(
    modifier: Modifier = Modifier,
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val dismissState = rememberSwipeToDismissBoxState(
        // Require a decisive swipe: the rows are tall and easy to brush past.
        positionalThreshold = { distance -> distance * 0.55f },
    )

    // Fire once when the swipe settles, and haptically confirm it.
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            view.slightHapticFeedback()
            onRemove()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            val active = dismissState.targetValue != SwipeToDismissBoxValue.Settled
            val background by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                label = "swipeBackground",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .background(background)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd)
                    Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        content = { content() },
    )
}

/**
 * First-run empty state. Replaces two lines of grey text with something that explains what
 * the app is for and offers the action directly, instead of pointing at a button.
 */
@Composable
fun EmptyLibraryState(
    modifier: Modifier = Modifier,
    onAddFromChannel: () -> Unit,
    onAddByUrl: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.LibraryMusic,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Nothing to sync yet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Add a YouTube playlist and SealSync keeps its audio mirrored to your " +
                    "folder — new videos download, removed ones disappear.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        // Pulling from your own channel is the usual way in, so it gets the filled button;
        // the URL path stays available underneath for anything else.
        Button(onClick = onAddFromChannel) {
            Icon(
                Icons.Outlined.Subscriptions,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(text = "Add from your channel", modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onAddByUrl) {
            Text(text = "Add by URL instead")
        }
    }
}

/**
 * A prompt shown when the YouTube API key is missing. Previously this only surfaced as a
 * toast after tapping Sync, so a fresh install looked broken for no visible reason.
 */
@Composable
fun MissingApiKeyBanner(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Add your YouTube API key",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = "Syncing needs it. Tap to open Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/** Relative timestamps read better than dates for something synced multiple times a day. */
fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> "${diff / 86_400_000}d ago"
        else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
    }
}

@Preview
@Composable
private fun PlaylistRowPreview() {
    PreviewThemeLight {
        Column {
            PlaylistRow(
                playlist = PlaylistEntry(
                    id = 1,
                    title = "Late Night Ambient",
                    url = "",
                    channelTitle = "Chillhop Music",
                    videoCount = 42,
                    lastSynced = System.currentTimeMillis() - 3_600_000,
                ),
                syncState = PlaylistSyncState.Synced,
            )
            Spacer(Modifier.height(8.dp))
            PlaylistRow(
                playlist = PlaylistEntry(id = 2, title = "New Finds", url = "", videoCount = 7),
                syncState = PlaylistSyncState.NeverSynced,
            )
        }
    }
}

@Preview
@Composable
private fun EmptyStatePreview() {
    PreviewThemeLight { EmptyLibraryState(onAddFromChannel = {}, onAddByUrl = {}) }
}
