package com.chaya.app.detection

import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.MediaKind

/** One sheet entry: a detected item plus everything needed to present it plainly. */
data class RankedMedia(
    val media: DetectedMedia,
    val kind: MediaKind,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val durationSeconds: Double?,
    val videoHeight: Int?,
    val isLikelyAd: Boolean,
    val score: Int,
    /** Single-quality playlists of the same stream, folded under this entry. */
    val alternates: List<DetectedMedia> = emptyList(),
) {
    /** Base name for the saved file, e.g. "Big Buck Bunny (720p)". */
    val fileBaseName: String get() = MediaNamer.fileBaseName(title, videoHeight)
}

/** What the media sheet shows: the main item, the rest of the page's media, likely ads, and hidden stream pieces. */
data class MediaSheetModel(
    val primary: RankedMedia?,
    val others: List<RankedMedia>,
    val likelyAds: List<RankedMedia>,
    val hiddenSegmentCount: Int,
) {
    /** Items worth a badge: the main item plus other non-ad media. */
    val visibleCount: Int get() = (if (primary != null) 1 else 0) + others.size

    val isEmpty: Boolean get() = primary == null && others.isEmpty() && likelyAds.isEmpty()

    companion object {
        val EMPTY = MediaSheetModel(null, emptyList(), emptyList(), 0)
    }
}

/**
 * Picks the page's main video out of everything the detectors saw. Signals:
 * declared page video (og:video, JSON-LD), stream manifests, the largest
 * visible playing player, duration (ad-length clips rank down), and ad
 * hosts or ad containers (always ranked out of the main list).
 */
object MediaRanker {

    /** Players smaller than this (CSS px²) are thumbnails, previews, or trackers. */
    private const val TINY_AREA = 120 * 90

    /** Playlist names that denote one rendition of a stream rather than the stream itself. */
    private val variantNamePattern = Regex(
        "chunklist|variant|rendition|level|quality|audio|index_?\\d|media_?\\d|playlist_?\\d|" +
            "stream_?\\d|video_?\\d|\\d{3,4}p|_\\d+k|(^|[-_])(hd|sd|low|mid|high)([-_.]|$)",
        RegexOption.IGNORE_CASE,
    )

    private data class Scored(
        val media: DetectedMedia,
        val kind: MediaKind,
        val player: PlayerMeta?,
        val matchesPageVideo: Boolean,
        val isAd: Boolean,
        val durationSeconds: Double?,
        val score: Int,
    )

    fun rank(
        media: List<DetectedMedia>,
        pageMeta: PageMeta?,
        pageUrl: String?,
        pageTitle: String? = null,
        hiddenSegmentCount: Int = 0,
    ): MediaSheetModel {
        val candidates = media
            .filter { kindOf(it) != MediaKind.SEGMENT }
            .distinctBy { MediaUrlClassifier.withoutRangeParams(it.url) }
            .sortedBy { it.detectedAt }
        if (candidates.isEmpty()) {
            return MediaSheetModel(null, emptyList(), emptyList(), hiddenSegmentCount)
        }

        // Fold single-quality playlists under the first playlist of their stream.
        val heads = mutableListOf<DetectedMedia>()
        val alternates = mutableMapOf<DetectedMedia, MutableList<DetectedMedia>>()
        for (item in candidates) {
            val parent = if (kindOf(item) == MediaKind.STREAM) {
                heads.firstOrNull { kindOf(it) == MediaKind.STREAM && isVariantOf(item, it) }
            } else null
            if (parent != null) alternates.getOrPut(parent) { mutableListOf() } += item else heads += item
        }

        val players = pageMeta?.players.orEmpty()
        val largestVideoArea = players.filter { it.visible && !it.isAudio }.maxOfOrNull { it.area } ?: 0
        val declaredVideos = pageMeta?.videoUrls.orEmpty()

        // MediaSource players (blob: src) expose no URL; pair them with streams in detection order.
        val blobPlayers = players.filter { it.isBlob && !it.isAudio }.sortedByDescending { it.area }
        val streamPlayers = heads
            .filter { kindOf(it) == MediaKind.STREAM && !AdHosts.isAdUrl(it.url) }
            .zip(blobPlayers)
            .toMap()

        val scored = heads.map { item ->
            val kind = kindOf(item)
            val player = players.firstOrNull { it.src != null && sameResource(it.src, item.url) }
                ?: streamPlayers[item]
            val matchesPageVideo = declaredVideos.any { sameResource(it, item.url) }
            val isAd = AdHosts.isAdUrl(item.url) || player?.inAdContainer == true
            val duration = player?.durationSeconds
                ?: if (matchesPageVideo) pageMeta?.ldDurationSeconds else null
            val isLargest = player != null && player.visible && !player.isAudio &&
                player.area > 0 && player.area == largestVideoArea
            Scored(
                media = item,
                kind = kind,
                player = player,
                matchesPageVideo = matchesPageVideo,
                isAd = isAd,
                durationSeconds = duration,
                score = score(kind, player, isLargest, matchesPageVideo, isAd, duration),
            )
        }

        val (ads, content) = scored.partition { it.isAd }
        val ordered = content.sortedWith(
            compareByDescending<Scored> { it.score }.thenBy { it.media.detectedAt }
        )
        val primaryScored = ordered.firstOrNull()

        // Ordinals count every item of a kind in sheet order, so others read "Video 2" after a main video.
        val ordinals = mutableMapOf<MediaKind, Int>()
        fun nextOrdinal(kind: MediaKind): Int {
            val next = (ordinals[kind] ?: 0) + 1
            ordinals[kind] = next
            return next
        }

        val primary = primaryScored?.let { s ->
            nextOrdinal(s.kind)
            s.toRanked(
                title = MediaNamer.primaryTitle(
                    url = s.media.url,
                    player = s.player,
                    pageMeta = pageMeta,
                    matchesPageVideo = s.matchesPageVideo,
                    fallbackPageTitle = pageTitle,
                    pageUrl = pageUrl,
                ),
                thumbnail = s.player?.poster ?: pageMeta?.ogImage ?: pageMeta?.ldThumbnail,
                alternates = alternates[s.media].orEmpty(),
            )
        }
        val others = ordered.drop(1).map { s ->
            s.toRanked(
                title = MediaNamer.otherTitle(s.media.url, s.player, s.kind, nextOrdinal(s.kind)),
                thumbnail = s.player?.poster,
                alternates = alternates[s.media].orEmpty(),
            )
        }
        var adOrdinal = 0
        val likelyAds = ads.sortedBy { it.media.detectedAt }.map { s ->
            adOrdinal++
            s.toRanked(
                title = s.player?.title ?: MediaNamer.humanFileName(s.media.url) ?: "Ad $adOrdinal",
                thumbnail = s.player?.poster,
                alternates = alternates[s.media].orEmpty(),
            )
        }

        return MediaSheetModel(primary, others, likelyAds, hiddenSegmentCount)
    }

    private fun Scored.toRanked(
        title: String,
        thumbnail: String?,
        alternates: List<DetectedMedia>,
    ): RankedMedia {
        // A stream's currently playing rendition is not its quality; the picker offers the real choices.
        val height = if (kind == MediaKind.STREAM) null else player?.videoHeight
        return RankedMedia(
            media = media,
            kind = kind,
            title = title,
            subtitle = MediaNamer.subtitle(media.url, kind, isAd, durationSeconds, height, media.mimeType),
            thumbnailUrl = thumbnail,
            durationSeconds = durationSeconds,
            videoHeight = height,
            isLikelyAd = isAd,
            score = score,
            alternates = alternates,
        )
    }

    /** Additive evidence; weights are pinned by MediaRankerTest fixtures rather than tuned per site. */
    private fun score(
        kind: MediaKind,
        player: PlayerMeta?,
        isLargest: Boolean,
        matchesPageVideo: Boolean,
        isAd: Boolean,
        durationSeconds: Double?,
    ): Int {
        var score = 0
        if (matchesPageVideo) score += 100
        score += when (kind) {
            MediaKind.STREAM -> 30
            MediaKind.VIDEO -> 10
            else -> 0
        }
        if (player != null) {
            score += if (player.visible) 10 else -15
            if (isLargest) score += 35
            if (player.playing || player.played) score += 15
            if (player.visible && player.area in 1 until TINY_AREA) score -= 15
            if (player.muted && player.autoplay && player.loop && !player.controls) score -= 20
            if (player.poster != null) score += 5
        }
        durationSeconds?.let { d ->
            score += when {
                d >= 60 -> 20
                d >= 31 -> 10
                d > 0 -> -25
                else -> 0
            }
        }
        if (isAd) score -= 100
        return score
    }

    private fun kindOf(media: DetectedMedia): MediaKind =
        MediaUrlClassifier.kindOf(media.url, media.mimeType)

    /** A rendition playlist lives in a subdirectory of the master's, or beside it with a rendition-style name. */
    private fun isVariantOf(child: DetectedMedia, head: DetectedMedia): Boolean {
        val childHost = MediaUrlClassifier.hostOf(child.url) ?: return false
        if (childHost != MediaUrlClassifier.hostOf(head.url)) return false
        val headDir = MediaUrlClassifier.pathOf(head.url).substringBeforeLast('/') + "/"
        val childPath = MediaUrlClassifier.pathOf(child.url)
        val childDir = childPath.substringBeforeLast('/') + "/"
        if (childDir.length > headDir.length && childDir.startsWith(headDir)) return true
        return childDir == headDir && variantNamePattern.containsMatchIn(childPath.substringAfterLast('/'))
    }

    /** Same host and path; queries differ between signed copies of one file. */
    private fun sameResource(a: String, b: String): Boolean {
        val hostA = MediaUrlClassifier.hostOf(a) ?: return false
        return hostA == MediaUrlClassifier.hostOf(b) &&
            MediaUrlClassifier.pathOf(a) == MediaUrlClassifier.pathOf(b)
    }
}
