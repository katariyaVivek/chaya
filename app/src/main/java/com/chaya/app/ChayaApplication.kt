package com.chaya.app

import android.app.Application
import android.os.Build
import android.os.StrictMode
import com.chaya.app.database.ChayaDatabase
import com.chaya.app.diagnostics.CrashReporter
import com.chaya.app.diagnostics.EventLog
import com.chaya.app.download.DownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ChayaApplication : Application() {
    lateinit var downloadManager: DownloadManager
        private set

    val database: ChayaDatabase by lazy { ChayaDatabase.getInstance(this) }

    /** On-device diagnostics ring buffer + rotating log (Phase 2.3). */
    val eventLog: EventLog by lazy { EventLog(this) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // Fail fast on main-thread disk/network during development; release
            // builds never pay for this. Log-only (no penaltyDeath) so a single
            // slip in a manual walkthrough doesn't kill the debug session.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }
        CrashReporter.install(this, eventLog)
        val dao = database.downloadDao()
        downloadManager = DownloadManager(this, dao, eventLog = eventLog)
        // Restore persisted downloads from Room
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            downloadManager.restore()
        }
    }
}
