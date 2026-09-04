package com.chaya.app

import android.app.Application
import com.chaya.app.database.ChayaDatabase
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
        val dao = database.downloadDao()
        downloadManager = DownloadManager(this, dao, eventLog = eventLog)
        // Restore persisted downloads from Room
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            downloadManager.restore()
        }
    }
}
