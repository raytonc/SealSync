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
     * Bump when the tags written for an unchanged playlist position change, so that files
     * whose stored [com.junkfood.seal.database.objects.TrackTag] row is "correct" but whose
     * actual tags are not get rewritten once.
     *
     * 1 -> 2: 1.16.x stored rows for files it never managed to tag (every ffmpeg retag
     * failed) and for downloads whose album came out as "NA" (single-word playlist titles
     * were read by yt-dlp as a field name). Those rows say "done" about wrong files.
     */
    const val TAG_FORMAT_VERSION = 2

    /**
     * Tags are written with mutagen, run by the Python interpreter youtubedl-android ships.
     *
     * ffmpeg was used before and could not do this job. With `-map 0` it refuses to remux
     * the embedded cover art into Ogg ("Unsupported codec id") or MP4 ("Tag text
     * incompatible"), so every retag failed; and for Ogg/Opus it writes `-metadata` to the
     * container rather than to the audio stream, where players read it, so even a run that
     * exited cleanly would have changed nothing. mutagen is what yt-dlp itself uses to
     * embed thumbnails, it is already in the bundled site-packages, and it edits tags
     * without touching the audio or the artwork.
     *
     * The album arrives hex-encoded so no locale or argv decoding on the device can mangle
     * a non-ASCII playlist title on its way in.
     */
    private val RETAG_SCRIPT = """
        import sys
        import mutagen
        from mutagen.id3 import ID3, TALB, TRCK
        from mutagen.mp4 import MP4Tags
        path = sys.argv[1]
        album = bytes.fromhex(sys.argv[2]).decode("utf-8")
        track = int(sys.argv[3])
        f = mutagen.File(path)
        if f is None:
            sys.exit("unrecognised audio file")
        if f.tags is None:
            f.add_tags()
        t = f.tags
        if isinstance(t, MP4Tags):
            t["\xa9alb"] = [album]
            t["trkn"] = [(track, 0)]
        elif isinstance(t, ID3):
            t.setall("TALB", [TALB(encoding=3, text=[album])])
            t.setall("TRCK", [TRCK(encoding=3, text=[str(track)])])
        else:
            t["album"] = [album]
            t["tracknumber"] = [str(track)]
        f.save()
    """.trimIndent()

    /** Same locations [com.yausername.youtubedl_android.YoutubeDL.init] sets up. */
    private val pythonBinary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libpython.so")

    private val packagesDir: File
        get() = File(context.noBackupFilesDir, "youtubedl-android/packages")

    /**
     * Retags [file] in place, as far as the caller can tell.
     *
     * The tags are written into a copy beside the original, which is swapped in only once
     * Python has exited cleanly. A crash, a full disk or a killed process therefore leaves
     * the original untouched rather than half-written, which matters because the file
     * being rewritten may be the user's only copy of a track that is no longer
     * downloadable. The cost is one full read and one full write of the file.
     *
     * Returns true only when the swap happened.
     */
    fun retag(file: File, album: String, trackNumber: Int): Boolean {
        val binary = pythonBinary
        if (!binary.canExecute()) {
            Log.e(
                TAG,
                "retag: python is not executable at ${binary.absolutePath} " +
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

        return try {
            file.copyTo(output, overwrite = true)

            val pythonHome = File(packagesDir, "python/usr").absolutePath
            val builder = ProcessBuilder(
                binary.absolutePath,
                "-c", RETAG_SCRIPT,
                output.absolutePath,
                album.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) },
                trackNumber.toString(),
            ).redirectErrorStream(true)
            builder.environment().apply {
                put(
                    "LD_LIBRARY_PATH",
                    listOf("python", "ffmpeg", "aria2c")
                        .joinToString(":") { File(packagesDir, "$it/usr/lib").absolutePath }
                )
                put("PYTHONHOME", pythonHome)
                put("HOME", pythonHome)
                put("TMPDIR", context.cacheDir.absolutePath)
                put("PYTHONUTF8", "1")
            }
            val process = builder.start()

            // Drained even though nothing reads it on success: a full pipe buffer blocks
            // the process forever rather than failing, which would hang the sync.
            val log = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()

            if (exit != 0 || !output.isFile || output.length() == 0L) {
                Log.e(TAG, "retag: failed for ${file.name} (exit $exit): ${log.takeLast(500)}")
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
