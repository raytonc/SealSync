package com.junkfood.seal.ui.page.queue

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.Downloader
import com.junkfood.seal.QueueSummary
import com.junkfood.seal.R
import com.junkfood.seal.TrackDownload
import com.junkfood.seal.ui.common.HapticFeedback.slightHapticFeedback
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.SmallTopAppBar
import com.junkfood.seal.ui.theme.PreviewThemeLight
import com.junkfood.seal.util.ToastUtil

/**
 * The download queue: every track in the current run and what it is doing.
 *
 * Downloads run several at a time, so the home screen's one-line card can only ever
 * summarise them -- naming one title and averaging the rest. This is where the run is
 * actually legible: what is transferring right now with its own bar and yt-dlp's own
 * speed/ETA line, what is still waiting, and what has settled.
 *
 * The rows outlive the run. A sync that failed one track out of twenty is the case worth
 * designing for, and that track's reason is gone by the time a toast has faded, so the
 * list stays put until the next sync replaces it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQueuePage(onNavigateBack: () -> Unit) {
    val queue by Downloader.queue.collectAsStateWithLifecycle()
    val summary by Downloader.queueSummary.collectAsStateWithLifecycle()
    val downloaderState by Downloader.downloaderState.collectAsStateWithLifecycle()
    val isSyncing = downloaderState is Downloader.State.DownloadingPlaylist

    val view = LocalView.current
    val clipboardManager = LocalClipboardManager.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SmallTopAppBar(
                titleText = stringResource(R.string.download_queue),
                navigationIcon = { BackButton(onNavigateBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Structured as if/else rather than an early return: a `return` out of a
            // composable lambda skips the group-end calls the compiler emitted for it and
            // leaves Compose's group stack unbalanced, which blows up in the next layout
            // pass rather than here.
            if (queue.isEmpty()) {
                EmptyQueueState()
            } else {
                // Only a live run has a total worth counting against. Once it ends the queue
                // holds nothing but the failures, so "3 of 3" with a full bar would be true
                // and useless -- the banner says what is actually left instead.
                if (isSyncing) {
                    QueueHeader(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        summary = summary,
                        onCancel = {
                            view.slightHapticFeedback()
                            Downloader.cancelSync()
                        },
                    )
                } else {
                    FailedRunBanner(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        // The failures themselves, not the whole queue. This branch covers
                        // every non-running state, and in the window where a retry has
                        // requeued its rows but the state change has not landed yet, the
                        // queue holds Queued rows that are emphatically not failures.
                        failedCount = summary.failed,
                        // A yt-dlp update also occupies the downloader, so a retry started
                        // during one is rejected. Better to show the button as unavailable
                        // than to let it be tapped for a toast explaining why it did not
                        // work -- and there is nothing to retry when the tally is empty.
                        enabled = downloaderState is Downloader.State.Idle && summary.failed > 0,
                        onRetry = {
                            view.slightHapticFeedback()
                            Downloader.retryFailedDownloads()
                        },
                    )
                }

                // Ordered by what the reader is looking for, not by the run's own order:
                // what is moving now, then what is coming, then the record of what happened.
                // Within each group the run's order is kept.
                //
                // Grouped in one pass and remembered against the queue: this recomposes on
                // every progress tick of every active download, and three independent
                // filters meant walking the whole run three times for each of them.
                val (active, waiting, finished) = remember(queue) { queue.groupByStatus() }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (active.isNotEmpty()) {
                        item(key = "h-active") { SectionHeader(stringResource(R.string.queue_section_active)) }
                        items(active, key = { "a-" + it.videoId }) { track ->
                            TrackRow(modifier = Modifier.animateItem(), track = track)
                        }
                    }
                    if (waiting.isNotEmpty()) {
                        item(key = "h-waiting") { SectionHeader(stringResource(R.string.queue_section_waiting)) }
                        items(waiting, key = { "w-" + it.videoId }) { track ->
                            TrackRow(modifier = Modifier.animateItem(), track = track)
                        }
                    }
                    if (finished.isNotEmpty()) {
                        item(key = "h-finished") {
                            SectionHeader(
                                stringResource(
                                    if (isSyncing) R.string.queue_section_finished
                                    else R.string.queue_section_failed
                                )
                            )
                        }
                        items(finished, key = { "f-" + it.videoId }) { track ->
                            TrackRow(
                                modifier = Modifier.animateItem(),
                                track = track,
                                onCopyError = { reason ->
                                    clipboardManager.setText(
                                        AnnotatedString(track.title + "\n" + reason)
                                    )
                                    ToastUtil.makeToast(R.string.error_copied)
                                },
                            )
                        }
                    }
                    item(key = "spacer") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/** The queue split into the three sections the list renders, in the run's own order. */
private data class QueueSections(
    val active: List<TrackDownload>,
    val waiting: List<TrackDownload>,
    val finished: List<TrackDownload>,
)

private fun List<TrackDownload>.groupByStatus(): QueueSections {
    val active = mutableListOf<TrackDownload>()
    val waiting = mutableListOf<TrackDownload>()
    val finished = mutableListOf<TrackDownload>()
    forEach { track ->
        when (track.status) {
            // A retrying item is between attempts, not finished with them -- it belongs
            // with the work still in flight rather than in the record of what happened.
            is TrackDownload.Status.Downloading, is TrackDownload.Status.Retrying -> active
            is TrackDownload.Status.Queued -> waiting
            else -> finished
        } += track
    }
    return QueueSections(active, waiting, finished)
}

/**
 * Run-wide state. The bar counts finished items whole and in-flight ones by their own
 * fraction, so it advances continuously even though three items move at once -- stepping
 * only as each track landed would make it look stalled for minutes at a time.
 */
@Composable
private fun QueueHeader(
    modifier: Modifier = Modifier,
    summary: QueueSummary,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.queue_progress,
                            summary.done + summary.failed + summary.skipped,
                            summary.total,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val detail = buildList {
                        if (summary.downloading > 0) {
                            add(
                                pluralStringResource(
                                    R.plurals.queue_downloading_now,
                                    summary.downloading,
                                    summary.downloading,
                                )
                            )
                        }
                        // Why the bar is standing still: a backing-off item contributes
                        // nothing to it, so without this line the run reads as stalled.
                        if (summary.retrying > 0) {
                            add(
                                pluralStringResource(
                                    R.plurals.queue_retrying_count,
                                    summary.retrying,
                                    summary.retrying,
                                )
                            )
                        }
                        if (summary.failed > 0) {
                            add(
                                pluralStringResource(
                                    R.plurals.queue_failed_count,
                                    summary.failed,
                                    summary.failed,
                                )
                            )
                        }
                    }.joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.queue_cancel_sync))
                }
            }

            Spacer(Modifier.height(12.dp))

            val animated by animateFloatAsState(
                targetValue = summary.progress.coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = 400),
                label = "queueOverallProgress",
            )
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f),
            )
        }
    }
}

/**
 * What a finished run left behind. Only failures survive the run, so this is not a
 * summary of the sync -- it is the list of things that still need attention, and it says
 * so rather than reporting counts the summary card on the home screen already gave.
 */
@Composable
private fun FailedRunBanner(
    modifier: Modifier = Modifier,
    failedCount: Int,
    enabled: Boolean = true,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = pluralStringResource(
                        R.plurals.queue_failed_title, failedCount, failedCount
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.queue_failed_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.width(8.dp))
            // The whole point of keeping these rows around. Retrying them costs a handful
            // of downloads; the alternative the user had was re-running the entire sync.
            FilledTonalButton(onClick = onRetry, enabled = enabled) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.queue_retry_failed))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * One track. The status line under the title is the row's real content: for a running
 * item it is yt-dlp's own progress line, verbatim, because speed and ETA are the two
 * things worth knowing and nothing the UI could synthesise beats them.
 */
@Composable
private fun TrackRow(
    modifier: Modifier = Modifier,
    track: TrackDownload,
    onCopyError: (String) -> Unit = {},
) {
    val status = track.status
    val (icon, tint) = status.iconAndTint()
    // Settled rows step back so the eye lands on what is still moving.
    val titleAlpha = if (track.isTerminal) 0.65f else 1f

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (status is TrackDownload.Status.Downloading) {
                    TrackProgressRing(progress = status.progress / 100f)
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = tint,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title.ifBlank { track.videoId },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = titleAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                when (status) {
                    is TrackDownload.Status.Downloading -> {
                        // Monospaced so the numbers stop jittering as they tick over.
                        Text(
                            text = status.line.ifBlank { "%.0f%%".format(status.progress) },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Says what went wrong and that it is coming back, so a row waiting
                    // out a backoff does not read as an unexplained pause.
                    is TrackDownload.Status.Retrying -> {
                        Text(
                            // status.attempt is the attempt that just failed, which is also
                            // what Failed.attempts counts -- so the two agree, and a row
                            // never announces an attempt that has not happened.
                            text = stringResource(
                                R.string.queue_status_retrying,
                                status.attempt,
                                status.reason,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    is TrackDownload.Status.Failed -> {
                        Text(
                            text = status.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = { onCopyError(status.reason) },
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.queue_copy_error),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }

                    else -> {
                        Text(
                            text = stringResource(status.labelRes()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A small determinate ring in place of the status icon while an item transfers -- the
 * per-item figure has to live somewhere, and the row's leading slot is already the column
 * the eye scans for state.
 */
@Composable
private fun TrackProgressRing(progress: Float) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300),
        label = "trackProgress",
    )
    androidx.compose.material3.CircularProgressIndicator(
        progress = { animated },
        modifier = Modifier.size(20.dp),
        strokeWidth = 2.5.dp,
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
    )
}

@Composable
private fun TrackDownload.Status.iconAndTint(): Pair<ImageVector, Color> = when (this) {
    is TrackDownload.Status.Queued ->
        Icons.Outlined.Schedule to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    // Never drawn: a running row shows the progress ring in this slot instead.
    is TrackDownload.Status.Downloading ->
        Icons.Outlined.Schedule to MaterialTheme.colorScheme.primary

    is TrackDownload.Status.Done ->
        Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary

    is TrackDownload.Status.Retrying ->
        Icons.Outlined.Refresh to MaterialTheme.colorScheme.tertiary

    is TrackDownload.Status.Failed ->
        Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error

    is TrackDownload.Status.Skipped ->
        Icons.Outlined.RemoveCircleOutline to
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
}

private fun TrackDownload.Status.labelRes(): Int = when (this) {
    is TrackDownload.Status.Queued -> R.string.queue_status_queued
    is TrackDownload.Status.Done -> R.string.queue_status_done
    is TrackDownload.Status.Skipped -> R.string.queue_status_skipped
    // All three carry their own line and never reach here.
    is TrackDownload.Status.Downloading,
    is TrackDownload.Status.Failed,
    is TrackDownload.Status.Retrying,
        -> R.string.queue_status_queued
}

@Composable
private fun EmptyQueueState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.queue_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.queue_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Preview
@Composable
private fun TrackRowPreview() {
    PreviewThemeLight {
        Column(Modifier.padding(20.dp)) {
            TrackRow(
                track = TrackDownload(
                    videoId = "a",
                    title = "Boards of Canada - Roygbiv",
                    status = TrackDownload.Status.Downloading(
                        progress = 42f,
                        line = "42.0% of 8.31MiB at 1.20MiB/s ETA 00:04",
                    ),
                )
            )
            TrackRow(
                track = TrackDownload(
                    videoId = "b",
                    title = "Aphex Twin - Xtal",
                    status = TrackDownload.Status.Queued,
                )
            )
            TrackRow(
                track = TrackDownload(
                    videoId = "c",
                    title = "Burial - Archangel",
                    status = TrackDownload.Status.Done,
                )
            )
            TrackRow(
                track = TrackDownload(
                    videoId = "d",
                    title = "Some Unavailable Track",
                    status = TrackDownload.Status.Failed("Video unavailable"),
                )
            )
        }
    }
}
