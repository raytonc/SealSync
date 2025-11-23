package com.junkfood.seal

import android.annotation.SuppressLint
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
import android.util.Log
import androidx.core.content.getSystemService
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.google.android.material.color.DynamicColors
import com.junkfood.seal.ui.common.AudioThumbnailFetcher
import com.junkfood.seal.ui.common.AudioThumbnailKeyer
import com.junkfood.seal.ui.page.settings.general.Directory
import com.junkfood.seal.util.AUDIO_DIRECTORY
import com.junkfood.seal.util.AUDIO_DIRECTORY_URI
import com.junkfood.seal.util.COMMAND_DIRECTORY
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.FileUtil.createEmptyFile
import com.junkfood.seal.util.FileUtil.getCookiesFile
import com.junkfood.seal.util.FileUtil.getExternalDownloadDirectory
import com.junkfood.seal.util.FileUtil.getExternalPrivateDownloadDirectory
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.UpdateUtil
import com.junkfood.seal.util.VIDEO_DIRECTORY
import com.junkfood.seal.util.YT_DLP_VERSION
import com.tencent.mmkv.MMKV
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

        clipboard = getSystemService()!!
        connectivityManager = getSystemService()!!

        applicationScope.launch((Dispatchers.IO)) {
            try {
                YoutubeDL.init(this@App)
                FFmpeg.init(this@App)
                Aria2c.init(this@App)
                DownloadUtil.getCookiesContentFromDatabase().getOrNull()?.let {
                    FileUtil.writeContentToFile(it, getCookiesFile())
                }
                UpdateUtil.deleteOutdatedApk()
            } catch (th: Throwable) {
                withContext(Dispatchers.Main) {
                    startCrashReportActivity(th)
                }
            }
        }

        videoDownloadDir = VIDEO_DIRECTORY.getString(
            getExternalDownloadDirectory().absolutePath
        )

        audioDownloadDir = AUDIO_DIRECTORY.getString(File(videoDownloadDir, "Audio").absolutePath)
        if (!PreferenceUtil.containsKey(COMMAND_DIRECTORY)) {
            COMMAND_DIRECTORY.updateString(videoDownloadDir)
        }
        if (Build.VERSION.SDK_INT >= 26) NotificationUtil.createNotificationChannel()


        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            startCrashReportActivity(e)
        }
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
        lateinit var videoDownloadDir: String
        lateinit var audioDownloadDir: String
        lateinit var applicationScope: CoroutineScope
        lateinit var connectivityManager: ConnectivityManager
        lateinit var packageInfo: PackageInfo

        var isServiceRunning = false
        var downloadService: DownloadService? = null

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                @Suppress("UNCHECKED_CAST")
                val binder = service as DownloadService.DownloadServiceBinder
                downloadService = binder.getService()
                isServiceRunning = true
            }

            override fun onServiceDisconnected(arg0: ComponentName) {
                downloadService = null
            }
        }

        fun startService() {
            if (isServiceRunning) return
            Intent(context.applicationContext, DownloadService::class.java).also { intent ->
                context.bindService(intent, connection, BIND_AUTO_CREATE)
            }
        }

        fun stopService() {
            if (!isServiceRunning) return
            try {
                isServiceRunning = false
                context.applicationContext.run {
                    unbindService(connection)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }


        val privateDownloadDir: String
            get() = getExternalPrivateDownloadDirectory().run {
                createEmptyFile(".nomedia")
                absolutePath
            }

        fun updateDownloadDir(uri: Uri, directoryType: Directory) {
            when (directoryType) {
                Directory.AUDIO -> {
                    Log.d(TAG, "updateDownloadDir: Received URI: $uri")

                    // Persist URI permission so we can access it later
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        Log.d(TAG, "updateDownloadDir: Successfully persisted URI permission")
                    } catch (e: Exception) {
                        Log.e(TAG, "updateDownloadDir: Failed to persist URI permission", e)
                    }

                    // Store the URI (primary method for SAF)
                    PreferenceUtil.encodeString(AUDIO_DIRECTORY_URI, uri.toString())
                    Log.d(TAG, "updateDownloadDir: Stored URI: $uri")

                    // Also store the path (for display purposes and legacy compatibility)
                    val path = FileUtil.getRealPath(uri)
                    Log.d(TAG, "updateDownloadDir: Converted path: $path")
                    audioDownloadDir = path
                    PreferenceUtil.encodeString(AUDIO_DIRECTORY, path)
                }
            }
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