package com.chaya.app.diagnostics

/**
 * Local-only aggregates over [ChayaEvent] entries (Phase 2.4): detection
 * coverage per domain and download success/fail rate. Computed on-device
 * from the in-memory [EventLog] snapshot, displayed locally, never
 * transmitted anywhere — no network client exists in this feature's diff.
 */
data class Insights(
    /** Media detected per domain (host of the page URL), most-active first. */
    val detectedPerDomain: List<Pair<String, Int>>,
    /** Terminal download outcomes. */
    val completed: Int,
    val failed: Int,
    /** Failures by error kind (e.g. HttpStatus, Network). */
    val failuresByKind: List<Pair<String, Int>>,
) {
    /** Success rate over finished downloads; null when nothing finished. */
    val successRate: Float?
        get() {
            val total = completed + failed
            return if (total == 0) null else completed.toFloat() / total
        }

    companion object {
        /**
         * Folds one event snapshot into aggregates; pure function, unit-tested.
         * Detection counts attribute to the media URL's host (the CDN actually
         * serving the file) — page attribution would need the pageUrl field
         * the event deliberately does not carry (scrubbed at the boundary).
         */
        fun compute(events: List<ChayaEvent>): Insights {
            val perDomain = mutableMapOf<String, Int>()
            var completed = 0
            var failed = 0
            val byKind = mutableMapOf<String, Int>()
            for (event in events) {
                when (event) {
                    is ChayaEvent.MediaDetected -> {
                        val domain = hostOf(event.url) ?: continue
                        perDomain[domain] = (perDomain[domain] ?: 0) + 1
                    }
                    is ChayaEvent.DownloadStateChanged -> {
                        if (event.to == "COMPLETED") completed++
                    }
                    is ChayaEvent.DownloadFailed -> {
                        failed++
                        byKind[event.errorKind] = (byKind[event.errorKind] ?: 0) + 1
                    }
                    else -> Unit
                }
            }
            return Insights(
                detectedPerDomain = perDomain.entries
                    .sortedByDescending { it.value }
                    .map { it.key to it.value },
                completed = completed,
                failed = failed,
                failuresByKind = byKind.entries
                    .sortedByDescending { it.value }
                    .map { it.key to it.value },
            )
        }

        /** Host of a scrubbed http(s) URL; null for anything else. */
        private fun hostOf(url: String): String? {
            return try {
                val uri = android.net.Uri.parse(url)
                uri.host?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
