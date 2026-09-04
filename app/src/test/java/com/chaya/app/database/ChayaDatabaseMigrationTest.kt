package com.chaya.app.database

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.chaya.app.download.DownloadError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Proves the 2→3 migration preserves download history using plain
 * framework SQLite (no instrumentation harness needed): builds a real
 * v2-shaped database file, runs [ChayaDatabase.MIGRATION_2_3] against
 * it, and asserts rows survive with the new columns present and NULL.
 * Robolectric supplies the framework Context the SQLite helper needs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChayaDatabaseMigrationTest {

    /** Same table shape the shipped v2 app wrote. */
    private fun v2Callback() = object : SupportSQLiteOpenHelper.Callback(2) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE downloads (id INTEGER PRIMARY KEY NOT NULL, " +
                    "url TEXT NOT NULL, pageUrl TEXT, fileName TEXT NOT NULL, " +
                    "mimeType TEXT, file_path TEXT, exported_uri TEXT, " +
                    "error_message TEXT, downloaded_bytes INTEGER NOT NULL, " +
                    "total_bytes INTEGER, state TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
            )
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    /** Creates a v2-shaped database file the way the shipped app wrote it. */
    private fun createV2Database(): File {
        val dir = Files.createTempDirectory("chaya-mig-test").toFile()
        val file = File(dir, "test.db")
        // Robolectric provides a working Context so framework SQLite can open a real file.
        val context = RuntimeEnvironment.getApplication()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(v2Callback())
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.apply {
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
        helper.close()
        return file
    }

    @Test
    fun `migrate 2 to 3 preserves rows and adds nullable error columns`() {
        val file = createV2Database()
        try {
            // Same Robolectric Context for the migration pass.
            val context = RuntimeEnvironment.getApplication()
            val config = SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(file.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
            val helper = FrameworkSQLiteOpenHelperFactory().create(config)
            val db = helper.writableDatabase
            ChayaDatabase.MIGRATION_2_3.migrate(db)

            // v2 row keeps its legacy message; new columns exist and default to NULL.
            db.query("SELECT error_message, error_kind, error_code FROM downloads WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("HTTP 403: Forbidden", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }
            // Untouched healthy row survives verbatim.
            db.query("SELECT fileName, downloaded_bytes, state FROM downloads WHERE id = 2").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("b.mp4", cursor.getString(0))
                assertEquals(100L, cursor.getLong(1))
                assertEquals("PAUSED", cursor.getString(2))
            }
            db.close()
            helper.close()
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `legacy row without kind decodes to retryable unknown`() {
        val error = DownloadEntity.decodeError(null, null, "HTTP 403: Forbidden")

        assertTrue(error is DownloadError.Unknown)
        assertEquals("Something went wrong", error?.userMessage)
        assertEquals(true, error?.retryable)
    }

    @Test
    fun `classified error round-trips through kind and code`() {
        // Network/Unknown wrap a cause instance, so equality is on the
        // taxonomy slot (class + persisted columns), not data-class equals.
        for (original in listOf<DownloadError>(
            DownloadError.Network(RuntimeException("x")),
            DownloadError.HttpStatus(404),
            DownloadError.HttpStatus(503),
            DownloadError.StorageFull,
            DownloadError.UnsupportedFormat,
            DownloadError.Cancelled,
            DownloadError.Unknown(RuntimeException("x")),
        )) {
            val (kind, code, message) = DownloadEntity.encodeError(original)
            val decoded = DownloadEntity.decodeError(kind, code, message)

            assertEquals(original::class, decoded!!::class)
            assertEquals(original.userMessage, decoded.userMessage)
            assertEquals(original.retryable, decoded.retryable)
            if (original is DownloadError.HttpStatus) {
                assertEquals(original.code, (decoded as DownloadError.HttpStatus).code)
            }
        }
    }

    @Test
    fun `null error encodes to all-null columns`() {
        val (kind, code, message) = DownloadEntity.encodeError(null)

        assertNull(kind)
        assertNull(code)
        assertNull(message)
    }
}
