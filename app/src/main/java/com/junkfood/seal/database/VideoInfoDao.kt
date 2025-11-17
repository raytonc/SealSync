package com.junkfood.seal.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.database.objects.CookieProfile
import com.junkfood.seal.database.objects.OptionShortcut
import com.junkfood.seal.database.objects.PlaylistEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoInfoDao {
    @Query("SELECT * FROM CommandTemplate")
    fun getTemplateFlow(): Flow<List<CommandTemplate>>

    @Query("SELECT * FROM CommandTemplate")
    suspend fun getTemplateList(): List<CommandTemplate>

    @Query("SELECT * FROM CommandTemplate WHERE id = :id")
    suspend fun getTemplateById(id: Int): CommandTemplate

    @Insert
    suspend fun insertTemplate(template: CommandTemplate): Long

    @Insert
    @Transaction
    suspend fun importTemplates(templateList: List<CommandTemplate>)

    @Update
    suspend fun updateTemplate(template: CommandTemplate)

    @Delete
    suspend fun deleteTemplate(template: CommandTemplate)

    @Query("DELETE FROM CommandTemplate WHERE id = :id")
    suspend fun deleteTemplateById(id: Int)

    @Delete
    suspend fun deleteTemplates(templates: List<CommandTemplate>)

    @Query("SELECT * FROM CookieProfile")
    fun getCookieProfileFlow(): Flow<List<CookieProfile>>

    @Query("SELECT * FROM CookieProfile WHERE id = :id")
    suspend fun getCookieById(id: Int): CookieProfile?

    @Insert
    suspend fun insertCookieProfile(cookieProfile: CookieProfile)

    @Update
    suspend fun updateCookieProfile(cookieProfile: CookieProfile)

    @Delete
    suspend fun deleteCookieProfile(cookieProfile: CookieProfile)

    @Query("SELECT * FROM OptionShortcut")
    fun getOptionShortcuts(): Flow<List<OptionShortcut>>

    @Query("SELECT * FROM OptionShortcut")
    suspend fun getShortcutList(): List<OptionShortcut>

    @Insert
    suspend fun insertShortcut(optionShortcut: OptionShortcut): Long

    @Insert
    @Transaction
    suspend fun insertAllShortcuts(shortcuts: List<OptionShortcut>)

    @Delete
    suspend fun deleteShortcut(optionShortcut: OptionShortcut)

    @Query("SELECT * FROM PlaylistEntry ORDER BY dateAdded ASC")
    fun getPlaylistsFlow(): Flow<List<PlaylistEntry>>

    @Query("SELECT * FROM PlaylistEntry ORDER BY dateAdded ASC")
    suspend fun getPlaylists(): List<PlaylistEntry>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntry): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntry)

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntry)

    @Query("DELETE FROM PlaylistEntry WHERE id = :id")
    suspend fun deletePlaylistById(id: Int)
}