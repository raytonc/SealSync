package com.junkfood.seal

/**
 * One track's place in a sync run, as the queue screen shows it.
 *
 * A run seeds one of these per missing video up front, all [Status.Queued], and moves them
 * through their statuses in place rather than adding and removing entries. Downloads happen
 * several at a time, so "what is happening right now" is a set, not a single item, and the
 * rows that have already finished are the record of the run -- they stay until the next
 * sync starts. Replaces the old single-task DownloadTaskItem, whose fields beyond title and
 * progress were never read.
 */
data class TrackDownload(
    val videoId: String,
    val title: String,
    val status: Status = Status.Queued,
) {
    sealed interface Status {
        /** Waiting for a download slot. */
        data object Queued : Status

        /**
         * Running. [progress] is 0..100 as yt-dlp reports it, and [line] is its latest
         * status line -- speed and ETA, worth showing verbatim.
         */
        data class Downloading(val progress: Float = 0f, val line: String = "") : Status

        /**
         * Failed on a transient error and waiting out the backoff before trying again.
         *
         * Its own status rather than a flag on [Downloading]: a row in this state is not
         * making progress, so rendering it with a progress ring frozen at whatever
         * fraction it died at reads as a stalled download. [attempt] is the attempt that
         * just failed, 1-based, and [reason] is why -- both worth showing, since a run
         * that quietly retried three times and then succeeded should still be able to say
         * that it did.
         */
        data class Retrying(val attempt: Int, val reason: String) : Status

        data object Done : Status

        /**
         * [reason] is the throwable's message, shown on the row and copyable. [attempts]
         * is how many times the item was tried, so a row can distinguish a video that is
         * simply unavailable from one the network never managed to fetch.
         */
        data class Failed(val reason: String, val attempts: Int = 1) : Status

        /** Never started: the run was cancelled while this one was still queued. */
        data object Skipped : Status
    }

    val isTerminal: Boolean
        get() = status is Status.Done || status is Status.Failed || status is Status.Skipped
}

/** Run-wide tallies, for the queue header and the home card. */
data class QueueSummary(
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val downloading: Int = 0,
    val queued: Int = 0,
    /** Items waiting out a backoff before another attempt. Counted apart from [downloading]:
     * they are not transferring anything, and the header says so in its own words. */
    val retrying: Int = 0,
    /**
     * Overall completion, 0..1. Finished items count whole and in-flight ones count their
     * own fraction, so the bar advances continuously even though several items move at
     * once. Skipped items count as settled -- a cancelled run should not leave the bar
     * short of the end forever.
     */
    val progress: Float = 0f,
)
