package com.junkfood.seal.database.objects

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity
@Serializable
data class PlaylistEntry(
    @PrimaryKey(autoGenerate = true) val id: Int,
    val title: String,
    val url: String,
    val dateAdded: Long = System.currentTimeMillis()
)
