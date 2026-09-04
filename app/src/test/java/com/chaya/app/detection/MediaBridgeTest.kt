package com.chaya.app.detection

import com.chaya.app.model.DetectionSource
import com.chaya.app.model.DetectedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the capability and consent boundary between page JavaScript and
 * native detection so frames and hostile pages cannot start unbounded HEAD work.
 */
class MediaBridgeTest {

    /** Ensures a matching native opt-in is required before an unknown endpoint reaches verification. */
    @Test
    fun `same-origin candidates are rejected before opt-in and forwarded after matching opt-in`() {
        val candidates = mutableListOf<Pair<DetectedMedia, Long>>()
        val pageUrl = "https://media.example/watch"
        val generation = 41L
        val bridge = MediaBridge(
            onMediaDetected = { _, _ -> error("candidate must not bypass verification") },
            onMediaCandidate = { media, callbackGeneration ->
                candidates += media to callbackGeneration
            },
        )
        val capability = bridge.beginNavigation(pageUrl, generation)

        bridge.onMediaCandidate(capability, "https://media.example/media/abc123", "fetch")

        assertTrue(candidates.isEmpty())
        assertTrue(bridge.enableThoroughScan(pageUrl, generation))

        bridge.onMediaCandidate(capability, "https://media.example/media/abc123", "fetch")

        assertEquals(1, candidates.size)
        assertEquals("https://media.example/media/abc123", candidates.single().first.url)
        assertEquals(pageUrl, candidates.single().first.pageUrl)
        assertEquals(DetectionSource.XHR_FETCH, candidates.single().first.source)
        assertEquals(null, candidates.single().first.mimeType)
        assertEquals(generation, candidates.single().second)
    }

    /** Ensures calls without the injected navigation capability cannot report media or consume scan capacity. */
    @Test
    fun `missing and invalid capabilities are ignored`() {
        val candidates = mutableListOf<DetectedMedia>()
        val detected = mutableListOf<DetectedMedia>()
        val bridge = MediaBridge(
            onMediaDetected = { media, _ -> detected += media },
            onMediaCandidate = { media, _ -> candidates += media },
        )
        bridge.beginNavigation("https://media.example/watch", 1L)

        bridge.onMediaCandidate(null, "https://media.example/media/abc123", "fetch")
        bridge.onMediaCandidate("not-the-capability", "https://media.example/media/abc123", "fetch")
        bridge.onMediaDetectedWithType(null, "https://cdn.example/clip.mp4", "video", "video/mp4")
        bridge.onMediaDetectedWithType(
            "not-the-capability",
            "https://cdn.example/clip.mp4",
            "video",
            "video/mp4",
        )

        assertTrue(candidates.isEmpty())
        assertTrue(detected.isEmpty())
    }

    /** Ensures a direct report from a retired document cannot reach the newly active browser destination. */
    @Test
    fun `stale and invalidated capabilities cannot report direct media`() {
        val detected = mutableListOf<Pair<DetectedMedia, Long>>()
        val bridge = MediaBridge(
            onMediaDetected = { media, generation -> detected += media to generation },
        )
        val staleCapability = bridge.beginNavigation("https://first.example/watch", 1L)
        val activeCapability = bridge.beginNavigation("https://second.example/watch", 2L)

        bridge.onMediaDetectedWithType(
            staleCapability,
            "https://cdn.example/media/stale.mp4",
            "video",
            "video/mp4",
        )
        bridge.invalidateNavigation()
        bridge.onMediaDetectedWithType(
            activeCapability,
            "https://cdn.example/media/invalidated.mp4",
            "video",
            "video/mp4",
        )

        assertTrue(detected.isEmpty())
    }

    /** Ensures an old capability and consent cannot survive a later document generation or invalidation. */
    @Test
    fun `stale capabilities and stale scan consent are ignored`() {
        val candidates = mutableListOf<Pair<DetectedMedia, Long>>()
        val pageUrl = "https://media.example/watch"
        val bridge = MediaBridge(
            onMediaDetected = { _, _ -> error("candidate must not bypass verification") },
            onMediaCandidate = { media, generation -> candidates += media to generation },
        )
        val staleCapability = bridge.beginNavigation(pageUrl, 7L)
        assertTrue(bridge.enableThoroughScan(pageUrl, 7L))
        val activeCapability = bridge.beginNavigation(pageUrl, 8L)

        assertFalse(bridge.enableThoroughScan(pageUrl, 7L))
        bridge.onMediaCandidate(staleCapability, "https://media.example/media/stale", "fetch")
        bridge.onMediaCandidate(activeCapability, "https://media.example/media/not-authorized", "fetch")
        assertTrue(bridge.enableThoroughScan(pageUrl, 8L))
        bridge.onMediaCandidate(activeCapability, "https://media.example/media/active", "fetch")
        bridge.invalidateNavigation()
        bridge.onMediaCandidate(activeCapability, "https://media.example/media/invalidated", "fetch")

        assertEquals(listOf("https://media.example/media/active"), candidates.map { it.first.url })
        assertEquals(listOf(8L), candidates.map { it.second })
    }

    /** Ensures native deduplication and the ten-candidate ceiling cannot be bypassed with URL fragments. */
    @Test
    fun `candidate cap deduplicates fragments and resets for the next navigation`() {
        val candidates = mutableListOf<Pair<DetectedMedia, Long>>()
        val pageUrl = "https://media.example/watch"
        val bridge = MediaBridge(
            onMediaDetected = { _, _ -> error("candidate must not bypass verification") },
            onMediaCandidate = { media, generation -> candidates += media to generation },
        )
        val firstCapability = bridge.beginNavigation(pageUrl, 12L)
        assertTrue(bridge.enableThoroughScan(pageUrl, 12L))

        bridge.onMediaCandidate(firstCapability, "https://media.example/media/one#first", "fetch")
        bridge.onMediaCandidate(firstCapability, "https://media.example/media/one#second", "fetch")
        (2..11).forEach { index ->
            bridge.onMediaCandidate(
                firstCapability,
                "https://media.example/media/candidate-$index#fragment",
                "xhr",
            )
        }

        assertEquals(10, candidates.size)
        assertEquals(
            listOf("https://media.example/media/one") +
                (2..10).map { "https://media.example/media/candidate-$it" },
            candidates.map { it.first.url },
        )
        assertTrue(candidates.all { it.second == 12L })

        val nextCapability = bridge.beginNavigation(pageUrl, 13L)
        assertTrue(bridge.enableThoroughScan(pageUrl, 13L))
        bridge.onMediaCandidate(nextCapability, "https://media.example/media/fresh-document", "fetch")

        assertEquals("https://media.example/media/fresh-document", candidates.last().first.url)
        assertEquals(13L, candidates.last().second)
    }

    /** Ensures a retained WebView dispatches candidate work into its currently visible browser destination. */
    @Test
    fun `callbacks can rebind after the retained WebView is reattached`() {
        val firstDestinationCandidates = mutableListOf<DetectedMedia>()
        val reattachedDestinationCandidates = mutableListOf<DetectedMedia>()
        val bridge = MediaBridge(
            onMediaDetected = { _, _ -> error("candidate must not bypass verification") },
            onMediaCandidate = { media, _ -> firstDestinationCandidates += media },
        )

        bridge.updateCallbacks(
            onMediaDetected = { _, _ -> error("candidate must not bypass verification") },
            onMediaCandidate = { media, _ -> reattachedDestinationCandidates += media },
        )
        val pageUrl = "https://reattached.example/watch"
        val generation = 21L
        val capability = bridge.beginNavigation(pageUrl, generation)
        assertTrue(bridge.enableThoroughScan(pageUrl, generation))
        bridge.onMediaCandidate(
            capability,
            "https://reattached.example/media/abc123",
            "fetch",
        )

        assertTrue(firstDestinationCandidates.isEmpty())
        assertEquals(
            listOf("https://reattached.example/media/abc123"),
            reattachedDestinationCandidates.map { it.url },
        )
    }

    /** Ensures a valid capability still cannot send verification to unrelated origins or unsupported sources. */
    @Test
    fun `cross-origin and unsupported candidates are ignored`() {
        val candidates = mutableListOf<DetectedMedia>()
        val pageUrl = "https://media.example/watch"
        val generation = 1L
        val bridge = MediaBridge(
            onMediaDetected = { _, _ -> error("candidate must not bypass verification") },
            onMediaCandidate = { media, _ -> candidates += media },
        )
        val capability = bridge.beginNavigation(pageUrl, generation)
        assertTrue(bridge.enableThoroughScan(pageUrl, generation))

        bridge.onMediaCandidate(capability, "https://cdn.example/media/abc123", "fetch")
        bridge.onMediaCandidate(capability, "https://media.example/media/abc123", "script")

        assertTrue(candidates.isEmpty())
    }

    /** Ensures authenticated direct fetch reports retain their distinct source and generation classification. */
    @Test
    fun `authenticated fetch media is marked as XHR fetch rather than DOM`() {
        val detected = mutableListOf<Pair<DetectedMedia, Long>>()
        val generation = 99L
        val bridge = MediaBridge(
            onMediaDetected = { media, callbackGeneration -> detected += media to callbackGeneration },
        )
        val capability = bridge.beginNavigation("https://media.example/watch", generation)

        bridge.onMediaDetectedWithType(
            capability,
            "https://media.example/assets/clip.mp4",
            "xhr",
            "video/mp4",
        )

        assertEquals(DetectionSource.XHR_FETCH, detected.single().first.source)
        assertEquals(generation, detected.single().second)
    }
}
