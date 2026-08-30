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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EnergySavingsLeaf
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.Route
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.PreferenceItem
import com.junkfood.seal.ui.component.PreferencesHintCard
import com.junkfood.seal.ui.component.SmallTopAppBar
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.ShortcutUtil
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.UpdateUtil
import com.junkfood.seal.util.YOUTUBE_API_KEY
import com.junkfood.seal.util.YOUTUBE_CHANNEL_HANDLE
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.junkfood.seal.ui.component.SettingGroup
import com.junkfood.seal.ui.component.SettingSectionHeader

@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    onNavigateBack: () -> Unit, onNavigateTo: (String) -> Unit
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val uriHandler = LocalUriHandler.current

    // --- replicate a subset of GeneralDownloadPreferences state so we can render general settings inline
    val scope = rememberCoroutineScope()
    var isUpdating by remember { mutableStateOf(false) }
    var ytdlpVersion = YoutubeDL.getInstance().version(context.applicationContext)
        ?: context.getString(R.string.ytdlp_update)
    var audioDirectoryText by remember { mutableStateOf(App.audioDownloadDir) }

    var showApiKeyDialog by remember { mutableStateOf(false) }
    var currentApiKey by remember { mutableStateOf(YOUTUBE_API_KEY.getString()) }

    var showChannelHandleDialog by remember { mutableStateOf(false) }
    var currentChannelHandle by remember { mutableStateOf(YOUTUBE_CHANNEL_HANDLE.getString()) }

    val dirLauncher =
        rememberLauncherForActivityResult(object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: Context, input: Uri?): Intent {
                return (super.createIntent(context, input)).apply {
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                }
            }
        }) { uri: Uri? ->
            uri?.let {
                App.updateDownloadDir(it)
                val path = FileUtil.getRealPath(it)
                audioDirectoryText = path
            }
        }


    Scaffold(
        modifier = Modifier
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
            modifier = Modifier.padding(it),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // --- Sync: everything the sync run itself depends on.
            item { SettingSectionHeader(text = "Sync") }
            item {
                SettingGroup {
                    PreferenceItem(
                        title = stringResource(id = R.string.audio_directory),
                        description = audioDirectoryText,
                        icon = Icons.Rounded.Folder
                    ) {
                        dirLauncher.launch(null)
                    }

                    PreferenceItem(
                        title = "YouTube API Key",
                        description = if (currentApiKey.isNotEmpty())
                            "Configured · ${currentApiKey.take(6)}…"
                        else
                            "Required for syncing — tap to add",
                        icon = Icons.Rounded.Key
                    ) {
                        showApiKeyDialog = true
                    }

                    PreferenceItem(
                        title = "YouTube Channel Handle",
                        description = if (currentChannelHandle.isNotEmpty())
                            "@$currentChannelHandle"
                        else
                            "Optional — lets you add playlists from your channel",
                        icon = Icons.Rounded.AccountCircle
                    ) {
                        showChannelHandleDialog = true
                    }
                }
            }

            // --- Shortcuts and maintenance.
            item { SettingSectionHeader(text = "General") }
            item {
                SettingGroup {
                    PreferenceItem(
                        title = stringResource(id = R.string.pin_shortcut),
                        description = stringResource(id = R.string.pin_shortcut_desc),
                        icon = Icons.AutoMirrored.Rounded.AddToHomeScreen
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ShortcutUtil.requestPinSyncShortcut(context)
                        }
                    }

                    PreferenceItem(
                        title = stringResource(id = R.string.ytdlp_update_action),
                        description = if (isUpdating) "Updating…" else ytdlpVersion,
                        enabled = !isUpdating,
                        leadingIcon = {
                            if (isUpdating) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(start = 8.dp, end = 16.dp)
                                        .size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Update,
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
                                    ytdlpVersion =
                                        YoutubeDL.getInstance().version(context.applicationContext)
                                            ?: context.getString(R.string.ytdlp_update)
                                    status
                                }.onFailure { th ->
                                    th.printStackTrace()
                                    ToastUtil.showToast(context.getString(R.string.yt_dlp_update_fail))
                                }.onSuccess {
                                    ToastUtil.showToast(
                                        context.getString(R.string.yt_dlp_up_to_date) + " ($ytdlpVersion)"
                                    )
                                }
                                isUpdating = false
                            }
                        }
                    )
                }
            }

            // --- About.
            item { SettingSectionHeader(text = stringResource(id = R.string.about)) }
            item {
                SettingGroup {
                    PreferenceItem(
                        title = stringResource(R.string.readme),
                        description = stringResource(R.string.readme_desc),
                        icon = Icons.Rounded.Info
                    ) {
                        uriHandler.openUri("https://github.com/raytonc/SealSync")
                    }

                    PreferenceItem(
                        title = stringResource(id = R.string.credits),
                        description = stringResource(id = R.string.credits_desc),
                        icon = Icons.Rounded.VolunteerActivism
                    ) { onNavigateTo(Route.CREDITS) }
                }
            }
        }

        if (showApiKeyDialog) {
            YouTubeApiKeyDialog(
                onDismiss = { showApiKeyDialog = false },
                onConfirm = { newKey ->
                    YOUTUBE_API_KEY.updateString(newKey)
                    currentApiKey = newKey
                    showApiKeyDialog = false
                },
                currentKey = currentApiKey
            )
        }

        if (showChannelHandleDialog) {
            YouTubeChannelHandleDialog(
                onDismiss = { showChannelHandleDialog = false },
                onConfirm = { newHandle ->
                    YOUTUBE_CHANNEL_HANDLE.updateString(newHandle)
                    currentChannelHandle = newHandle
                    showChannelHandleDialog = false
                },
                currentHandle = currentChannelHandle
            )
        }
    }
}

@Composable
fun YouTubeChannelHandleDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    currentHandle: String = ""
) {
    var handle by remember { mutableStateOf(currentHandle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("YouTube Channel Handle") },
        text = {
            Column {
                Text(
                    text = "Enter the YouTube channel handle (without @)",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = handle,
                    onValueChange = { handle = it.removePrefix("@") },
                    label = { Text("Channel Handle") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("channelname") },
                    prefix = { Text("@") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(handle.trim().removePrefix("@")) },
                enabled = handle.trim().isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun YouTubeApiKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    currentKey: String = ""
) {
    var apiKey by remember { mutableStateOf(currentKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("YouTube API Key") },
        text = {
            Column {
                Text(
                    text = "Enter your YouTube Data API v3 key. You can get one from Google Cloud Console.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("AIzaSy...") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(apiKey.trim()) },
                enabled = apiKey.trim().isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}