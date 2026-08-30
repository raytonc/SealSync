package com.junkfood.seal.database

import androidx.room.DeleteTable
import androidx.room.migration.AutoMigrationSpec

/**
 * Drops the tables left over from the upstream general-purpose yt-dlp front-end.
 *
 * SealSync only syncs playlists: custom command templates, their option shortcuts, and
 * WebView cookie profiles have no UI to create them and nothing left that reads them.
 */
@DeleteTable.Entries(
    DeleteTable(tableName = "CommandTemplate"),
    DeleteTable(tableName = "OptionShortcut"),
    DeleteTable(tableName = "CookieProfile"),
)
class DropUnusedTablesMigration : AutoMigrationSpec
