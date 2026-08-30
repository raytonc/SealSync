package com.junkfood.seal.util

import androidx.room.Room
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.database.AppDatabase
import com.junkfood.seal.database.objects.PlaylistEntry

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
}
