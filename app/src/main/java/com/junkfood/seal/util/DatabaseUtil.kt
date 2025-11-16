package com.junkfood.seal.util

import androidx.room.Room
import com.junkfood.seal.App.Companion.applicationScope
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.database.AppDatabase
import com.junkfood.seal.database.backup.Backup
import com.junkfood.seal.database.backup.BackupUtil.BackupType
import com.junkfood.seal.database.backup.BackupUtil.decodeToBackup
import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.database.objects.CookieProfile
import com.junkfood.seal.database.objects.OptionShortcut
import com.junkfood.seal.database.objects.PlaylistEntry
import kotlinx.coroutines.launch


object DatabaseUtil {
    private const val DATABASE_NAME = "app_database"
    private val db = Room.databaseBuilder(
        context, AppDatabase::class.java, DATABASE_NAME
    ).build()
    private val dao = db.videoInfoDao()

    init {
        applicationScope.launch {
            getTemplateFlow().collect {
                if (it.isEmpty()) PreferenceUtil.initializeTemplateSample()
            }
        }
    }

    fun getTemplateFlow() = dao.getTemplateFlow()

    fun getShortcuts() = dao.getOptionShortcuts()

    suspend fun getTemplateList() = dao.getTemplateList()
    suspend fun insertTemplate(template: CommandTemplate) = dao.insertTemplate(template)
    suspend fun updateTemplate(template: CommandTemplate) = dao.updateTemplate(template)
    suspend fun deleteTemplateById(id: Int) = dao.deleteTemplateById(id)
    suspend fun deleteTemplates(templates: List<CommandTemplate>) = dao.deleteTemplates(templates)

    suspend fun getCookieById(id: Int) = dao.getCookieById(id)
    suspend fun insertCookieProfile(profile: CookieProfile) = dao.insertCookieProfile(profile)
    suspend fun updateCookieProfile(profile: CookieProfile) = dao.updateCookieProfile(profile)
    suspend fun deleteCookieProfile(profile: CookieProfile) = dao.deleteCookieProfile(profile)

    suspend fun getShortcutList() = dao.getShortcutList()
    suspend fun insertShortcut(shortcut: OptionShortcut) = dao.insertShortcut(shortcut)
    suspend fun deleteShortcut(shortcut: OptionShortcut) = dao.deleteShortcut(shortcut)

    suspend fun importBackup(backup: Backup, types: Set<BackupType>): Int {
        var cnt = 0
        backup.run {
            if (types.contains(BackupType.CommandTemplate)) {
                if (templates != null) {
                    val templateList = getTemplateList()
                    dao.importTemplates(
                        templates.filterNot {
                            templateList.contains(it)
                        }.map { it.copy(id = 0) }.also { cnt += it.size }
                    )
                }
            }
            if (types.contains(BackupType.CommandShortcut)) {
                val shortcutList = getShortcutList()
                if (shortcuts != null) {
                    dao.insertAllShortcuts(
                        shortcuts.filterNot {
                            shortcutList.contains(it)
                        }.map { it.copy(id = 0) }.also { cnt += it.size }
                    )
                }
            }
        }
        return cnt
    }

    suspend fun importTemplatesFromJson(json: String): Int {
        json.decodeToBackup().onSuccess { backup ->
            return importBackup(
                backup = backup,
                types = setOf(BackupType.CommandTemplate, BackupType.CommandShortcut)
            )
        }.onFailure { it.printStackTrace() }
        return 0
    }

    fun getPlaylistsFlow() = dao.getPlaylistsFlow()
    suspend fun getPlaylists() = dao.getPlaylists()
    suspend fun insertPlaylist(playlist: PlaylistEntry) = dao.insertPlaylist(playlist)
    suspend fun deletePlaylist(playlist: PlaylistEntry) = dao.deletePlaylist(playlist)
    suspend fun deletePlaylistById(id: Int) = dao.deletePlaylistById(id)
}