@file:OptIn(ExperimentalPermissionsApi::class)

package com.junkfood.seal.ui.page.settings.general

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.NetworkCell
import androidx.compose.material.icons.outlined.NetworkWifi
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.booleanState
import com.junkfood.seal.ui.common.intState
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.ConfirmButton
import com.junkfood.seal.ui.component.DismissButton
import com.junkfood.seal.ui.component.LargeTopAppBar
import com.junkfood.seal.ui.component.PreferenceInfo
import com.junkfood.seal.ui.component.PreferenceItem
import com.junkfood.seal.ui.component.PreferenceSubtitle
import com.junkfood.seal.ui.component.PreferenceSwitch
import com.junkfood.seal.ui.component.PreferenceSwitchWithDivider
import com.junkfood.seal.ui.component.SealDialog
import com.junkfood.seal.util.CELLULAR_DOWNLOAD
import com.junkfood.seal.util.CUSTOM_COMMAND
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.MAX_RATE
import com.junkfood.seal.util.PreferenceStrings.getUpdateIntervalText
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getLong
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.PreferenceUtil.updateLong
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.RATE_LIMIT
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.UpdateIntervalList
import com.junkfood.seal.util.UpdateUtil
import com.junkfood.seal.util.YT_DLP_AUTO_UPDATE
import com.junkfood.seal.util.YT_DLP_NIGHTLY
import com.junkfood.seal.util.YT_DLP_STABLE
import com.junkfood.seal.util.YT_DLP_UPDATE_CHANNEL
import com.junkfood.seal.util.YT_DLP_UPDATE_INTERVAL
import com.junkfood.seal.util.YT_DLP_VERSION
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ytdlpReference = "https://github.com/yt-dlp/yt-dlp#readme"

enum class Directory {
    AUDIO
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralDownloadPreferences(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // General settings state
    var showYtdlpDialog by remember { mutableStateOf(false) }
    var isUpdating by remember { mutableStateOf(false) }

    // Directory settings state
    var audioDirectoryText by remember { mutableStateOf(App.audioDownloadDir) }
    var editingDirectory by remember { mutableStateOf(Directory.AUDIO) }

    // Format settings state

    // Network settings state
    var isRateLimitEnabled by RATE_LIMIT.booleanState
    var maxDownloadRate by remember { mutableStateOf(MAX_RATE.getString()) }
    var showRateLimitDialog by remember { mutableStateOf(false) }
    var isDownloadWithCellularEnabled by CELLULAR_DOWNLOAD.booleanState

    val isCustomCommandEnabled by remember {
        mutableStateOf(PreferenceUtil.getValue(CUSTOM_COMMAND))
    }

    val storagePermission =
        rememberPermissionState(permission = Manifest.permission.WRITE_EXTERNAL_STORAGE)

    val isPermissionGranted =
        Build.VERSION.SDK_INT > 29 || storagePermission.status == PermissionStatus.Granted

    val launcher =
        rememberLauncherForActivityResult(object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: Context, input: Uri?): Intent {
                return (super.createIntent(context, input)).apply {
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                }
            }
        }) {
            it?.let { uri ->
                App.updateDownloadDir(uri, editingDirectory)
                val path = FileUtil.getRealPath(uri)
                audioDirectoryText = path
            }
        }

    fun openDirectoryChooser(directory: Directory = Directory.AUDIO) {
        editingDirectory = directory
        if (Build.VERSION.SDK_INT > 29 || storagePermission.status == PermissionStatus.Granted)
            launcher.launch(null)
        else storagePermission.launchPermissionRequest()
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState(),
        canScroll = { true })

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = {
            SnackbarHost(
                modifier = Modifier.systemBarsPadding(),
                hostState = snackbarHostState
            )
        },
        topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(id = R.string.general_settings)) },
                navigationIcon = {
                    BackButton { onNavigateBack() }
                },
                scrollBehavior = scrollBehavior
            )
        },
        content = {
            LazyColumn(
                modifier = Modifier.padding(it)
            ) {
                if (isCustomCommandEnabled)
                    item {
                        PreferenceInfo(text = stringResource(id = R.string.custom_command_enabled_hint))
                    }

                // ===== GENERAL SECTION =====
                item {
                    PreferenceSubtitle(text = stringResource(id = R.string.general_settings))
                }

                item {
                    var ytdlpVersion by remember {
                        mutableStateOf(
                            YoutubeDL.getInstance().version(context.applicationContext)
                                ?: context.getString(R.string.ytdlp_update)
                        )
                    }
                    PreferenceItem(
                        title = stringResource(id = R.string.ytdlp_update_action),
                        description = ytdlpVersion,
                        leadingIcon = {
                            if (isUpdating) UpdateProgressIndicator() else {
                                Icon(
                                    imageVector = Icons.Outlined.Update,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(start = 8.dp, end = 16.dp)
                                        .size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }, onClick = {
                            scope.launch {
                                runCatching {
                                    isUpdating = true
                                    val status = UpdateUtil.updateYtDlp()
                                    ytdlpVersion = YT_DLP_VERSION.getString()
                                    status
                                }.onFailure { th ->
                                    th.printStackTrace()
                                    ToastUtil.makeToastSuspend(App.context.getString(R.string.yt_dlp_update_fail))
                                }.onSuccess { status ->
                                    val message = when (status) {
                                        YoutubeDL.UpdateStatus.DONE ->
                                            context.getString(R.string.yt_dlp_up_to_date) + " (${YT_DLP_VERSION.getString()})"
                                        YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE ->
                                            context.getString(R.string.yt_dlp_up_to_date) + " (${YT_DLP_VERSION.getString()})"
                                        else ->
                                            context.getString(R.string.yt_dlp_up_to_date) + " (${YT_DLP_VERSION.getString()})"
                                    }
                                    ToastUtil.makeToastSuspend(message)
                                }
                                isUpdating = false
                            }
                        }, onClickLabel = stringResource(id = R.string.update),
                        trailingIcon = {
                            IconButton(onClick = { showYtdlpDialog = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.Settings,
                                    contentDescription = stringResource(
                                        id = R.string.open_settings
                                    )
                                )
                            }
                        }
                    )
                }

                // ===== AUDIO DIRECTORY SECTION =====
                item {
                    PreferenceSubtitle(text = stringResource(R.string.audio_directory))
                }

                if (!isCustomCommandEnabled) {
                    item {
                        PreferenceItem(
                            title = stringResource(id = R.string.audio_directory),
                            description = audioDirectoryText,
                            icon = Icons.Outlined.LibraryMusic
                        ) {
                            openDirectoryChooser(directory = Directory.AUDIO)
                        }
                    }
                }

                // ===== AUDIO FORMAT SECTION =====
                item {
                    PreferenceSubtitle(text = stringResource(id = R.string.audio))
                }

                // Crop artwork toggle removed — artwork will be enforced square by default

                // ===== NETWORK SECTION =====
                item {
                    PreferenceSubtitle(text = stringResource(R.string.network))
                }

                item {
                    PreferenceSwitchWithDivider(
                        title = stringResource(R.string.rate_limit),
                        description = maxDownloadRate + " KB/s",
                        icon = Icons.Outlined.SlowMotionVideo,
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
                        icon = if (isDownloadWithCellularEnabled) Icons.Outlined.NetworkCell
                        else Icons.Outlined.NetworkWifi,
                        isChecked = isDownloadWithCellularEnabled
                    ) {
                        isDownloadWithCellularEnabled = !isDownloadWithCellularEnabled
                        CELLULAR_DOWNLOAD.updateBoolean(
                            isDownloadWithCellularEnabled
                        )
                    }
                }
            }
        })

    // ===== DIALOGS =====
    if (showYtdlpDialog) {
        var ytdlpUpdateChannel by YT_DLP_UPDATE_CHANNEL.intState
        var ytdlpAutoUpdate by YT_DLP_AUTO_UPDATE.booleanState
        var updateInterval by remember { mutableLongStateOf(YT_DLP_UPDATE_INTERVAL.getLong()) }

        SealDialog(
            onDismissRequest = { showYtdlpDialog = false },
            confirmButton = {
                ConfirmButton {
                    YT_DLP_AUTO_UPDATE.updateBoolean(ytdlpAutoUpdate)
                    YT_DLP_UPDATE_CHANNEL.updateInt(ytdlpUpdateChannel)
                    YT_DLP_UPDATE_INTERVAL.updateLong(updateInterval)
                    showYtdlpDialog = false
                }
            },
            dismissButton = {
                DismissButton {
                    showYtdlpDialog = false
                }
            },
            title = { Text(text = stringResource(id = R.string.update)) },
            icon = { Icon(Icons.Outlined.SyncAlt, null) },
            text = {
                LazyColumn() {
                    item {
                        Text(
                            text = stringResource(id = R.string.update_channel),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .padding(top = 16.dp, bottom = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    item {
                        DialogSingleChoiceItem(
                            text = "yt-dlp",
                            selected = ytdlpUpdateChannel == YT_DLP_STABLE,
                            label = "Stable"
                        ) {
                            ytdlpUpdateChannel = YT_DLP_STABLE
                        }
                    }
                    item {
                        DialogSingleChoiceItem(
                            text = "yt-dlp-nightly-builds",
                            selected = ytdlpUpdateChannel == YT_DLP_NIGHTLY,
                            label = "Nightly",
                            labelContainerColor = MaterialTheme.colorScheme.tertiary
                        ) {
                            ytdlpUpdateChannel = YT_DLP_NIGHTLY
                        }
                    }
                    item {
                        Text(
                            text = stringResource(id = R.string.additional_settings),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .padding(top = 16.dp, bottom = 16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    item {
                        var expanded by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            expanded = expanded,
                            onExpandedChange = { expanded = it }) {
                            OutlinedTextField(
                                value = if (!ytdlpAutoUpdate) stringResource(id = R.string.disabled)
                                else getUpdateIntervalText(updateInterval),
                                onValueChange = {},
                                label = { Text(text = stringResource(id = R.string.auto_update)) },
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                },
                            )
                            ExposedDropdownMenu(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                expanded = expanded,
                                onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(id = R.string.disabled)) },
                                    onClick = {
                                        ytdlpAutoUpdate = false
                                        expanded = false
                                    })
                                for ((interval, stringId) in UpdateIntervalList) {
                                    DropdownMenuItem(
                                        text = { Text(text = stringResource(id = stringId)) },
                                        onClick = {
                                            ytdlpAutoUpdate = true
                                            updateInterval = interval
                                            expanded = false
                                        })
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    if (showRateLimitDialog) {
        RateLimitDialog(
            onDismissRequest = { showRateLimitDialog = false },
            maxDownloadRate = maxDownloadRate
        ) { rate ->
            maxDownloadRate = rate
            MAX_RATE.updateString(rate)
        }
    }
}

@Composable
private fun DialogSingleChoiceItem(
    modifier: Modifier = Modifier,
    text: String,
    selected: Boolean,
    label: String,
    labelContainerColor: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = true,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        RadioButton(
            modifier = Modifier.clearAndSetSemantics { }, selected = selected, onClick = onClick
        )

        Text(text = text, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.weight(1f))
        Surface(
            modifier.padding(end = 12.dp),
            shape = CircleShape,
            color = labelContainerColor,
            contentColor = contentColorFor(backgroundColor = labelContainerColor),
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
fun DialogCheckBoxItem(
    modifier: Modifier = Modifier,
    text: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = true,
                onValueChange = { onClick() },
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            modifier = Modifier.clearAndSetSemantics { },
            checked = checked, onCheckedChange = { onClick() },
        )
        Text(
            modifier = Modifier.weight(1f),
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun UpdateProgressIndicator() {
    CircularProgressIndicator(
        modifier = Modifier
            .padding(start = 8.dp, end = 16.dp)
            .size(24.dp),
        strokeWidth = 3.dp
    )
}

@Composable
fun RateLimitDialog(
    onDismissRequest: () -> Unit,
    maxDownloadRate: String,
    onConfirm: (String) -> Unit
) {
    var rate by remember { mutableStateOf(maxDownloadRate) }

    SealDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            ConfirmButton {
                onConfirm(rate)
                onDismissRequest()
            }
        },
        dismissButton = {
            DismissButton {
                onDismissRequest()
            }
        },
        icon = { Icon(Icons.Outlined.SlowMotionVideo, null) },
        title = { Text(text = stringResource(R.string.rate_limit)) },
        text = {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                OutlinedTextField(
                    value = rate,
                    onValueChange = { rate = it },
                    label = { Text("KB/s") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}
