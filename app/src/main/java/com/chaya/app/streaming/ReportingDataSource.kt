package com.chaya.app.streaming

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Passes every call to [delegate] and tells [report] what the server answered when the request opens: its type
 * and length, or the HTTP status that refused it. A stream that downloads "fine" yet will not play (a server
 * answering each piece with a small error page) shows up only this way.
 */
class ReportingDataSource(
    private val delegate: DataSource,
    private val report: (String) -> Unit,
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val address = dataSpec.uri.toString()
        val length = try {
            delegate.open(dataSpec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            report("$address answered HTTP ${e.responseCode}")
            throw e
        } catch (e: java.io.IOException) {
            report("$address failed: ${e::class.simpleName}")
            throw e
        }
        val type = delegate.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()
        report("$address answered ${type ?: "no type"}, ${if (length >= 0) "$length bytes" else "length unknown"}")
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() = delegate.close()
}
