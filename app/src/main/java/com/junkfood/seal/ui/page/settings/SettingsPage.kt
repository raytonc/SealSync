package com.junkfood.seal.ui.page.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.Route
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.PreferenceItem
import com.junkfood.seal.ui.component.PreferenceSwitch
import com.junkfood.seal.ui.component.SmallTopAppBar
import com.junkfood.seal.ui.page.UpdateDialogImpl
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.AUTO_SYNC_ENABLED
import com.junkfood.seal.util.AUTO_SYNC_INTERVALS
import com.junkfood.seal.util.AUTO_SYNC_INTERVAL_HOURS
import com.junkfood.seal.util.AUTO_SYNC_REQUIRES_CHARGING
import com.junkfood.seal.util.syncIntervalLabelRes
import com.junkfood.seal.util.AutoSyncWorker
import com.junkfood.seal.util.CELLULAR_DOWNLOAD
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.ShortcutUtil
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.UpdateUtil
import com.junkfood.seal.util.YOUTUBE_API_KEY
import com.junkfood.seal.util.YOUTUBE_CHANNEL_HANDLE
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.SystemUpdate
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

    // Manual app-update check. The automatic one runs once per cold start from HomeEntry,
    // which gives no way to ask on demand and no answer when the app is already current.
    //
    // The version is read from the package manager rather than a generated constant: this
    // module does not enable the buildConfig feature, and this is the same source the
    // updater compares against, so the two cannot disagree.
    val installedVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    // A finished download still has to reach the package installer, and on O+ that needs
    // REQUEST_INSTALL_PACKAGES -- or, if the user denies it, a trip to the system screen
    // that grants it. Without these the update downloaded and then silently did nothing.
    val installSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            UpdateUtil.installLatestApk()
        }
    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            UpdateUtil.installLatestApk()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                installSettingsLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    )
                )
            } else {
                UpdateUtil.installLatestApk()
            }
        }
    }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var latestRelease by remember { mutableStateOf(UpdateUtil.LatestRelease()) }
    var updateDownloadStatus by remember {
        mutableStateOf(UpdateUtil.DownloadStatus.NotYet as UpdateUtil.DownloadStatus)
    }
    val updateJob = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val scope = rememberCoroutineScope()
    var isUpdating by remember { mutableStateOf(false) }
    // Remembered state, not a plain local: reading the version goes through the yt-dlp
    // wrapper to disk, so an unremembered `var` paid for that on every recomposition --
    // and, worse, the assignment after an update was discarded by the next one, so the row
    // kept showing the version the screen opened with.
    var ytdlpVersion by remember {
        mutableStateOf(
            YoutubeDL.getInstance().version(context.applicationContext)
                ?: context.getString(R.string.ytdlp_update)
        )
    }
    var audioDirectoryText by remember { mutableStateOf(App.audioDownloadDir) }

    var showApiKeyDialog by remember { mutableStateOf(false) }
    var currentApiKey by remember { mutableStateOf(YOUTUBE_API_KEY.getString()) }

    var showChannelHandleDialog by remember { mutableStateOf(false) }
    var currentChannelHandle by remember { mutableStateOf(YOUTUBE_CHANNEL_HANDLE.getString()) }

    // Auto-sync. Held as state rather than read straight from the store on each
    // recomposition so the rows update the moment they are toggled; every write is
    // followed by a reschedule, which is what actually makes the change take effect.
    var autoSyncEnabled by remember { mutableStateOf(AUTO_SYNC_ENABLED.getBoolean()) }
    var autoSyncHours by remember { mutableIntStateOf(AUTO_SYNC_INTERVAL_HOURS.getInt()) }
    var autoSyncCharging by remember {
        mutableStateOf(AUTO_SYNC_REQUIRES_CHARGING.getBoolean())
    }
    var showIntervalDialog by remember { mutableStateOf(false) }

    // Read into state rather than straight from MMKV inside the note below. MMKV is not a
    // snapshot source, so a direct read never recomposes -- and this preference is changed
    // from the metered-network dialog on another screen, which would leave the note saying
    // "Wi-Fi only" after the schedule had already been rebuilt for any network. Refreshed
    // on resume, the same way the API key row above is.
    var cellularAllowed by remember { mutableStateOf(CELLULAR_DOWNLOAD.getBoolean()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cellularAllowed = CELLULAR_DOWNLOAD.getBoolean()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

            // --- Automatic sync: the schedule, and the two constraints worth exposing.
            item { SettingSectionHeader(text = stringResource(R.string.auto_sync)) }
            item {
                SettingGroup {
                    PreferenceSwitch(
                        title = stringResource(R.string.auto_sync_enable),
                        description = if (autoSyncEnabled)
                            stringResource(
                                R.string.auto_sync_enabled_desc,
                                stringResource(syncIntervalLabelRes(autoSyncHours))
                            )
                        else
                            stringResource(R.string.auto_sync_enable_desc),
                        icon = Icons.Rounded.Sync,
                        checked = autoSyncEnabled,
                        onCheckedChange = { enabled ->
                            autoSyncEnabled = enabled
                            AUTO_SYNC_ENABLED.updateBoolean(enabled)
                            AutoSyncWorker.applySettingsChange(context)
                        },
                    )

                    PreferenceItem(
                        title = stringResource(R.string.auto_sync_interval),
                        description = stringResource(syncIntervalLabelRes(autoSyncHours)),
                        icon = Icons.Rounded.Schedule,
                        // Dimmed rather than hidden while off, so the cadence a run would
                        // use is still visible when deciding whether to turn it on.
                        enabled = autoSyncEnabled,
                    ) {
                        showIntervalDialog = true
                    }

                    PreferenceSwitch(
                        title = stringResource(R.string.auto_sync_charging),
                        description = stringResource(R.string.auto_sync_charging_desc),
                        icon = Icons.Rounded.BatteryChargingFull,
                        enabled = autoSyncEnabled,
                        checked = autoSyncCharging,
                        onCheckedChange = { requiresCharging ->
                            autoSyncCharging = requiresCharging
                            AUTO_SYNC_REQUIRES_CHARGING.updateBoolean(requiresCharging)
                            AutoSyncWorker.applySettingsChange(context)
                        },
                    )

                    // The only way back. Until this row existed the preference was
                    // write-once: the metered-network dialog's "always" button set it true
                    // and nothing anywhere set it false, so a user who tapped that once --
                    // to get one sync through on the train -- had permanently signed every
                    // future scheduled run up for mobile data with no way to take it back.
                    //
                    // Not gated on autoSyncEnabled, unlike the two rows above: this governs
                    // manual syncs and retries just as much as scheduled ones.
                    PreferenceSwitch(
                        title = stringResource(R.string.cellular_download),
                        description = stringResource(R.string.cellular_download_desc),
                        icon = Icons.Rounded.NetworkCell,
                        checked = cellularAllowed,
                        onCheckedChange = { allowed ->
                            cellularAllowed = allowed
                            CELLULAR_DOWNLOAD.updateBoolean(allowed)
                            // The schedule's network constraint is built from this, and an
                            // already-enqueued one keeps whatever it was built with.
                            AutoSyncWorker.applySettingsChange(context)
                        },
                    )
                }
            }
            // Says out loud what the constraints mean, because the alternative is a user
            // concluding the feature is broken when it is in fact waiting on wifi.
            item {
                PreferenceInfoNote(
                    text = stringResource(
                        if (cellularAllowed) R.string.auto_sync_note_any_network
                        else R.string.auto_sync_note_unmetered
                    )
                )
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
                        title = stringResource(R.string.check_for_updates),
                        description = stringResource(
                            R.string.check_for_updates_desc, installedVersion
                        ),
                        // A spinner in place of the icon while the check is in flight, the
                        // same way the yt-dlp row above reports itself.
                        leadingIcon = {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(start = 8.dp, end = 16.dp)
                                        .size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.SystemUpdate,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(start = 8.dp, end = 16.dp)
                                        .size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                    ) {
                        if (isCheckingUpdate) return@PreferenceItem
                        scope.launch {
                            isCheckingUpdate = true
                            runCatching {
                                withContext(Dispatchers.IO) { UpdateUtil.checkForUpdate() }
                            }.onSuccess { release ->
                                if (release != null) {
                                    latestRelease = release
                                    showUpdateDialog = true
                                } else {
                                    // The silent case is the whole reason this exists: a
                                    // check that finds nothing has to say so, or the button
                                    // is indistinguishable from one that does not work.
                                    ToastUtil.showToast(
                                        context.getString(
                                            R.string.app_up_to_date, installedVersion
                                        )
                                    )
                                }
                            }.onFailure { th ->
                                th.printStackTrace()
                                ToastUtil.showToast(
                                    context.getString(R.string.app_update_check_failed)
                                )
                            }
                            isCheckingUpdate = false
                        }
                    }

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

        if (showUpdateDialog) {
            UpdateDialogImpl(
                onDismissRequest = {
                    showUpdateDialog = false
                    updateJob.value?.cancel()
                },
                title = latestRelease.name.toString(),
                onConfirmUpdate = {
                    // Collected on the main dispatcher, not IO. The Finished branch below
                    // touches an ActivityResultLauncher and, on the pre-M path,
                    // startActivity plus a main-thread-only Toast -- none of which may run
                    // off the main thread. flowOn inside downloadApk already puts the
                    // network work on IO and only affects the upstream producer, so the
                    // dispatcher this collector runs on buys nothing but the bug.
                    updateJob.value = scope.launch {
                        runCatching {
                            UpdateUtil.downloadApk(latestRelease = latestRelease)
                                .collect { status ->
                                    updateDownloadStatus = status
                                    if (status is UpdateUtil.DownloadStatus.Finished) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            installPermissionLauncher.launch(
                                                Manifest.permission.REQUEST_INSTALL_PACKAGES
                                            )
                                        } else {
                                            UpdateUtil.installLatestApk()
                                        }
                                    }
                                }
                        }.onFailure {
                            it.printStackTrace()
                            updateDownloadStatus = UpdateUtil.DownloadStatus.NotYet
                            ToastUtil.showToast(context.getString(R.string.app_update_failed))
                        }
                    }
                },
                releaseNote = latestRelease.body.toString(),
                downloadStatus = updateDownloadStatus,
            )
        }

        if (showIntervalDialog) {
            SyncIntervalDialog(
                selectedHours = autoSyncHours,
                onDismiss = { showIntervalDialog = false },
                onSelect = { hours ->
                    autoSyncHours = hours
                    AUTO_SYNC_INTERVAL_HOURS.updateInt(hours)
                    AutoSyncWorker.applySettingsChange(context)
                    showIntervalDialog = false
                },
            )
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

/** Picks the cadence. A short fixed list, so radio buttons rather than a slider. */
@Composable
private fun SyncIntervalDialog(
    selectedHours: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_sync_interval)) },
        text = {
            Column {
                AUTO_SYNC_INTERVALS.forEach { interval ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = interval.hours == selectedHours,
                                role = Role.RadioButton,
                                onClick = { onSelect(interval.hours) },
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = interval.hours == selectedHours, onClick = null)
                        Spacer(Modifier.size(16.dp))
                        Text(stringResource(interval.labelRes))
                    }
                }
            }
        },
        // Dismiss, not confirm: picking a row commits the change and closes the dialog, so
        // there is nothing for an affirmative button to affirm. In the confirmButton slot
        // Material puts it at the trailing edge where OK/Done belongs, which invites a tap
        // from someone who opened the picker only to look.
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
        confirmButton = {},
    )
}

/**
 * A quiet line of explanation under a settings group, for the thing a group of switches
 * cannot say about itself. Not a [PreferenceItem]: it is not tappable and should not look
 * like it is.
 */
@Composable
private fun PreferenceInfoNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 8.dp),
    )
}
