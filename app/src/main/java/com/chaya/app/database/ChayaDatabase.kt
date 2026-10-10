package com.chaya.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DownloadEntity::class, HistoryEntity::class, BookmarkEntity::class],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ChayaDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        /** Adds the 2.2 error-taxonomy columns; existing rows keep their legacy message. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN error_kind TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN error_code INTEGER")
            }
        }

        /** Adds the title, thumbnail and quality the redesigned downloads list shows; old rows keep NULLs. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN title TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN thumbnail_url TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN quality_height INTEGER")
            }
        }

        /** Adds what a picture-plus-sound download needs: the engine's request headers and the sound file's address. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN request_headers TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN audio_url TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN audio_request_headers TEXT")
            }
        }

        /** Adds the browser's history and bookmarks; downloads are untouched. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `history` (`url` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`visitedAt` INTEGER NOT NULL, `visits` INTEGER NOT NULL, PRIMARY KEY(`url`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (`url` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`url`))",
                )
            }
        }

        /** Adds the key that ties the files of one post together for the library; older rows stay ungrouped. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN group_key TEXT")
            }
        }

        @Volatile
        private var INSTANCE: ChayaDatabase? = null

        fun getInstance(context: Context): ChayaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChayaDatabase::class.java,
                    "chaya.db"
                )
                    // Explicit migration chain — never wipe user history on upgrade.
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
