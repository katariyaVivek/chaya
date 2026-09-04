package com.chaya.app.detection

import android.net.Uri
import android.webkit.WebResourceRequest
import com.chaya.app.model.DetectedMedia
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Protects top-level page attribution when concurrent WebView request callbacks
 * outlive one document or are redirected to a reattached browser destination.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaInterceptorTest {

    /** Creates a request whose only observable fields are the metadata MediaInterceptor consumes. */
    private fun request(url: String, headers: Map<String, String> = emptyMap()): WebResourceRequest {
        return mockk {
            every { this@mockk.url } returns Uri.parse(url)
            every { this@mockk.requestHeaders } returns headers
        }
    }

    /** Ensures a new document can report the same media URL with its own page and generation attribution. */
    @Test
    fun `navigation resets interceptor dedupe and updates page attribution`() {
        val detected = mutableListOf<Pair<DetectedMedia, Long>>()
        val interceptor = MediaInterceptor { media, generation -> detected += media to generation }
        val mediaRequest = request("https://cdn.example/assets/clip.mp4#fragment")

        interceptor.beginNavigation("https://first.example/watch", 1L)
        interceptor.shouldInterceptRequest(mediaRequest)
        interceptor.shouldInterceptRequest(mediaRequest)
        interceptor.beginNavigation("https://second.example/watch", 2L)
        interceptor.shouldInterceptRequest(mediaRequest)

        assertEquals(2, detected.size)
        assertEquals("https://first.example/watch", detected[0].first.pageUrl)
        assertEquals(1L, detected[0].second)
        assertEquals("https://second.example/watch", detected[1].first.pageUrl)
        assertEquals(2L, detected[1].second)
    }

    /** Ensures callbacks rebind and invalidation blocks any late request from the retired document. */
    @Test
    fun `callback rebind and invalidation isolate a retained interceptor`() {
        val firstDestination = mutableListOf<DetectedMedia>()
        val reattachedDestination = mutableListOf<DetectedMedia>()
        val interceptor = MediaInterceptor { media, _ -> firstDestination += media }

        interceptor.beginNavigation("https://media.example/watch", 5L)
        interceptor.updateOnMediaDetected { media, _ -> reattachedDestination += media }
        interceptor.shouldInterceptRequest(request("https://cdn.example/assets/first.mp3"))
        interceptor.invalidateNavigation()
        interceptor.shouldInterceptRequest(request("https://cdn.example/assets/late.mp3"))

        assertTrue(firstDestination.isEmpty())
        assertEquals(listOf("https://cdn.example/assets/first.mp3"), reattachedDestination.map { it.url })
    }
}
