package com.junkfood.seal.ui.page.videolist

import VideoStreamSVG
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.AsyncImageImpl
import com.junkfood.seal.ui.common.HapticFeedback.longPressHapticFeedback
import com.junkfood.seal.ui.common.HapticFeedback.slightHapticFeedback
import com.junkfood.seal.ui.common.SVGImage
import com.junkfood.seal.ui.component.SkeletonList
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.ConfirmButton
import com.junkfood.seal.ui.component.DismissButton
import com.junkfood.seal.ui.component.LargeTopAppBar
import com.junkfood.seal.ui.component.SealDialog
import com.junkfood.seal.ui.theme.ArtworkShape
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.toFileSizeText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoListPage(
    viewModel: VideoListViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onNavigateBack: () -> Unit
) {
    val audioFiles by viewModel.audioFilesFlow.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    // Rescan every time page is shown
    LaunchedEffect(Unit) {
        viewModel.refreshFileList()
    }

    val scrollBehavior = if (audioFiles.isNotEmpty()) {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            rememberTopAppBarState(),
            canScroll = { true }
        )
    } else {
        TopAppBarDefaults.pinnedScrollBehavior()
    }

    val view = LocalView.current
    // Hoisted out of the row: SimpleDateFormat is comparatively expensive to construct,
    // and every row was building its own.
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    var isSelectEnabled by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDeleteMultipleDialog by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<AudioFileInfo?>(null) }

    // Keyed by the row's stable identity rather than held as a list of items: the row
    // needs "am I selected?" on every recomposition, and over a list that was a linear
    // scan per row -- O(rows x selected) for each frame of a multi-select.
    val selectedKeys = remember { mutableStateMapOf<String, Unit>() }
    val selectedFiles by remember(audioFiles) {
        derivedStateOf { audioFiles.filter { it.selectionKey in selectedKeys } }
    }

    BackHandler(isSelectEnabled) {
        isSelectEnabled = false
        selectedKeys.clear()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        modifier = Modifier,
                        text = "Audio Files"
                    )
                },
                navigationIcon = {
                    BackButton {
                        onNavigateBack()
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            view.slightHapticFeedback()
                            viewModel.refreshFileList()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            AnimatedVisibility(
                isSelectEnabled,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                BottomAppBar {
                    Text(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                        text = "${selectedFiles.size} selected",
                        style = MaterialTheme.typography.labelLarge
                    )
                    IconButton(
                        onClick = {
                            view.slightHapticFeedback()
                            showDeleteMultipleDialog = true
                        },
                        enabled = selectedFiles.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = "Delete selected"
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        if (isLoading) {
            // Placeholder rows in the shape of the real list: the scan is usually quick, and
            // a full-screen spinner made a fast operation feel like a stall.
            SkeletonList(
                modifier = Modifier
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
            )
        } else if (audioFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    SVGImage(
                        SVGString = VideoStreamSVG,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 72.dp, vertical = 20.dp)
                    )
                    Text(
                        text = "No audio yet",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Run a sync from the home screen and your playlist audio " +
                                "will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
            ) {
                items(audioFiles, key = { it.selectionKey }) { fileInfo ->
                    val key = fileInfo.selectionKey
                    AudioFileItem(
                        fileInfo = fileInfo,
                        isSelectEnabled = isSelectEnabled,
                        isSelected = key in selectedKeys,
                        dateFormat = dateFormat,
                        onSelect = { selectedKeys.toggleSelection(key) },
                        onClick = {
                            if (isSelectEnabled) {
                                selectedKeys.toggleSelection(key)
                            } else {
                                // Prefer the SAF URI, which is already directly openable.
                                val uri = fileInfo.uri
                                val path = fileInfo.file?.absolutePath
                                when {
                                    uri != null -> FileUtil.openFile(uri = uri) {
                                        ToastUtil.showToast(App.context.getString(R.string.file_unavailable))
                                    }

                                    path != null -> FileUtil.openFile(path = path) {
                                        ToastUtil.showToast(App.context.getString(R.string.file_unavailable))
                                    }

                                    else ->
                                        ToastUtil.showToast(App.context.getString(R.string.file_unavailable))
                                }
                            }
                        },
                        onLongClick = {
                            isSelectEnabled = true
                            selectedKeys[key] = Unit
                        },
                        onDeleteClick = {
                            fileToDelete = fileInfo
                            showDeleteDialog = true
                        }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }
    }

    if (showDeleteDialog && fileToDelete != null) {
        SealDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text("Delete file?") },
            text = {
                Text("Are you sure you want to delete ${fileToDelete?.name}?")
            },
            confirmButton = {
                ConfirmButton {
                    fileToDelete?.let { viewModel.deleteFile(it) }
                    showDeleteDialog = false
                }
            },
            dismissButton = {
                DismissButton {
                    showDeleteDialog = false
                }
            }
        )
    }

    if (showDeleteMultipleDialog) {
        val totalSize = selectedFiles.sumOf { it.size }
        SealDialog(
            onDismissRequest = { showDeleteMultipleDialog = false },
            icon = { Icon(Icons.Outlined.DeleteSweep, null) },
            title = { Text("Delete files?") },
            text = {
                Text("Are you sure you want to delete ${selectedFiles.size} files (${totalSize.toFileSizeText()})?")
            },
            confirmButton = {
                ConfirmButton {
                    // Copied before the keys are cleared: selectedFiles is derived from
                    // them, so handing the live list over and then clearing would leave
                    // the delete with nothing to do.
                    viewModel.deleteFiles(selectedFiles.toList())
                    selectedKeys.clear()
                    isSelectEnabled = false
                    showDeleteMultipleDialog = false
                }
            },
            dismissButton = {
                DismissButton {
                    showDeleteMultipleDialog = false
                }
            }
        )
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudioFileItem(
    fileInfo: AudioFileInfo,
    isSelectEnabled: Boolean,
    isSelected: Boolean,
    dateFormat: SimpleDateFormat,
    onSelect: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val view = LocalView.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(
                onClick = {
                    view.slightHapticFeedback()
                    onClick()
                },
                onLongClick = {
                    view.longPressHapticFeedback()
                    onLongClick()
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (isSelectEnabled) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelect() }
                )
            }
            // Thumbnail (if available)
            fileInfo.thumbnailUrl?.takeIf { it.isNotEmpty() }?.let { url ->
                AsyncImageImpl(
                    model = url,
                    contentDescription = fileInfo.videoTitle ?: fileInfo.name,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(ArtworkShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } ?: Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(ArtworkShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = fileInfo.videoTitle ?: fileInfo.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = fileInfo.size.toFileSizeText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = dateFormat.format(Date(fileInfo.lastModified)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!isSelectEnabled) {
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Stable identity for a row, used both as the [LazyColumn] key and as the selection key.
 * The SAF URI where there is one, the absolute path for legacy File-API entries, and the
 * name as a last resort.
 */
private val AudioFileInfo.selectionKey: String
    get() = uri?.toString() ?: file?.absolutePath ?: name

/**
 * A set keyed for O(1) membership. Backed by a snapshot *map* rather than a set because
 * the Compose runtime this project builds against has no observable set primitive; the
 * value is ignored.
 */
private fun MutableMap<String, Unit>.toggleSelection(key: String) {
    if (remove(key) == null) put(key, Unit)
}
