package com.chaya.app.streaming

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a server answered for a stream's request is told, so a stream of error pages can be seen as one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportingDataSourceTest {

    private class Answering(
        private val headers: Map<String, List<String>>,
        private val length: Long,
        private val failure: java.io.IOException? = null,
    ) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = failure?.let { throw it } ?: length
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun getUri(): Uri? = null
        override fun getResponseHeaders(): Map<String, List<String>> = headers
        override fun close() = Unit
    }

    private val spec = DataSpec(Uri.parse("https://cdn.example/s/seg-1.ts"))

    @Test
    fun `an answer is told with its type and length`() {
        val told = mutableListOf<String>()
        ReportingDataSource(Answering(mapOf("content-type" to listOf("text/html")), 712), told::add).open(spec)

        assertEquals(listOf("https://cdn.example/s/seg-1.ts answered text/html, 712 bytes"), told)
    }

    @Test
    fun `a refusal is told with its status and still thrown`() {
        val told = mutableListOf<String>()
        val refused = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), spec, ByteArray(0))

        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            ReportingDataSource(Answering(emptyMap(), 0, refused), told::add).open(spec)
        }
        assertEquals(listOf("https://cdn.example/s/seg-1.ts answered HTTP 403"), told)
    }
}
