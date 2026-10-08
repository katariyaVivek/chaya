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

    /** Runs [migrations] in order against a fresh v2 file and hands the open database to [check]. */
    private fun migratedFromV2(vararg migrations: androidx.room.migration.Migration, check: (SupportSQLiteDatabase) -> Unit) {
        val file = createV2Database()
        try {
            val context = RuntimeEnvironment.getApplication()
            val config = SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(file.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
            val helper = FrameworkSQLiteOpenHelperFactory().create(config)
            val db = helper.writableDatabase
            migrations.forEach { it.migrate(db) }
            check(db)
            db.close()
            helper.close()
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `migrate 2 to 4 keeps every row and adds empty title, thumbnail and quality`() {
        migratedFromV2(ChayaDatabase.MIGRATION_2_3, ChayaDatabase.MIGRATION_3_4) { db ->
            db.query("SELECT fileName, title, thumbnail_url, quality_height FROM downloads ORDER BY id").use { cursor ->
                assertEquals(2, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals("a.mp4", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
                assertTrue(cursor.moveToNext())
                assertEquals("b.mp4", cursor.getString(0))
            }
        }
    }

    @Test
    fun `new columns accept a title, a poster and a quality after migrating`() {
        migratedFromV2(ChayaDatabase.MIGRATION_2_3, ChayaDatabase.MIGRATION_3_4) { db ->
            db.execSQL(
                "UPDATE downloads SET title = 'Big Buck Bunny', " +
                    "thumbnail_url = 'https://img.example/poster.jpg', quality_height = 720 WHERE id = 2",
            )
            db.query("SELECT title, thumbnail_url, quality_height FROM downloads WHERE id = 2").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Big Buck Bunny", cursor.getString(0))
                assertEquals("https://img.example/poster.jpg", cursor.getString(1))
                assertEquals(720, cursor.getInt(2))
            }
        }
    }

    @Test
    fun `migrate 2 to 5 keeps every row and adds empty request headers and no sound file`() {
        migratedFromV2(ChayaDatabase.MIGRATION_2_3, ChayaDatabase.MIGRATION_3_4, ChayaDatabase.MIGRATION_4_5) { db ->
            db.query("SELECT fileName, request_headers, audio_url, audio_request_headers FROM downloads ORDER BY id")
                .use { cursor ->
                    assertEquals(2, cursor.count)
                    assertTrue(cursor.moveToFirst())
                    assertEquals("a.mp4", cursor.getString(0))
                    assertTrue(cursor.isNull(1))
                    assertTrue(cursor.isNull(2))
                    assertTrue(cursor.isNull(3))
                    assertTrue(cursor.moveToNext())
                    assertEquals("b.mp4", cursor.getString(0))
                }
        }
    }

    @Test
    fun `migrated rows decode into tasks with no engine request`() {
        migratedFromV2(ChayaDatabase.MIGRATION_2_3, ChayaDatabase.MIGRATION_3_4, ChayaDatabase.MIGRATION_4_5) { db ->
            db.execSQL(
                "UPDATE downloads SET request_headers = 'User-Agent: UA\nReferer: https://site.example/', " +
                    "audio_url = 'https://cdn.example/audio', audio_request_headers = 'User-Agent: UA2' WHERE id = 2",
            )
            db.query("SELECT request_headers, audio_url, audio_request_headers FROM downloads WHERE id = 2").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val entity = DownloadEntity(
                    id = 2, url = "u", pageUrl = null, fileName = "b.mp4", mimeType = null, filePath = null,
                    totalBytes = null,
                    requestHeaders = cursor.getString(0),
                    audioUrl = cursor.getString(1),
                    audioRequestHeaders = cursor.getString(2),
                )
                val task = entity.toTask()

                assertEquals(mapOf("User-Agent" to "UA", "Referer" to "https://site.example/"), task.requestHeaders)
                assertEquals("https://cdn.example/audio", task.audioUrl)
                assertEquals(mapOf("User-Agent" to "UA2"), task.audioRequestHeaders)
                assertTrue(task.hasOwnRequest)
            }
        }
    }

    @Test
    fun `an old row has no engine request`() {
        val task = DownloadEntity(
            id = 1, url = "u", pageUrl = null, fileName = "a.mp4", mimeType = null, filePath = null, totalBytes = null,
        ).toTask()

        assertEquals(emptyMap<String, String>(), task.requestHeaders)
        assertNull(task.audioUrl)
        assertEquals(false, task.hasOwnRequest)
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
            DownloadError.CouldNotCombine(RuntimeException("x")),
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
