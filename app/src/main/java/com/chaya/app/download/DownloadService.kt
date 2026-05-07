package com.chaya.app.download

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import com.chaya.app.ChayaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadService : Service() {
    private var observerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DownloadNotification.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as ChayaApplication

        // Handle notification actions
        when (intent?.action) {
            DownloadNotification.ACTION_CANCEL -> {
                val downloadId = intent.getLongExtra(
                    DownloadNotification.EXTRA_DOWNLOAD_ID, -1L
                )
                if (downloadId >= 0) {
                    app.downloadManager.cancelDownload(downloadId)
                }
            }
        }

        // Post a pending notification immediately so Android doesn't kill us.
        // The coroutine will update it with real progress once it starts.
        val snapshot = app.downloadManager.downloads.value
        val active = snapshot.filter {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED
        }
        if (active.isNotEmpty()) {
            val notification = DownloadNotification.buildProgressNotification(
                this, active.last()
            )
            startForeground(DownloadNotification.NOTIFICATION_ID, notification)
        }

        // Observe download state and keep notification updated
        if (observerJob == null || observerJob?.isActive != true) {
            observerJob = scope.launch {
                app.downloadManager.downloads.collectLatest { tasks ->
                    val currentActive = tasks.filter {
                        it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED
                    }

                    if (currentActive.isNotEmpty()) {
                        val notification = DownloadNotification.buildProgressNotification(
                            this@DownloadService,
                            currentActive.last()
                        )
                        startForeground(DownloadNotification.NOTIFICATION_ID, notification)
                    } else {
                        // Show completion notification briefly
                        val completed = tasks.filter { it.state == DownloadState.COMPLETED }
                        if (completed.isNotEmpty()) {
                            val notification = DownloadNotification.buildCompleteNotification(
                                this@DownloadService,
                                completed.last()
                            )
                            NotificationManagerCompat.from(this@DownloadService)
                                .notify(completed.last().id.toInt(), notification)
                        }
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
