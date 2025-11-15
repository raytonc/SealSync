package com.junkfood.seal.ui.page.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.EnergySavingsLeaf
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SettingsApplications
import androidx.compose.material.icons.rounded.SignalCellular4Bar
import androidx.compose.material.icons.rounded.SignalWifi4Bar
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.Route
import com.junkfood.seal.ui.common.intState
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.PreferencesHintCard
import com.junkfood.seal.ui.component.SettingItem
import com.junkfood.seal.ui.component.PreferenceItem
import com.junkfood.seal.ui.component.PreferenceSwitchWithDivider
import com.junkfood.seal.ui.component.PreferenceSwitch
import com.junkfood.seal.ui.component.PreferenceInfo
import com.junkfood.seal.ui.component.SettingTitle
import com.junkfood.seal.ui.component.SmallTopAppBar
import com.junkfood.seal.util.EXTRACT_AUDIO
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.ShortcutUtil
import com.junkfood.seal.util.CUSTOM_COMMAND
import com.junkfood.seal.util.RATE_LIMIT
import com.junkfood.seal.util.MAX_RATE
import com.junkfood.seal.util.CELLULAR_DOWNLOAD
import com.junkfood.seal.util.UpdateUtil
import com.junkfood.seal.util.ToastUtil
import com.yausername.youtubedl_android.YoutubeDL
import com.junkfood.seal.util.FileUtil
import androidx.compose.ui.platform.LocalUriHandler
import com.junkfood.seal.ui.page.settings.general.Directory

import androidx.compose.ui.platform.LocalUriHandler
@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    onNavigateBack: () -> Unit, onNavigateTo: (String) -> Unit
) {
    val context = LocalContext.current
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var showBatteryHint by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                !pm.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                false
            }
        )
    }
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    } else {
        Intent()
    }
    val isActivityAvailable: Boolean = if (Build.VERSION.SDK_INT < 23) false
    else if (Build.VERSION.SDK_INT < 33) context.packageManager.queryIntentActivities(
        intent,
        PackageManager.MATCH_ALL
    ).isNotEmpty()
    else context.packageManager.queryIntentActivities(
        intent,
        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong())
    ).isNotEmpty()


    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                showBatteryHint = !pm.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val uriHandler = LocalUriHandler.current

    // --- replicate a subset of GeneralDownloadPreferences state so we can render general settings inline
    val scope = rememberCoroutineScope()
    var showYtdlpDialog by remember { mutableStateOf(false) }
    var isUpdating by remember { mutableStateOf(false) }
    var ytdlpVersion = YoutubeDL.getInstance().version(context.applicationContext)
        ?: context.getString(R.string.ytdlp_update)
    var isCustomCommandEnabled by remember { mutableStateOf(CUSTOM_COMMAND.getBoolean()) }
    var audioDirectoryText by remember { mutableStateOf(App.audioDownloadDir) }
    var editingDirectory by remember { mutableStateOf(Directory.AUDIO) }
    var isRateLimitEnabled by remember { mutableStateOf(RATE_LIMIT.getBoolean()) }
    var maxDownloadRate by remember { mutableStateOf(MAX_RATE.getString()) }
    var showRateLimitDialog by remember { mutableStateOf(false) }
    var isDownloadWithCellularEnabled by remember { mutableStateOf(CELLULAR_DOWNLOAD.getBoolean()) }

    val dirLauncher = rememberLauncherForActivityResult(object : ActivityResultContracts.OpenDocumentTree() {
        override fun createIntent(context: Context, input: Uri?): Intent {
            return (super.createIntent(context, input)).apply {
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            }
        }
    }) { uri: Uri? ->
        uri?.let {
            App.updateDownloadDir(it, editingDirectory)
            val path = FileUtil.getRealPath(it)
            audioDirectoryText = path
        }
    }

    fun openDirectoryChooser(directory: Directory = Directory.AUDIO) {
        editingDirectory = directory
        dirLauncher.launch(null)
    }

    Scaffold(modifier = Modifier
        .fillMaxSize()
        .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SmallTopAppBar(
                titleText = stringResource(id = R.string.settings),
                navigationIcon = { BackButton(onNavigateBack) },
                scrollBehavior = scrollBehavior
            )
        }) {
        LazyColumn(
            modifier = Modifier.padding(it)
        ) {
            item {
                SettingTitle(text = stringResource(id = R.string.settings))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ) {
                item {
                    AnimatedVisibility(
                        visible = showBatteryHint && isActivityAvailable,
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        PreferencesHintCard(
                            title = stringResource(R.string.battery_configuration),
                            icon = Icons.Rounded.EnergySavingsLeaf,
                            description = stringResource(R.string.battery_configuration_desc),
                        ) {
                            launcher.launch(intent)
                            showBatteryHint =
                                !pm.isIgnoringBatteryOptimizations(context.packageName)
                        }
                    }
                }
            }
            // Inline general settings (no category subtitles)
            item {
                // ytdlp update
                
                PreferenceItem(
                    title = stringResource(id = R.string.ytdlp_update_action),
                    description = ytdlpVersion,
                    leadingIcon = {
                        if (isUpdating) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(start = 8.dp, end = 16.dp)
                                    .size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Rounded.SettingsApplications,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(start = 8.dp, end = 16.dp)
                                    .size(24.dp)
                            )
                        }
                    }, onClick = {
                        scope.launch {
                            runCatching {
                                isUpdating = true
                                val status = UpdateUtil.updateYtDlp()
                                ytdlpVersion = YoutubeDL.getInstance().version(context.applicationContext)
                                    ?: context.getString(R.string.ytdlp_update)
                                status
                            }.onFailure { th ->
                                th.printStackTrace()
                                ToastUtil.makeToastSuspend(context.getString(R.string.yt_dlp_update_fail))
                            }.onSuccess { status ->
                                val message = when (status) {
                                    YoutubeDL.UpdateStatus.DONE ->
                                        context.getString(R.string.yt_dlp_up_to_date) + " (${ytdlpVersion})"
                                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE ->
                                        context.getString(R.string.yt_dlp_up_to_date) + " (${ytdlpVersion})"
                                    else ->
                                        context.getString(R.string.yt_dlp_up_to_date) + " (${ytdlpVersion})"
                                }
                                ToastUtil.makeToastSuspend(message)
                            }
                            isUpdating = false
                        }
                    }
                )
            }

            item {
                // audio directory
                if (!isCustomCommandEnabled) {
                    PreferenceItem(
                        title = stringResource(id = R.string.audio_directory),
                        description = audioDirectoryText,
                        icon = Icons.Rounded.Folder
                    ) {
                                        openDirectoryChooser(directory = Directory.AUDIO)
                    }
                }
            }

            item {
                // rate limit
                PreferenceSwitchWithDivider(
                    title = stringResource(R.string.rate_limit),
                    description = maxDownloadRate + " KB/s",
                    icon = Icons.Rounded.VideoFile,
                    isChecked = isRateLimitEnabled,
                    enabled = !isCustomCommandEnabled,
                    onClick = { showRateLimitDialog = true },
                    onChecked = {
                        isRateLimitEnabled = !isRateLimitEnabled
                        RATE_LIMIT.updateBoolean(isRateLimitEnabled)
                    }
                )
            }

            item {
                PreferenceSwitch(
                    title = stringResource(id = R.string.download_with_cellular),
                    description = stringResource(id = R.string.download_with_cellular_desc),
                    icon = if (isDownloadWithCellularEnabled) Icons.Rounded.SignalCellular4Bar
                    else Icons.Rounded.SignalWifi4Bar,
                    isChecked = isDownloadWithCellularEnabled
                ) {
                    isDownloadWithCellularEnabled = !isDownloadWithCellularEnabled
                    CELLULAR_DOWNLOAD.updateBoolean(isDownloadWithCellularEnabled)
                }
            }

            item {
                PreferenceItem(
                    title = stringResource(id = R.string.pin_shortcut),
                    description = stringResource(id = R.string.pin_shortcut_desc),
                    icon = Icons.Rounded.SettingsApplications
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ShortcutUtil.requestPinSyncShortcut(context)
                    }
                }
            }

            // About section
            item {
                SettingTitle(text = stringResource(id = R.string.about))
            }

            item {
                PreferenceItem(
                    title = stringResource(R.string.readme),
                    description = stringResource(R.string.readme_desc),
                    icon = Icons.Rounded.Info
                ) {
                    uriHandler.openUri("https://github.com/raytonc/SealSync")
                }
            }

            item {
                PreferenceItem(
                    title = stringResource(id = R.string.credits),
                    description = stringResource(id = R.string.credits_desc),
                    icon = Icons.Rounded.VolunteerActivism
                ) { onNavigateTo(Route.CREDITS) }
            }
        }
    }
}