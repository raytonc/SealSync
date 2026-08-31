package com.junkfood.seal.ui.page

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import com.junkfood.seal.Downloader
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.LocalWindowWidthState
import com.junkfood.seal.ui.common.Route
import com.junkfood.seal.ui.common.animatedComposable
import com.junkfood.seal.ui.page.download.DownloadPage
import com.junkfood.seal.ui.page.queue.DownloadQueuePage
import com.junkfood.seal.ui.page.settings.SettingsPage
import com.junkfood.seal.ui.page.settings.about.CreditsPage
import com.junkfood.seal.ui.page.setup.SetupFlowPage
import com.junkfood.seal.ui.page.videolist.VideoListPage
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.SETUP_COMPLETED
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getLong
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.UpdateUtil
import com.junkfood.seal.util.YT_DLP_AUTO_UPDATE
import com.junkfood.seal.util.YT_DLP_UPDATE_INTERVAL
import com.junkfood.seal.util.YT_DLP_UPDATE_TIME
import com.junkfood.seal.util.YT_DLP_VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "HomeEntry"

@Composable
fun HomeEntry() {
    // Check if setup is completed
    var isSetupCompleted by rememberSaveable { mutableStateOf(SETUP_COMPLETED.getBoolean()) }

    // If setup is not completed, show the setup flow
    if (!isSetupCompleted) {
        SetupFlowPage(
            onSetupComplete = {
                isSetupCompleted = true
            }
        )
        return
    }

    // Normal app flow if setup is completed
    val navController = rememberNavController()
    val context = LocalContext.current
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var currentDownloadStatus by remember { mutableStateOf(UpdateUtil.DownloadStatus.NotYet as UpdateUtil.DownloadStatus) }
    val scope = rememberCoroutineScope()
    // Held across recompositions: a plain local was reset to null on every recomposition,
    // so dismissing the dialog cancelled nothing and the download kept running.
    val updateJob = remember { mutableStateOf<Job?>(null) }
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
                    navigateToQueue = { navController.navigate(Route.QUEUE) },
                    navigateToSettings = {
                        navController.navigate(Route.SETTINGS) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            animatedComposable(Route.DOWNLOADS) { VideoListPage { onNavigateBack() } }
            animatedComposable(Route.QUEUE) { DownloadQueuePage { onNavigateBack() } }
            settingsGraph(
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

            // The claim, not the snapshot read above, is what decides whether this runs.
            // That read comes from a composition snapshot that can already be a frame
            // stale, so passing it proves nothing about the state now -- it stays only as
            // a cheap way to skip the preference and network checks in the common case.
            if (!Downloader.claimForUpdate()) return@LaunchedEffect

            // Released in a finally, because this effect is keyed on Unit and dies with
            // the composable. Leaving the screen mid-update cancels the coroutine, and a
            // release only on the normal path would strand the downloader in Updating for
            // the life of the process -- every later sync, scheduled or manual, rejected
            // by a claim nobody is holding any more.
            try {
                runCatching {
                    withContext(Dispatchers.IO) {
                        UpdateUtil.updateYtDlp()
                    }
                }.onFailure {
                    it.printStackTrace()
                }
            } finally {
                Downloader.releaseUpdate()
            }
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
                    updateJob.value?.cancel()
                },
                title = latestRelease.name.toString(),
                onConfirmUpdate = {
                    updateJob.value = scope.launch(Dispatchers.IO) {
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
                            ToastUtil.showToast(context.getString(R.string.app_update_failed))
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
        animatedComposable(Route.CREDITS) { CreditsPage(onNavigateBack) }
    }
}
