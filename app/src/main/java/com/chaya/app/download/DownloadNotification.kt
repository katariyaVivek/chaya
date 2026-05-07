package com.chaya.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object DownloadNotification {
    const val CHANNEL_ID = "chaya_downloads"
    const val NOTIFICATION_ID = 1

    const val ACTION_CANCEL = "com.chaya.app.action.CANCEL_DOWNLOAD"
    const val EXTRA_DOWNLOAD_ID = "download_id"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active download progress"
            setShowBadge(false)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun buildProgressNotification(
        context: Context,
        task: DownloadTask
    ): Notification {
        val cancelIntent = Intent(context, DownloadService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_DOWNLOAD_ID, task.id)
        }
        val cancelPendingIntent = PendingIntent.getForegroundService(
            context,
            task.id.toInt(),
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val progressMax = task.totalBytes?.toInt() ?: 0
        val isIndeterminate = task.totalBytes == null || task.totalBytes <= 0

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task.fileName)
            .setContentText(formatBytes(task.downloadedBytes, task.totalBytes))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(progressMax, task.downloadedBytes.toInt(), isIndeterminate)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent
            )
            .build()
    }

    fun buildCompleteNotification(context: Context, task: DownloadTask): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(task.fileName)
            .setContentText("Download complete")
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }

    private fun formatBytes(downloaded: Long, total: Long?): String {
        val dl = formatFileSize(downloaded)
        return if (total != null && total > 0) {
            "$dl / ${formatFileSize(total)}"
        } else {
            dl
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
