package com.junkfood.seal.database

import androidx.room.DeleteTable
import androidx.room.migration.AutoMigrationSpec

@DeleteTable.Entries(
    DeleteTable(
        tableName = "DownloadedVideoInfo"
    )
)
class DeleteDownloadHistoryMigration : AutoMigrationSpec
