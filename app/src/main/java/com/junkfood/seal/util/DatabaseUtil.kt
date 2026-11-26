package com.junkfood.seal.util

import androidx.room.Room
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.database.AppDatabase
import com.junkfood.seal.database.objects.PlaylistEntry
import com.junkfood.seal.database.objects.TrackMetadata
import com.junkfood.seal.database.objects.TrackTag
import kotlinx.coroutines.flow.first

object DatabaseUtil {
    private const val DATABASE_NAME = "app_database"
    private val db = Room.databaseBuilder(
        context, AppDatabase::class.java, DATABASE_NAME
    ).build()
    private val dao = db.videoInfoDao()

    fun getPlaylistsFlow() = dao.getPlaylistsFlow()
    suspend fun insertPlaylist(playlist: PlaylistEntry) = dao.insertPlaylist(playlist)
    suspend fun updatePlaylist(playlist: PlaylistEntry) = dao.updatePlaylist(playlist)
    suspend fun deletePlaylist(playlist: PlaylistEntry) = dao.deletePlaylist(playlist)
    suspend fun findDuplicatePlaylist(url: String, playlistId: String?) =
        dao.findDuplicatePlaylist(url, playlistId)

    suspend fun getAllTrackTags() = dao.getAllTrackTags()
    suspend fun upsertTrackTags(tags: List<TrackTag>) = dao.upsertTrackTags(tags)
    suspend fun deleteTrackTags(videoIds: List<String>) = dao.deleteTrackTags(videoIds)
    suspend fun clearTrackTags() = dao.clearTrackTags()

    suspend fun getAllTrackMetadata() = dao.getAllTrackMetadata()
    suspend fun upsertTrackMetadata(rows: List<TrackMetadata>) = dao.upsertTrackMetadata(rows)
    suspend fun deleteTrackMetadata(videoIds: List<String>) = dao.deleteTrackMetadata(videoIds)
    suspend fun getPlaylists() = dao.getPlaylistsFlow().first()
}
