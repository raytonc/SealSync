package com.junkfood.seal.ui.page

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.CookieManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.junkfood.seal.Downloader
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.LocalWindowWidthState
import com.junkfood.seal.ui.common.Route
import com.junkfood.seal.ui.common.animatedComposable
import com.junkfood.seal.ui.common.animatedComposableVariant
import com.junkfood.seal.ui.common.arg
import com.junkfood.seal.ui.common.id
import com.junkfood.seal.ui.common.slideInVerticallyComposable
import com.junkfood.seal.ui.page.download.DownloadPage
import com.junkfood.seal.ui.page.download.DownloadViewModel
import com.junkfood.seal.ui.page.settings.SettingsPage
import com.junkfood.seal.ui.page.settings.about.AboutPage
import com.junkfood.seal.ui.page.settings.about.CreditsPage
import com.junkfood.seal.ui.page.settings.general.GeneralDownloadPreferences
import com.junkfood.seal.ui.page.settings.network.CookieProfilePage
import com.junkfood.seal.ui.page.settings.network.CookiesViewModel
import com.junkfood.seal.ui.page.settings.network.WebViewPage
import com.junkfood.seal.ui.page.videolist.VideoListPage
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getLong
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.UpdateUtil
import com.junkfood.seal.util.YT_DLP_AUTO_UPDATE
import com.junkfood.seal.util.YT_DLP_UPDATE_INTERVAL
import com.junkfood.seal.util.YT_DLP_UPDATE_TIME
import com.junkfood.seal.util.YT_DLP_VERSION
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "HomeEntry"

@Composable
fun HomeEntry(
    downloadViewModel: DownloadViewModel,
    cookiesViewModel: CookiesViewModel,
    isUrlShared: Boolean
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var currentDownloadStatus by remember { mutableStateOf(UpdateUtil.DownloadStatus.NotYet as UpdateUtil.DownloadStatus) }
    val scope = rememberCoroutineScope()
    var updateJob: Job? = null
    var latestRelease by remember { mutableStateOf(UpdateUtil.LatestRelease()) }
    val settings =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            UpdateUtil.installLatestApk()
        }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        if (result) {
            UpdateUtil.installLatestApk()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls())
                    settings.launch(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                else
                    UpdateUtil.installLatestApk()
            }
        }
    }

    val onNavigateBack: () -> Unit = {
        with(navController) {
            if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
                popBackStack()
            }
        }
    }

    if (isUrlShared) {
        if (navController.currentDestination?.route != Route.HOME) {
            navController.popBackStack(route = Route.HOME, inclusive = false, saveState = true)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {

        NavHost(
            modifier = Modifier
                .fillMaxWidth(
                    when (LocalWindowWidthState.current) {
                        WindowWidthSizeClass.Compact -> 1f
                        WindowWidthSizeClass.Expanded -> 0.5f
                        else -> 0.8f
                    }
                )
                .align(Alignment.Center),
            navController = navController,
            startDestination = Route.HOME
        ) {
            animatedComposable(Route.HOME) {
                DownloadPage(
                    navigateToDownloads = { navController.navigate(Route.DOWNLOADS) },
                    navigateToSettings = {
                        navController.navigate(Route.SETTINGS) {
                            launchSingleTop = true
                        }
                    },
                    downloadViewModel = downloadViewModel
                )
            }
            animatedComposable(Route.DOWNLOADS) { VideoListPage { onNavigateBack() } }
            settingsGraph(
                cookiesViewModel = cookiesViewModel,
                onNavigateBack = onNavigateBack,
                onNavigateTo = { route ->
                    navController.navigate(route = route) {
                        launchSingleTop = true
                    }
                }
            )

        }

        val downloaderState by Downloader.downloaderState.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            if (downloaderState !is Downloader.State.Idle) return@LaunchedEffect

            if (!YT_DLP_AUTO_UPDATE.getBoolean() && YT_DLP_VERSION.getString()
                    .isNotEmpty()
            ) return@LaunchedEffect

            if (!PreferenceUtil.isNetworkAvailableForDownload()) {
                return@LaunchedEffect
            }

            val lastUpdateTime = YT_DLP_UPDATE_TIME.getLong()
            val currentTime = System.currentTimeMillis()

            if (currentTime < lastUpdateTime + YT_DLP_UPDATE_INTERVAL.getLong()) {
                return@LaunchedEffect
            }

            runCatching {
                Downloader.updateState(state = Downloader.State.Updating)
                withContext(Dispatchers.IO) {
                    UpdateUtil.updateYtDlp()
                }
            }.onFailure {
                it.printStackTrace()
            }
            Downloader.updateState(state = Downloader.State.Idle)
        }

        LaunchedEffect(Unit) {
            if (!PreferenceUtil.isNetworkAvailableForDownload() || !PreferenceUtil.isAutoUpdateEnabled()
            )
                return@LaunchedEffect
            launch(Dispatchers.IO) {
                runCatching {
                    UpdateUtil.checkForUpdate()?.let {
                        latestRelease = it
                        showUpdateDialog = true
                    }
                }.onFailure {
                    it.printStackTrace()
                }
            }
        }

        if (showUpdateDialog) {
            UpdateDialogImpl(
                onDismissRequest = {
                    showUpdateDialog = false
                    updateJob?.cancel()
                },
                title = latestRelease.name.toString(),
                onConfirmUpdate = {
                    updateJob = scope.launch(Dispatchers.IO) {
                        runCatching {
                            UpdateUtil.downloadApk(latestRelease = latestRelease)
                                .collect { downloadStatus ->
                                    currentDownloadStatus = downloadStatus
                                    if (downloadStatus is UpdateUtil.DownloadStatus.Finished) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            launcher.launch(Manifest.permission.REQUEST_INSTALL_PACKAGES)
                                        }
                                    }
                                }
                        }.onFailure {
                            it.printStackTrace()
                            currentDownloadStatus = UpdateUtil.DownloadStatus.NotYet
                            ToastUtil.makeToastSuspend(context.getString(R.string.app_update_failed))
                            return@launch
                        }
                    }
                },
                releaseNote = latestRelease.body.toString(),
                downloadStatus = currentDownloadStatus
            )
        }
    }
}

fun NavGraphBuilder.settingsGraph(
    cookiesViewModel: CookiesViewModel,
    onNavigateBack: () -> Unit,
    onNavigateTo: (route: String) -> Unit
) {
    navigation(startDestination = Route.SETTINGS_PAGE, route = Route.SETTINGS) {
        animatedComposable(Route.SETTINGS_PAGE) {
            SettingsPage(
                onNavigateBack = onNavigateBack,
                onNavigateTo = onNavigateTo
            )
        }
        animatedComposable(Route.GENERAL_DOWNLOAD_PREFERENCES) {
            GeneralDownloadPreferences(
                onNavigateBack = { onNavigateBack() }
            )
        }
        animatedComposable(Route.ABOUT) {
            AboutPage(
                onNavigateBack = onNavigateBack,
                onNavigateToCreditsPage = { onNavigateTo(Route.CREDITS) }
            )
        }
        animatedComposable(Route.CREDITS) { CreditsPage(onNavigateBack) }
        animatedComposable(Route.COOKIE_PROFILE) {
            CookieProfilePage(
                cookiesViewModel = cookiesViewModel,
                navigateToCookieGeneratorPage = { onNavigateTo(Route.COOKIE_GENERATOR_WEBVIEW) },
            ) { onNavigateBack() }
        }
        animatedComposable(
            Route.COOKIE_GENERATOR_WEBVIEW
        ) {
            WebViewPage(cookiesViewModel) {
                onNavigateBack()
                CookieManager.getInstance().flush()
            }
        }
    }
}
