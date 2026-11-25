package com.chaya.app.speech

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
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
 * Keeps Chaya running while it makes subtitles, so the person can leave the app: a notification saying how far, with
 * *Stop*, and the processor kept awake (the screen may go off). Stops itself when no video is being made.
 */
class SpeechService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching: Job? = null
    private var awake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Making subtitles", NotificationManager.IMPORTANCE_LOW).apply {
            description = "How far making a video's subtitles has got"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as ChayaApplication
        if (intent?.action == ACTION_STOP) app.speech.stopAll()
        // At once: startForegroundService gives a few seconds before the system ends the app.
        startForeground(NOTIFICATION_ID, progress(app, app.speech.states.value))
        if (awake == null) {
            awake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chaya:subtitles")
                .apply {
                    setReferenceCounted(false)
                    acquire(AWAKE_AT_MOST_MS)
                }
        }
        if (watching?.isActive != true) {
            watching = scope.launch {
                app.speech.states.collectLatest { states ->
                    if (states.values.any { it.isWorking }) {
                        safeNotify(NOTIFICATION_ID, progress(app, states))
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 ends a data-sync service after six hours a day: what was heard is kept, to carry on from. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as ChayaApplication).speech.stopAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun progress(app: ChayaApplication, states: Map<Long, Making>): Notification {
        val titles = app.downloadManager.downloads.value.associate { it.id to (it.title ?: it.fileName) }
        val shown = SpeechNotice.of(states, titles)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SpeechService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(shown.title)
            .setContentText(shown.text)
            .setProgress(100, shown.percent ?: 0, shown.percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    private fun safeNotify(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        awake?.takeIf { it.isHeld }?.release()
        awake = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "chaya_subtitles"
        private const val NOTIFICATION_ID = 2
        private const val DONE_ID_BASE = 3000
        const val ACTION_STOP = "com.chaya.app.action.STOP_SUBTITLES"

        /** The processor is kept awake no longer than this, however long making takes. */
        private const val AWAKE_AT_MOST_MS = 6 * 60 * 60 * 1000L

        /** Keeps the app running while subtitles are made: from the person's tap, so the app is in front. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, SpeechService::class.java)) }
        }

        /** A video's subtitles are made, or could not be: said when the person may be elsewhere. */
        fun finished(context: Context, id: Long, title: String, ended: Making) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(title)
                .setContentText(if (ended is Making.Failed) ended.message else "Subtitles made")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify((DONE_ID_BASE + id).toInt(), notification) }
        }
    }
}

/** What the notification says while subtitles are made: [percent] null while only waiting or fetching. */
data class SpeechNotice(val title: String, val text: String, val percent: Int?) {
    companion object {
        fun of(states: Map<Long, Making>, titles: Map<Long, String>): SpeechNotice {
            val working = states.filterValues { it.isWorking }
            val listening = working.filterValues { it is Making.Listening }
            val waiting = working.count { it.value == Making.Waiting }
            val first = (listening.keys.firstOrNull() ?: working.keys.firstOrNull())
            val title = when {
                listening.size > 1 -> "Making subtitles for ${listening.size} videos"
                first != null -> titles[first] ?: "Making subtitles"
                else -> "Making subtitles"
            }
            val parts = listening.values.map { it as Making.Listening }.map { l ->
                if (l.service == null) "On the phone · ${l.percent}%" else "${l.service} · ${l.percent}%"
            } + working.values.filterIsInstance<Making.Fetching>().map { "Fetching the ${it.model.label} model" } +
                listOfNotNull(if (waiting > 0) "$waiting waiting" else null)
            val percent = listening.values.map { (it as Making.Listening).percent }.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }
            return SpeechNotice(title, parts.joinToString(" · ").ifEmpty { "Making subtitles" }, percent)
        }
    }
}
