package com.junkfood.seal.util

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import com.junkfood.seal.App.Companion.context
import java.io.File

/**
 * Rewrites the album and track-number tags on already-downloaded audio.
 *
 * Downloads get their tags from yt-dlp at fetch time, but a file's correct track number is
 * a property of the playlist, not of the video: reordering a playlist changes the position
 * of files that are already on disk and that a sync would otherwise never touch again.
 * This is how those catch up, and it is also what backfills a library downloaded before
 * any track numbers were written at all.
 */
object TagUtil {

    private const val TAG = "TagUtil"

    /**
     * ffmpeg is shipped as `libffmpeg.so` in the APK's native library directory.
     *
     * It is a real executable given a `lib*.so` name so that Android will extract it and
     * mark it executable the way it does any native library -- a plain binary in assets
     * would land without the execute bit on modern releases. The system unpacks it per ABI
     * into [android.content.pm.ApplicationInfo.nativeLibraryDir], which is where it has to
     * be read from.
     *
     * It is deliberately *not* looked up under the package tree
     * [com.yausername.ffmpeg.FFmpeg.init] unpacks: that archive holds only `usr/lib`, the
     * shared objects ffmpeg links against, and contains no executable at all. Pointing at
     * a `usr/bin` inside it finds nothing, and every retag then fails the `canExecute`
     * check and silently does nothing -- which is exactly what shipped in 1.16.0.
     *
     * The libraries in that tree are still needed at run time, which is what
     * [ffmpegEnvironment] puts on the loader path.
     */
    private val ffmpegBinary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    /** Where [com.yausername.ffmpeg.FFmpeg.init] unpacks the shared objects ffmpeg needs. */
    private val ffmpegLibDir: File
        get() = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")

    /**
     * Retags [file] in place, as far as the caller can tell.
     *
     * ffmpeg cannot actually edit a container's metadata where it sits -- it demuxes to a
     * new file -- so this writes a sibling and swaps it in only once ffmpeg has exited
     * cleanly and produced something non-empty. A crash, a full disk or a killed process
     * therefore leaves the original untouched rather than truncated, which matters because
     * the file being rewritten is the user's only copy of a track that may no longer be
     * downloadable.
     *
     * `-c copy` keeps the audio stream and the embedded artwork exactly as they are: this
     * is a container rewrite, not a re-encode, so it neither loses quality nor costs the
     * CPU an encode would. The cost is one full read and one full write of the file.
     *
     * Returns true only when the swap happened.
     */
    fun retag(file: File, album: String, trackNumber: Int): Boolean {
        val binary = ffmpegBinary
        if (!binary.canExecute()) {
            Log.e(
                TAG,
                "retag: ffmpeg is not executable at ${binary.absolutePath} " +
                        "(exists=${binary.exists()}) -- no file can be retagged"
            )
            return false
        }
        if (!file.isFile) {
            Log.w(TAG, "retag: ${file.name} is gone")
            return false
        }

        // Same directory as the target: a rename across filesystems is not atomic and the
        // cache dir is not guaranteed to be on the same mount as the music folder.
        val output = File(file.parentFile, "$RETAG_TEMP_PREFIX${file.name}")
        // A previous run killed mid-write can leave this behind, and ffmpeg refuses to
        // overwrite silently.
        output.delete()

        return try {
            val builder = ProcessBuilder(
                binary.absolutePath,
                "-y",
                "-i", file.absolutePath,
                "-map", "0",
                "-c", "copy",
                // Both spellings are written because the tag's name depends on the
                // container ffmpeg is muxing: "track" is what ID3 and Vorbis comments take,
                // "TRACKNUMBER" is what an Ogg/Opus stream expects. Writing the wrong one
                // for a container is ignored rather than an error, so writing both is what
                // makes this work across every extension in [AUDIO_EXTENSIONS].
                "-metadata", "album=$album",
                "-metadata", "track=$trackNumber",
                "-metadata", "TRACKNUMBER=$trackNumber",
                output.absolutePath,
            ).redirectErrorStream(true)
            // ffmpeg links against the shared objects unpacked under the package tree, and
            // finds none of them without this: it exits immediately with a loader error
            // rather than doing anything. The native library dir is included too, since
            // that is where the APK's own copies live.
            builder.environment()["LD_LIBRARY_PATH"] = listOf(
                ffmpegLibDir.absolutePath,
                context.applicationInfo.nativeLibraryDir,
            ).joinToString(":")
            val process = builder.start()

            // The output has to be drained even though nothing reads it: ffmpeg is chatty
            // on stderr, and a full pipe buffer blocks the process forever rather than
            // failing, which would hang the sync instead of just this one file.
            val log = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()

            if (exit != 0 || !output.isFile || output.length() == 0L) {
                Log.e(TAG, "retag: ffmpeg failed for ${file.name} (exit $exit): ${log.takeLast(500)}")
                output.delete()
                return false
            }

            if (!output.renameTo(file)) {
                Log.e(TAG, "retag: could not replace ${file.name}")
                output.delete()
                return false
            }

            Log.d(TAG, "retag: ${file.name} -> album='$album' track=$trackNumber")
            true
        } catch (th: Throwable) {
            Log.e(TAG, "retag: failed for ${file.name}", th)
            output.delete()
            false
        }
    }

    /**
     * Tells the media scanner about files this pass rewrote.
     *
     * Player apps read tags from the system media store rather than from the files, so
     * without this the retag is invisible until something else triggers a scan -- the
     * sorting would look exactly as broken as it did before. Note that a player keeping its
     * own library cache (Musicolet does) may still need a rescan from inside that app.
     */
    fun notifyMediaScanner(context: Context, paths: Collection<String>) {
        if (paths.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        }.onFailure { Log.e(TAG, "notifyMediaScanner: failed", it) }
    }
}
