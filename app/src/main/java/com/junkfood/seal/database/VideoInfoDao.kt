package com.junkfood.seal.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.junkfood.seal.database.objects.PlaylistEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoInfoDao {
    @Query("SELECT * FROM PlaylistEntry ORDER BY dateAdded ASC")
    fun getPlaylistsFlow(): Flow<List<PlaylistEntry>>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntry): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntry)

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntry)

    @Query("SELECT * FROM PlaylistEntry WHERE url = :url OR playlistId = :playlistId LIMIT 1")
    suspend fun findDuplicatePlaylist(url: String, playlistId: String?): PlaylistEntry?
}
