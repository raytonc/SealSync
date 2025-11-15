package com.junkfood.seal

data class DownloadTaskItem(
    val webpageUrl: String = "",
    val title: String = "",
    val uploader: String = "",
    val duration: Int = 0,
    val fileSizeApprox: Double = .0,
    val progress: Float = 0f,
    val progressText: String = "",
    val thumbnailUrl: String = "",
    val taskId: String = "",
    val playlistIndex: Int = 0,
)
