package com.junkfood.seal.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import com.junkfood.seal.database.objects.PlaylistEntry
import com.junkfood.seal.database.objects.TrackTag

@Database(
    entities = [PlaylistEntry::class, TrackTag::class],
    version = 10,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7, spec = DeleteDownloadHistoryMigration::class),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = DropUnusedTablesMigration::class),
        AutoMigration(from = 9, to = 10),
    ]
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoInfoDao(): VideoInfoDao
}
