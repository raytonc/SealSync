package com.junkfood.seal.database.backup

import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.database.objects.OptionShortcut
import com.junkfood.seal.util.DatabaseUtil
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object BackupUtil {
    private val format = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun exportTemplatesToJson() =
        exportTemplatesToJson(
            templates = DatabaseUtil.getTemplateList(),
            shortcuts = DatabaseUtil.getShortcutList()
        )

    fun exportTemplatesToJson(
        templates: List<CommandTemplate>,
        shortcuts: List<OptionShortcut>
    ): String {
        return format.encodeToString(
            Backup(
                templates = templates, shortcuts = shortcuts
            )
        )
    }

    fun String.decodeToBackup(): Result<Backup> {
        return format.runCatching {
            decodeFromString<Backup>(this@decodeToBackup)
        }
    }

    enum class BackupDestination {
        File, Clipboard
    }

    enum class BackupType {
        CommandTemplate, CommandShortcut
    }
}