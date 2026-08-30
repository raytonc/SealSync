package com.junkfood.seal.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.junkfood.seal.Downloader
import com.junkfood.seal.QueueSummary
import com.junkfood.seal.R
import com.junkfood.seal.ui.theme.PreviewThemeLight

/**
 * The live sync card: a summary of the run, and the way into the queue screen.
 *
 * Deliberately not a per-track view. Several items download at once, so naming one of them
 * here would mean either picking a favourite -- whichever callback fired last, which makes
 * the title flicker between items -- or averaging them into a number that describes none.
 * Instead this reports the shape of the run (how far along, how many moving) and stacks the
 * few titles actually in flight; the queue screen is where each one gets its own progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncProgressCard(
    modifier: Modifier = Modifier,
    summary: QueueSummary,
    phase: Downloader.Phase,
    deleted: Int,
    activeTitles: List<String>,
    onCancel: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp,
        onClick = onOpenQueue,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDownloadIcon(tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    // Named by the step actually running. The count only means anything in
                    // the download phase -- the earlier ones have no per-item queue to count
                    // against, and rendering them all as one "preparing" placeholder made a
                    // minute of listing or deleting look like a stall.
                    val settled = summary.done + summary.failed + summary.skipped
                    // Resolved unconditionally, then chosen between: a composable call whose
                    // execution depends on the data leaves Compose's slot table mismatched
                    // when the phase changes under it.
                    val fetchingText = stringResource(R.string.sync_phase_fetching)
                    val scanningText = stringResource(R.string.sync_phase_scanning)
                    val deletingText = stringResource(R.string.sync_phase_deleting)
                    val preparingText = stringResource(R.string.sync_preparing)
                    val progressText =
                        stringResource(R.string.sync_progress_title, settled, summary.total)
                    val downloadingNowText = pluralStringResource(
                        R.plurals.queue_downloading_now,
                        summary.downloading,
                        summary.downloading,
                    )
                    val deletedText =
                        stringResource(R.string.sync_phase_deleted_detail, deleted)

                    Text(
                        text = when (phase) {
                            Downloader.Phase.Fetching -> fetchingText
                            Downloader.Phase.Scanning -> scanningText
                            Downloader.Phase.Deleting -> deletingText
                            Downloader.Phase.Downloading ->
                                if (summary.total > 0) progressText else preparingText
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    val detail = when {
                        phase == Downloader.Phase.Downloading && summary.downloading > 0 ->
                            downloadingNowText

                        // Deletions are the run's other real outcome, and the card said
                        // nothing about them at all: a sync that only removed files looked
                        // like it had done nothing.
                        deleted > 0 -> deletedText
                        else -> null
                    }
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        )
                    }
                }
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.queue_cancel_sync),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            // Every title in flight, not just one: three lines is a small price for the
            // card telling the truth about what the sync is doing.
            if (activeTitles.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    activeTitles.forEach { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            val barModifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
            val barColor = MaterialTheme.colorScheme.onPrimaryContainer
            val trackColor = barColor.copy(alpha = 0.2f)

            if (phase == Downloader.Phase.Downloading && summary.total > 0) {
                // The downloader already blends finished items with the fractional progress
                // of the ones in flight, so this is a straight read rather than arithmetic
                // over a single "current item" that no longer exists.
                val animatedOverall by animateFloatAsState(
                    targetValue = summary.progress.coerceIn(0f, 1f),
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
                // Listing, scanning or deleting: real work, but nothing whose end is known
                // in advance, so an indeterminate bar is the honest one to draw.
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
 *
 * Takes the whole [Downloader.SyncResult] rather than two counts, because two counts cannot
 * tell the run's outcomes apart. A sync aborted by an unreadable playlist and a sync that
 * genuinely had nothing to do both arrive as (0, 0), and this card used to render both as
 * "Already up to date" -- the worst possible reading of the first. Cancelled runs and failed
 * downloads were invisible for the same reason.
 */
@Composable
fun SyncSummaryCard(
    modifier: Modifier = Modifier,
    result: Downloader.SyncResult,
    onDismiss: () -> Unit,
) {
    val failed = result.error != null
    val changed = result.downloaded > 0 || result.deleted > 0

    // Every string resolved up front and unconditionally. `stringResource` is composable,
    // and selecting between calls inside a `when` or a `?:` makes which ones run depend on
    // the data, which is what leaves Compose's slot table mismatched across recompositions.
    // Picking between plain Strings afterwards is free.
    val titleFailed = stringResource(R.string.sync_result_failed)
    val titleCancelled = stringResource(R.string.sync_result_cancelled)
    val titleComplete = stringResource(R.string.sync_result_complete)
    val titleUpToDate = stringResource(R.string.sync_result_up_to_date)
    val addedText = stringResource(R.string.sync_result_added, result.downloaded)
    val removedText = stringResource(R.string.sync_result_removed, result.deleted)
    val failedText = stringResource(R.string.sync_result_failed_count, result.failed)
    val nothingChangedText = stringResource(R.string.sync_result_nothing_changed)

    val title = when {
        failed -> titleFailed
        result.cancelled -> titleCancelled
        changed || result.failed > 0 -> titleComplete
        // Only here is "up to date" actually true: the run compared everything and found
        // nothing to do.
        else -> titleUpToDate
    }

    // An abort explains itself; otherwise list what the run actually did, and say so
    // explicitly when a cancelled run managed nothing rather than leaving a bare title.
    val detail = when {
        failed -> result.error
        else -> listOfNotNull(
            addedText.takeIf { result.downloaded > 0 },
            removedText.takeIf { result.deleted > 0 },
            failedText.takeIf { result.failed > 0 },
        ).joinToString(" · ").takeIf { it.isNotEmpty() }
            ?: nothingChangedText.takeIf { result.cancelled }
    }

    // Failures are not a neutral outcome, so they do not get the same calm green surface as
    // a clean run.
    val container =
        if (failed) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer
    val onContainer =
        if (failed) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSecondaryContainer

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = container,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (failed) Icons.Outlined.ErrorOutline
                else Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = onContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = onContainer,
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer.copy(alpha = 0.8f),
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.sync_dismiss),
                    tint = onContainer,
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
            summary = QueueSummary(
                total = 12,
                done = 3,
                downloading = 3,
                queued = 6,
                progress = 0.31f,
            ),
            phase = Downloader.Phase.Downloading,
            deleted = 0,
            activeTitles = listOf(
                "Boards of Canada - Roygbiv",
                "Aphex Twin - Xtal",
                "Burial - Archangel",
            ),
            onCancel = {},
            onOpenQueue = {},
        )
    }
}

@Preview
@Composable
private fun SyncFailedPreview() {
    PreviewThemeLight {
        SyncSummaryCard(
            result = Downloader.SyncResult(
                downloaded = 0,
                deleted = 0,
                error = "Could not read your playlists, so nothing was changed",
            ),
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun SyncSummaryPreview() {
    PreviewThemeLight {
        SyncSummaryCard(
            result = Downloader.SyncResult(downloaded = 4, deleted = 1),
            onDismiss = {},
        )
    }
}
