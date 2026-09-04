package com.chaya.app.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the 2→3 migration preserves download history: pre-taxonomy rows
 * keep their legacy message, gain the new columns, and decode back through
 * [DownloadEntity.decodeError] instead of being wiped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChayaDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChayaDatabase::class.java,
    )

    @Test
    fun `migrate 2 to 3 preserves rows and exposes new error columns`() {
        // Build a real v2 database the way the shipped app wrote it.
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO downloads (id, url, pageUrl, fileName, mimeType, " +
                    "file_path, exported_uri, error_message, downloaded_bytes, " +
                    "total_bytes, state, created_at, updated_at) " +
                    "VALUES (1, 'https://cdn.example.com/a.mp4', NULL, 'a.mp4', " +
                    "'video/mp4', NULL, NULL, 'HTTP 403: Forbidden', 0, NULL, " +
                    "'FAILED', 0, 0)",
            )
            execSQL(
                "INSERT INTO downloads (id, url, pageUrl, fileName, mimeType, " +
                    "file_path, exported_uri, error_message, downloaded_bytes, " +
                    "total_bytes, state, created_at, updated_at) " +
                    "VALUES (2, 'https://cdn.example.com/b.mp4', NULL, 'b.mp4', " +
                    "'video/mp4', '/tmp/b.mp4', NULL, NULL, 100, 200, " +
                    "'PAUSED', 0, 0)",
            )
            close()
        }

        // Run the migration and validate the schema, not just the data.
        helper.runMigrationsAndValidate(TEST_DB, 3, true, ChayaDatabase.MIGRATION_2_3).apply {
            // v2 row keeps its legacy message; new columns default to NULL.
            query("SELECT error_message, error_kind, error_code FROM downloads WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("HTTP 403: Forbidden", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }
            // Untouched healthy row survives verbatim.
            query("SELECT fileName, downloaded_bytes, state FROM downloads WHERE id = 2").use { cursor ->
                cursor.moveToFirst()
                assertEquals("b.mp4", cursor.getString(0))
                assertEquals(100L, cursor.getLong(1))
                assertEquals("PAUSED", cursor.getString(2))
            }
            close()
        }

        // Reopen through Room itself and confirm the DAO decodes the legacy row.
        val db = androidx.room.Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ChayaDatabase::class.java,
            TEST_DB,
        ).addMigrations(ChayaDatabase.MIGRATION_2_3).build()
        try {
            val legacy = runBlocking { db.downloadDao().getById(1) }!!
            // kind/code are NULL pre-classification, so decodeError falls back
            // to the legacy message wrapped as an Unknown (retry stays on).
            assertEquals("HTTP 403: Forbidden", legacy.errorMessage)
            assertNull(legacy.errorKind)
            val task = legacy.toTask()
            assertEquals("Something went wrong", task.error?.userMessage)
            assertEquals(true, task.error?.retryable)
        } finally {
            db.close()
        }
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
