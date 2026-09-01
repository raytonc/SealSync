package com.junkfood.seal

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.content.getSystemService
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.google.android.material.color.DynamicColors
import com.junkfood.seal.ui.common.AudioThumbnailFetcher
import com.junkfood.seal.ui.common.AudioThumbnailKeyer
import com.junkfood.seal.util.AUDIO_DIRECTORY
import com.junkfood.seal.util.AutoSyncWorker
import com.junkfood.seal.util.AUDIO_DIRECTORY_URI
import com.junkfood.seal.util.SETUP_COMPLETED
import com.junkfood.seal.util.YOUTUBE_API_KEY
import com.junkfood.seal.util.YOUTUBE_CHANNEL_HANDLE
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.FileUtil.getExternalDownloadDirectory
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.UpdateUtil
import com.junkfood.seal.util.YT_DLP_VERSION
import com.tencent.mmkv.MMKV
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

@HiltAndroidApp
class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        context = applicationContext
        packageInfo = packageManager.run {
            if (Build.VERSION.SDK_INT >= 33) getPackageInfo(
                packageName, PackageManager.PackageInfoFlags.of(0)
            ) else getPackageInfo(packageName, 0)
        }
        applicationScope = CoroutineScope(SupervisorJob())
        DynamicColors.applyToActivitiesIfAvailable(this)

        // CrashReportActivity runs in its own process (see the manifest) so it can outlive
        // the kill at the end of the uncaught-exception handler. That process gets its own
        // Application instance and so runs this method again -- but all it has to render
        // is a stack trace from an Intent extra plus the theme preferences MMKV has just
        // loaded. Everything below initialises the *app*: yt-dlp binaries, the WorkManager
        // schedule, notification channels. Doing any of it here would duplicate work at
        // best and, at worst, race the main process that is in the middle of dying.
        if (isCrashReportProcess()) return

        clipboard = getSystemService()!!
        connectivityManager = getSystemService()!!

        applicationScope.launch((Dispatchers.IO)) {
            try {
                YoutubeDL.init(this@App)
                FFmpeg.init(this@App)
                Aria2c.init(this@App)
                // Signalled the moment the binaries are usable, and before
                // deleteOutdatedApk, which is housekeeping no download depends on.
                binariesReady.complete(Unit)
                UpdateUtil.deleteOutdatedApk()
            } catch (th: Throwable) {
                // Completed exceptionally rather than left hanging. A failed init means
                // yt-dlp cannot run at all, so a waiter must not be released normally --
                // it would only go on to fail every item. But it does have to be released:
                // an uncompleted deferred makes every waiter sit out its own timeout and
                // then guess, and AutoSyncWorker's guess was "retry later", which
                // WorkManager honours by re-running the worker forever. Each of those runs
                // burns a foreground slot and a minute of ongoing notification on a
                // process whose binaries will never work.
                //
                // The distinction matters because the crash report is not the whole story
                // on this path. It launches once per process, and a WorkManager-woken cold
                // start has no UI in front of it -- so on those the exception here is the
                // only signal anything went wrong.
                binariesReady.completeExceptionally(th)
                withContext(Dispatchers.Main) {
                    startCrashReportActivity(th)
                }
            }
        }

        audioDownloadDir = AUDIO_DIRECTORY.getString(getExternalDownloadDirectory().absolutePath)

        // Migration logic: If user has already configured settings before setup flow was added,
        // mark setup as completed to avoid showing setup screen to existing users
        if (!PreferenceUtil.containsKey(SETUP_COMPLETED)) {
            val hasAudioFolder = AUDIO_DIRECTORY_URI.getString().isNotEmpty()
            val hasApiKey = YOUTUBE_API_KEY.getString().isNotEmpty()
            val hasChannelHandle = YOUTUBE_CHANNEL_HANDLE.getString().isNotEmpty()

            // If all three are configured, this is an existing user - mark setup as completed
            if (hasAudioFolder && hasApiKey && hasChannelHandle) {
                SETUP_COMPLETED.updateBoolean(true)
            }
        }

        if (Build.VERSION.SDK_INT >= 26) NotificationUtil.createNotificationChannel()

        // Reapply the scheduled sync on every start. WorkManager persists its own schedule
        // across reboots (given RECEIVE_BOOT_COMPLETED) and updates, so this is not what
        // keeps it alive -- it is what keeps it *correct*: the constraints are derived from
        // preferences that can change while no scheduling code runs, and the enqueue uses
        // UPDATE, so an unchanged request is inert and a changed one is rewritten in place.
        //
        // Off the main thread, and guarded: getInstance opens the WorkDatabase and enqueue
        // writes to disk, which is tens of milliseconds of blocked startup on a low-end
        // device -- every launch, usually to reapply a schedule that has not changed.
        // Nothing here waits on it, and it needs only the preferences MMKV has already
        // loaded. [AutoSyncWorker.applySettingsChange] does both for us.
        AutoSyncWorker.applySettingsChange(this)


        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            startCrashReportActivity(e)
            // Installing a handler replaces the framework's KillApplicationHandler, which
            // is what normally ends the process on an uncaught exception. Without this the
            // crashing thread dies but the process lives on: the main looper is gone while
            // [applicationScope] -- a SupervisorJob, unaffected by the crash -- keeps a
            // sync downloading, keeps posting to the foreground notification, and keeps
            // dispatching toasts to a Dispatchers.Main whose thread no longer exists. The
            // user is left with a frozen MainActivity, a stuck ongoing notification and no
            // way out but force-stop. The report activity is a separate process-level
            // task and has already been launched, so it survives this.
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }

    /**
     * True in the `:crash` process that hosts [CrashReportActivity].
     *
     * Android names a non-default process `<package>:<suffix>`, so the check is against
     * the manifest's `android:process=":crash"`. Application.getProcessName covers API
     * 28+; below that the name is read from the running-process list, and a null answer
     * falls back to "this is the main process", which is both the common case and the
     * safe one -- it initialises too much rather than too little.
     */
    private fun isCrashReportProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            val pid = Process.myPid()
            getSystemService<ActivityManager>()
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }
        return name == "$packageName:crash"
    }

    private fun startCrashReportActivity(th: Throwable) {
        th.printStackTrace()
        startActivity(
            Intent(
                this, CrashReportActivity::class.java
            ).setAction("$packageName.error_report").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("error_report", getVersionReport() + "\n" + th.stackTraceToString())
            })
    }

    /**
     * Configure Coil ImageLoader with custom fetcher for audio thumbnails
     * This enables lazy loading of embedded thumbnails from audio files
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                // Add custom keyer for audio URIs
                add(AudioThumbnailKeyer())
                // Add custom fetcher for extracting embedded thumbnails
                add(AudioThumbnailFetcher.Factory())
            }
            .build()
    }

    companion object {
        lateinit var clipboard: ClipboardManager
        lateinit var audioDownloadDir: String
        lateinit var applicationScope: CoroutineScope
        lateinit var connectivityManager: ConnectivityManager
        lateinit var packageInfo: PackageInfo

        /**
         * Whether the service is currently bound.
         *
         * Atomic, and flipped with compare-and-set below, because the calls that read it
         * are not confined to one thread: [Downloader] drives them from its state collector
         * on the application scope while the UI can trigger a sync from the main thread. A
         * plain `var` let two callers both observe `false` and each bind, which leaks a
         * binding the single [stopService] can never undo.
         */
        /**
         * Completes once yt-dlp, FFmpeg and aria2c have finished unpacking.
         *
         * [YoutubeDL.init] and friends run asynchronously in [onCreate] -- they unpack
         * binaries and take seconds on a first run after an update -- while a download
         * calls straight into `YoutubeDL.getInstance().execute`. Started from the UI that
         * race barely exists: a person has to reach the button, by which time the unpack
         * is long done. A scheduled sync has no such gap. WorkManager wakes a cold process
         * and the worker starts within milliseconds of `onCreate`, so every item would hit
         * a half-unpacked yt-dlp, fail with an error no retry pattern recognises, and
         * settle as permanently Failed on its first attempt.
         *
         * A [CompletableDeferred] rather than a boolean, so a caller can *wait* for it
         * instead of giving up: the worker has a foreground slot and minutes of runway,
         * and the right answer to "not ready yet" is a few seconds' patience.
         *
         * Completes exceptionally if the init throws, so `await()` distinguishes the three
         * states a caller actually needs to tell apart: ready, still unpacking (the call
         * suspends), and will never be ready (it throws). That last one is permanent for
         * the life of the process, and a caller that can reschedule itself -- the worker --
         * must not treat it as "try again in a while".
         */
        val binariesReady = CompletableDeferred<Unit>()

        private val isServiceRunning = AtomicBoolean(false)
        var downloadService: DownloadService? = null
            private set

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                downloadService = (service as DownloadService.DownloadServiceBinder).getService()
            }

            override fun onServiceDisconnected(arg0: ComponentName) {
                downloadService = null
            }
        }

        /**
         * Set while a [com.junkfood.seal.util.AutoSyncWorker] is running the sync.
         *
         * A scheduled sync is already foreground work: the worker holds the foreground
         * slot itself, with its own ongoing notification, because a process woken by
         * WorkManager in the background is not allowed to call `startForegroundService`
         * at all from Android 12 onward -- it throws
         * `ForegroundServiceStartNotAllowedException`. So [Downloader] must not also bind
         * the service for that run: the bind would crash the sync outright, and if it
         * somehow succeeded it would post a second ongoing notification for the same work.
         *
         * Atomic for the same reason [isServiceRunning] is -- the worker sets it from its
         * own coroutine while [Downloader]'s state collector reads it from the application
         * scope.
         */
        val isWorkerForeground = AtomicBoolean(false)

        /** Binds the download service so a sync survives the app being backgrounded. */
        fun startService() {
            // The worker is already the foreground host for this run. See above.
            if (isWorkerForeground.get()) return
            if (!isServiceRunning.compareAndSet(false, true)) return
            val appContext = context.applicationContext
            Intent(appContext, DownloadService::class.java).also { intent ->
                // The service goes foreground in onCreate, so startForegroundService is safe here.
                //
                // Guarded all the same. From Android 12 this throws
                // ForegroundServiceStartNotAllowedException when the process is already in
                // the background, and a sync started from the UI and then immediately
                // backgrounded -- tap Sync or Retry, swipe home -- can land exactly there.
                // Uncaught it reaches the default handler installed in onCreate and takes
                // the app down with a crash report. The run itself lives on
                // applicationScope and continues either way; what is lost is the service
                // keeping the process alive, which is strictly better than a crash.
                val started = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appContext.startForegroundService(intent)
                    } else {
                        appContext.startService(intent)
                    }
                }.onFailure {
                    Log.e(TAG, "startService: could not start the download service", it)
                    // Rolled back so a later sync, started while in the foreground, is not
                    // locked out by a flag claiming a service that never started.
                    isServiceRunning.set(false)
                }.isSuccess

                if (started) appContext.bindService(intent, connection, BIND_AUTO_CREATE)
            }
        }

        fun stopService() {
            if (!isServiceRunning.compareAndSet(true, false)) return
            downloadService = null
            runCatching { context.applicationContext.unbindService(connection) }
                .onFailure { it.printStackTrace() }
        }

        /**
         * Records the folder the user picked for audio. The SAF tree URI is what actually
         * gets read and written; the resolved filesystem path is kept alongside it for
         * display and for the legacy File-API scan path.
         */
        fun updateDownloadDir(uri: Uri) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onFailure { Log.e(TAG, "updateDownloadDir: could not persist URI permission", it) }

            PreferenceUtil.encodeString(AUDIO_DIRECTORY_URI, uri.toString())
            val path = FileUtil.getRealPath(uri)
            Log.d(TAG, "updateDownloadDir: $uri -> $path")
            audioDownloadDir = path
            PreferenceUtil.encodeString(AUDIO_DIRECTORY, path)
        }

        private const val TAG = "App"

        fun getVersionReport(): String {
            val versionName = packageInfo.versionName
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            val release = if (Build.VERSION.SDK_INT >= 30) {
                Build.VERSION.RELEASE_OR_CODENAME
            } else {
                Build.VERSION.RELEASE
            }
            return StringBuilder().append("App version: $versionName ($versionCode)\n")
                .append("Device information: Android $release (API ${Build.VERSION.SDK_INT})\n")
                .append("Supported ABIs: ${Build.SUPPORTED_ABIS.contentToString()}\n")
                .append("Yt-dlp version: ${YT_DLP_VERSION.getString()}\n").toString()
        }

        fun isFDroidBuild(): Boolean = packageInfo.versionName.contains("F-Droid")

        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context
    }
}
