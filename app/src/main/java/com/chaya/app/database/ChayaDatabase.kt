package com.chaya.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DownloadEntity::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ChayaDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

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
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
