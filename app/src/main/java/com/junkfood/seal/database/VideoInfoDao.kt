package com.junkfood.seal.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.junkfood.seal.database.objects.PlaylistEntry
import com.junkfood.seal.database.objects.TrackTag
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

    @Query("SELECT * FROM TrackTag")
    suspend fun getAllTrackTags(): List<TrackTag>

    /**
     * Upsert rather than insert: a retag rewrites the row for a video that already has one,
     * and a plain insert would abort the whole batch on the conflict.
     */
    @Upsert
    suspend fun upsertTrackTags(tags: List<TrackTag>)

    /** Drops rows for videos whose files a sync has just deleted. */
    @Query("DELETE FROM TrackTag WHERE videoId IN (:videoIds)")
    suspend fun deleteTrackTags(videoIds: List<String>)
}
