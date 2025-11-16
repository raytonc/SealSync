package com.junkfood.seal.ui.page.videolist

import VideoStreamSVG
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.AsyncImageImpl
import com.junkfood.seal.ui.common.HapticFeedback.slightHapticFeedback
import com.junkfood.seal.ui.common.SVGImage
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.ConfirmButton
import com.junkfood.seal.ui.component.DismissButton
import com.junkfood.seal.ui.component.LargeTopAppBar
import com.junkfood.seal.ui.component.SealDialog
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

    val scrollBehavior = if (audioFiles.isNotEmpty()) {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            rememberTopAppBarState(),
            canScroll = { true }
        )
    } else {
        TopAppBarDefaults.pinnedScrollBehavior()
    }

    val view = LocalView.current
    var isSelectEnabled by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDeleteMultipleDialog by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<AudioFileInfo?>(null) }

    val selectedFiles = remember { mutableStateListOf<AudioFileInfo>() }

    BackHandler(isSelectEnabled) {
        isSelectEnabled = false
        selectedFiles.clear()
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
        if (audioFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SVGImage(
                        SVGString = VideoStreamSVG,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 72.dp, vertical = 20.dp)
                    )
                    Text(
                        text = "No audio files",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                items(audioFiles, key = { it.file.absolutePath }) { fileInfo ->
                    AudioFileItem(
                        fileInfo = fileInfo,
                        isSelectEnabled = isSelectEnabled,
                        isSelected = selectedFiles.contains(fileInfo),
                        onSelect = {
                            if (selectedFiles.contains(fileInfo)) {
                                selectedFiles.remove(fileInfo)
                            } else {
                                selectedFiles.add(fileInfo)
                            }
                        },
                        onClick = {
                            if (isSelectEnabled) {
                                if (selectedFiles.contains(fileInfo)) {
                                    selectedFiles.remove(fileInfo)
                                } else {
                                    selectedFiles.add(fileInfo)
                                }
                            } else {
                                FileUtil.openFile(path = fileInfo.file.absolutePath) {
                                    ToastUtil.makeToastSuspend(App.context.getString(R.string.file_unavailable))
                                }
                            }
                        },
                        onLongClick = {
                            isSelectEnabled = true
                            selectedFiles.add(fileInfo)
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
        val totalSize = selectedFiles.sumOf { it.file.length() }
        SealDialog(
            onDismissRequest = { showDeleteMultipleDialog = false },
            icon = { Icon(Icons.Outlined.DeleteSweep, null) },
            title = { Text("Delete files?") },
            text = {
                Text("Are you sure you want to delete ${selectedFiles.size} files (${totalSize.toFileSizeText()})?")
            },
            confirmButton = {
                ConfirmButton {
                    viewModel.deleteFiles(selectedFiles.toList())
                    selectedFiles.clear()
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

@Composable
fun AudioFileItem(
    fileInfo: AudioFileInfo,
    isSelectEnabled: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onClick: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onLongClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val view = LocalView.current
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable {
                view.slightHapticFeedback()
                onClick()
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
                    modifier = Modifier.size(56.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } ?: Box(modifier = Modifier.size(56.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = fileInfo.videoTitle ?: fileInfo.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = fileInfo.file.length().toFileSizeText(),
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
