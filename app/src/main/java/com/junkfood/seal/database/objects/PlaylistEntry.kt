package com.junkfood.seal.database.objects

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity
@Serializable
data class PlaylistEntry(
    @PrimaryKey(autoGenerate = true) val id: Int,
    val title: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val playlistId: String? = null,
    @ColumnInfo(defaultValue = "0") val videoCount: Int = 0,
    val channelTitle: String? = null,
    val description: String? = null,
    @ColumnInfo(defaultValue = "0") val lastSynced: Long = 0,
    val dateAdded: Long = System.currentTimeMillis()
)
