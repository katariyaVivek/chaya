package com.chaya.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The lifecycle questions every screen asks of a download, answered once. Each set is spelled out, so a new
 * state has to be placed in every one of them deliberately.
 */
class DownloadStateTest {

    private fun where(question: (DownloadState) -> Boolean) = DownloadState.entries.filter(question).toSet()

    @Test
    fun `active is running or about to run`() {
        assertEquals(setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING), where { it.isActive })
    }

    @Test
    fun `unfinished adds paused to active`() {
        assertEquals(
            setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.PAUSED),
            where { it.isUnfinished },
        )
    }

    @Test
    fun `resume and retry carry on from a pause, a failure or a cancel`() {
        assertEquals(
            setOf(DownloadState.PAUSED, DownloadState.FAILED, DownloadState.CANCELLED),
            where { it.canResume },
        )
    }

    @Test
    fun `nothing active can be resumed, and a finished download is neither`() {
        DownloadState.entries.forEach { state ->
            assertFalse(state.name, state.isActive && state.canResume)
        }
        assertFalse(DownloadState.COMPLETED.isUnfinished || DownloadState.COMPLETED.canResume)
    }
}
