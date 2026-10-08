package com.chaya.app.platform

import com.chaya.app.download.DownloadNotification.formatFileSize

/** One thing a person can pick: a quality of the video, or just its audio. */
data class PlatformChoice(
    val label: String,
    /** Facts under the label, e.g. "MP4 · ≈ 48.0 MB". */
    val detail: String,
    /** Picture quality in pixels (the shorter side); null for audio only. */
    val quality: Int?,
    /** What gets downloaded: a complete video, a picture-only track, or an audio-only track. */
    val file: PlatformFormat,
    /** Sound to join to [file] when it has none of its own; null when [file] is already complete. */
    val audioToMerge: PlatformFormat?,
    val isAudioOnly: Boolean,
) {
    val needsMerge: Boolean get() = audioToMerge != null

    /** Total download size, when the sites said how big the pieces are. */
    val sizeBytes: Long?
        get() = file.sizeBytes?.let { it + (audioToMerge?.sizeBytes ?: 0L) }
}

/**
 * Turns what yt-dlp found into choices a person can read, best first, ending with "Audio only".
 * Ported from sancika's `qualityGroupsFromYtDlpInfo` and its format preferences, adjusted for a phone:
 * only what chaya can really save is offered.
 */
object FormatSelector {

    /** Prefers MP4 (plays everywhere), then the higher bitrate. */
    private val byPreference = compareByDescending<PlatformFormat> { it.ext == "mp4" }
        .thenByDescending { it.bitrateKbps ?: 0.0 }

    /** For picture-only tracks that will be joined to sound: MP4 container, then H.264, then bitrate. */
    private val byMergeCompatibility = compareByDescending<PlatformFormat> { it.ext == "mp4" }
        .thenByDescending { it.videoCodec?.startsWith("avc") == true }
        .thenByDescending { it.bitrateKbps ?: 0.0 }

    /** M4A first (it joins to MP4 without re-encoding), then the higher bitrate. */
    private val byAudioPreference = compareByDescending<PlatformFormat> { it.ext == "m4a" }
        .thenByDescending { it.audioBitrateKbps ?: it.bitrateKbps ?: 0.0 }

    /**
     * [canMerge] says whether the app can join a picture-only file to a separate sound file. While it
     * cannot, qualities that exist only as separate tracks (most of YouTube's HD) are left out
     * rather than offered and then failing.
     */
    fun choices(media: PlatformMedia, canMerge: Boolean): List<PlatformChoice> {
        // Saving a live broadcast needs a different approach than saving a finished video.
        if (media.isLive) return emptyList()

        val audioOnly = media.formats.filter { it.isAudioOnly && it.isDirectFile }
        val bestAudio = audioOnly.sortedWith(byAudioPreference).firstOrNull()
        val mergeAudio = audioOnly.filter { it.ext == "m4a" || it.ext == "mp4" }
            .sortedWith(byAudioPreference).firstOrNull()

        val usableMergeAudio = mergeAudio.takeIf { canMerge }
        val choices = media.formats
            .filter { it.hasVideo && !it.isAudioOnly }
            .mapNotNull { format -> format.quality?.let { quality -> quality to format } }
            .groupBy({ (quality, _) -> quality }, { (_, format) -> format })
            .toSortedMap(reverseOrder())
            .mapNotNull { (quality, candidates) -> choiceFor(quality, candidates, usableMergeAudio) }
            .toMutableList()

        bestAudio?.let { choices += audioChoice(it) }
        return choices
    }

    /**
     * One choice per quality. A ready-made file wins; then, when merging is possible, a separate picture
     * and sound (a real file in the end); a streaming manifest is the last resort.
     */
    private fun choiceFor(quality: Int, candidates: List<PlatformFormat>, mergeAudio: PlatformFormat?): PlatformChoice? {
        val completeFile = candidates.filter { it.isCompleteFile }.sortedWith(byPreference).firstOrNull()
        val pictureOnly = candidates
            .filter { it.isDirectFile && it.audioCodec == "none" && it.ext == "mp4" }
            .sortedWith(byMergeCompatibility).firstOrNull()
        val completeStream = candidates.filter { !it.isDirectFile && it.hasAudio }.sortedWith(byPreference).firstOrNull()

        return when {
            completeFile != null -> videoChoice(quality, completeFile, null)
            pictureOnly != null && mergeAudio != null -> videoChoice(quality, pictureOnly, mergeAudio)
            completeStream != null -> videoChoice(quality, completeStream, null)
            else -> null
        }
    }

    private fun videoChoice(quality: Int, file: PlatformFormat, audio: PlatformFormat?): PlatformChoice {
        val fps = file.fps?.takeIf { it > 30 }?.toInt()
        val choice = PlatformChoice(
            label = "${quality}p" + (fps?.toString() ?: ""),
            detail = "",
            quality = quality,
            file = file,
            audioToMerge = audio,
            isAudioOnly = false,
        )
        return choice.copy(detail = detailOf(choice))
    }

    private fun audioChoice(file: PlatformFormat): PlatformChoice {
        val choice = PlatformChoice(
            label = "Audio only",
            detail = "",
            quality = null,
            file = file,
            audioToMerge = null,
            isAudioOnly = true,
        )
        return choice.copy(detail = detailOf(choice))
    }

    private fun detailOf(choice: PlatformChoice): String =
        listOfNotNull(
            choice.file.ext?.uppercase(),
            choice.sizeBytes?.takeIf { it > 0 }?.let { "≈ ${formatFileSize(it)}" },
        ).joinToString(" · ")
}
