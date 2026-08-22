package com.chaya.app.download

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.chaya.app.ChayaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps downloads alive in the background and shows
 * a progress notification.
 */
class DownloadService : Service() {
    private var observerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** Completion notifications already shown during this service lifetime. */
    private val notifiedCompleted = mutableSetOf<Long>()

    override fun onCreate() {
        super.onCreate()
        DownloadNotification.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as ChayaApplication

        when (intent?.action) {
            DownloadNotification.ACTION_CANCEL -> {
                app.downloadManager.cancelDownload(
                    intent.getLongExtra(DownloadNotification.EXTRA_DOWNLOAD_ID, -1L)
                )
            }
            DownloadNotification.ACTION_PAUSE -> {
                app.downloadManager.pauseDownload(
                    intent.getLongExtra(DownloadNotification.EXTRA_DOWNLOAD_ID, -1L)
                )
            }
        }

        // ALWAYS promote to foreground immediately — startForegroundService
        // gives us ~5 seconds or the system crashes the app.
        val active = app.downloadManager.downloads.value.filter { it.state.isActive }
        startForeground(
            DownloadNotification.NOTIFICATION_ID,
            if (active.isNotEmpty()) {
                DownloadNotification.buildProgressNotification(this, active.last())
            } else {
                DownloadNotification.buildIdleNotification(this)
            }
        )

        if (observerJob?.isActive != true) {
            observerJob = scope.launch {
                app.downloadManager.downloads.collectLatest { tasks ->
                    val currentActive = tasks.filter { it.state.isActive }

                    if (currentActive.isNotEmpty()) {
                        startForeground(
                            DownloadNotification.NOTIFICATION_ID,
                            DownloadNotification.buildProgressNotification(
                                this@DownloadService,
                                currentActive.last()
                            )
                        )
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)

                        // One completion notification per finished download.
                        tasks
                            .filter {
                                it.state == DownloadState.COMPLETED &&
                                        it.id !in notifiedCompleted
                            }
                            .forEach { t ->
                                notifiedCompleted += t.id
                                safeNotify(
                                    (DownloadNotification.COMPLETE_ID_BASE + t.id).toInt(),
                                    DownloadNotification.buildCompleteNotification(this@DownloadService, t)
                                )
                            }

                        stopSelf()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun safeNotify(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Active states worth keeping a foreground service around for. */
private val DownloadState.isActive: Boolean
    get() = this == DownloadState.DOWNLOADING || this == DownloadState.QUEUED
