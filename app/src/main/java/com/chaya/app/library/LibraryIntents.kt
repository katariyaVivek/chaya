package com.chaya.app.library

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.chaya.app.download.DownloadState
import com.chaya.app.download.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Sharing, opening and saving the library's files through other apps. Nothing leaves the phone otherwise. */
object LibraryIntents {

    /** The type to tell other apps: the download's own, else one from its name. */
    fun mimeOf(name: String, mimeType: String? = null): String =
        mimeType?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    fun uriForFile(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Where another app can read a finished download: its file here, else its copy in the phone's folders. */
    fun uriFor(context: Context, task: DownloadTask): Uri? {
        if (task.state != DownloadState.COMPLETED) return null
        task.filePath?.let(::File)?.takeIf { it.isFile }?.let { return uriForFile(context, it) }
        return task.exportedUri?.let(Uri::parse)
    }

    /** A finished download a share sheet can take: a stream that lives only in the cache cannot be. */
    fun canShare(task: DownloadTask): Boolean =
        task.state == DownloadState.COMPLETED && (task.filePath != null || task.exportedUri != null)

    /** The share sheet for [uris], each readable by the app picked. */
    fun shareIntent(uris: List<Uri>, mimeTypes: List<String>): Intent? {
        if (uris.isEmpty()) return null
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = commonType(mimeTypes)
        send.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun viewIntent(uri: Uri, mimeType: String): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The one type they all have; else their shared family, such as any picture; else anything. */
    internal fun commonType(mimeTypes: List<String>): String {
        val distinct = mimeTypes.distinct()
        if (distinct.size == 1) return distinct.single()
        val families = distinct.map { it.substringBefore('/') }.distinct()
        return if (families.size == 1 && families.single().isNotEmpty()) "${families.single()}/*" else "*/*"
    }

    /**
     * Copies [file] into the phone's own folders (Pictures, Movies, Music or Download, by its type), where the
     * gallery and other apps see it. Needs Android 10 or newer; false when it could not be saved.
     */
    suspend fun saveToPhone(context: Context, file: File, name: String, mimeType: String): Boolean =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
            val (collection, folder) = when {
                mimeType.startsWith("video/") ->
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
                mimeType.startsWith("audio/") ->
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
                mimeType.startsWith("image/") ->
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
            }
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return@withContext false
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("Could not write $name")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                false
            }
        }
}
