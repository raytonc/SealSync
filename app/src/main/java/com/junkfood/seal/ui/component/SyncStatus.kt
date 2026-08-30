package com.junkfood.seal.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.junkfood.seal.ui.theme.PreviewThemeLight

/**
 * The live sync card. Sync is the whole point of the app and it used to happen invisibly —
 * the only feedback was a disabled button and a toast at the very end. This surfaces the
 * progress the downloader was already tracking: overall position in the run, and what is
 * being fetched right now.
 */
@Composable
fun SyncProgressCard(
    modifier: Modifier = Modifier,
    currentItem: Int,
    itemCount: Int,
    currentTitle: String,
    itemProgress: Float,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDownloadIcon(tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (itemCount > 0) "Syncing $currentItem of $itemCount"
                        else "Preparing sync",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (currentTitle.isNotBlank()) {
                        Text(
                            text = currentTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Cancel sync",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            val barModifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
            val barColor = MaterialTheme.colorScheme.onPrimaryContainer
            val trackColor = barColor.copy(alpha = 0.2f)

            if (itemCount > 0) {
                // Blend the current file's own progress into the bar so it advances smoothly
                // between items instead of stepping once per track.
                val overall =
                    ((currentItem - 1).coerceAtLeast(0) + itemProgress.coerceIn(0f, 1f)) / itemCount
                val animatedOverall by animateFloatAsState(
                    targetValue = overall,
                    animationSpec = tween(durationMillis = 400),
                    label = "syncOverallProgress",
                )
                LinearProgressIndicator(
                    progress = { animatedOverall },
                    modifier = barModifier,
                    color = barColor,
                    trackColor = trackColor,
                )
            } else {
                // Still enumerating playlists: no total to count against yet.
                LinearProgressIndicator(
                    modifier = barModifier,
                    color = barColor,
                    trackColor = trackColor,
                )
            }
        }
    }
}

/** A gentle breathing animation, so the card reads as active even on a slow download. */
@Composable
private fun PulsingDownloadIcon(tint: Color) {
    val transition = rememberInfiniteTransition(label = "syncPulse")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "syncPulseAlpha",
    )
    Icon(
        imageVector = Icons.Outlined.CloudDownload,
        contentDescription = null,
        modifier = Modifier.size(28.dp),
        tint = tint.copy(alpha = alpha),
    )
}

/**
 * Post-run summary. Replaces the toast that used to be the only signal a sync had finished,
 * and stays on screen long enough to actually read.
 */
@Composable
fun SyncSummaryCard(
    modifier: Modifier = Modifier,
    downloaded: Int,
    deleted: Int,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (downloaded == 0 && deleted == 0) "Already up to date"
                    else "Sync complete",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                val detail = buildList {
                    if (downloaded > 0) add("$downloaded added")
                    if (deleted > 0) add("$deleted removed")
                }.joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Preview
@Composable
private fun SyncProgressPreview() {
    PreviewThemeLight {
        SyncProgressCard(
            currentItem = 3,
            itemCount = 12,
            currentTitle = "Boards of Canada - Roygbiv",
            itemProgress = 0.4f,
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun SyncSummaryPreview() {
    PreviewThemeLight {
        SyncSummaryCard(downloaded = 4, deleted = 1, onDismiss = {})
    }
}
