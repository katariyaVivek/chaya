package com.chaya.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same table as sancika's matcher test, plus the extra link shapes chaya also accepts. */
class PlatformMatcherTest {

    private fun assertMatches(url: String, platform: Platform, id: String) {
        val match = PlatformMatcher.match(url)
        assertEquals("platform of $url", platform, match?.platform)
        assertEquals("id of $url", id, match?.id)
    }

    @Test
    fun `links from sancika's test table match`() {
        assertMatches("https://www.youtube.com/watch?v=dQw4w9WgXcQ", Platform.YOUTUBE, "dQw4w9WgXcQ")
        assertMatches("https://youtu.be/dQw4w9WgXcQ", Platform.YOUTUBE, "dQw4w9WgXcQ")
        assertMatches("https://youtube.com/shorts/abc123", Platform.YOUTUBE, "abc123")
        assertMatches("https://music.youtube.com/watch?v=music123", Platform.YOUTUBE, "music123")
        assertMatches("https://www.tiktok.com/@creator/video/7390123456789012345", Platform.TIKTOK, "7390123456789012345")
        assertMatches("https://vm.tiktok.com/ZMabc123/", Platform.TIKTOK, "ZMabc123")
        assertMatches("https://www.tiktok.com/t/ZPabc123/", Platform.TIKTOK, "ZPabc123")
        assertMatches("https://www.instagram.com/p/Cabc123/", Platform.INSTAGRAM, "p/Cabc123")
        assertMatches("https://www.instagram.com/reel/Cdef456/", Platform.INSTAGRAM, "reel/Cdef456")
        assertMatches("https://www.instagram.com/stories/account/123456789/", Platform.INSTAGRAM, "stories/account/123456789")
        assertMatches("https://twitter.com/user/status/1234567890", Platform.TWITTER, "1234567890")
        assertMatches("https://x.com/user/status/1234567890", Platform.TWITTER, "1234567890")
        assertMatches("https://t.co/abc123", Platform.TWITTER, "abc123")
    }

    @Test
    fun `other link shapes people actually share also match`() {
        assertMatches("https://m.youtube.com/watch?v=abc123&t=42s", Platform.YOUTUBE, "abc123")
        assertMatches("https://www.youtube.com/live/liveid99", Platform.YOUTUBE, "liveid99")
        assertMatches("https://www.youtube.com/embed/embedid1", Platform.YOUTUBE, "embedid1")
        assertMatches("https://www.youtube.com/watch?feature=share&v=late123", Platform.YOUTUBE, "late123")
        assertMatches("https://www.instagram.com/reels/Creel789/", Platform.INSTAGRAM, "reels/Creel789")
        assertMatches("https://mobile.twitter.com/user/status/987654321?s=20", Platform.TWITTER, "987654321")
        assertMatches("https://vt.tiktok.com/ZSabc/", Platform.TIKTOK, "ZSabc")
    }

    @Test
    fun `hosts are matched ignoring case and surrounding spaces`() {
        assertMatches("  HTTPS://WWW.YOUTUBE.COM/watch?v=Case1  ", Platform.YOUTUBE, "Case1")
    }

    @Test
    fun `the original link is kept untouched apart from trimming`() {
        val match = PlatformMatcher.match("  https://youtu.be/abc123?si=xyz  ")

        assertEquals("https://youtu.be/abc123?si=xyz", match?.url)
    }

    @Test
    fun `pages that are not a single video do not match`() {
        assertNull(PlatformMatcher.match("https://www.youtube.com/"))
        assertNull(PlatformMatcher.match("https://www.youtube.com/@creator"))
        assertNull(PlatformMatcher.match("https://www.youtube.com/results?search_query=cats"))
        assertNull(PlatformMatcher.match("https://www.instagram.com/someone/"))
        assertNull(PlatformMatcher.match("https://www.tiktok.com/@creator"))
        assertNull(PlatformMatcher.match("https://x.com/home"))
    }

    @Test
    fun `unsupported and malformed links return null`() {
        assertNull(PlatformMatcher.match("https://example.com/watch?v=123"))
        assertNull(PlatformMatcher.match("not-a-url"))
        assertNull(PlatformMatcher.match(""))
        assertNull(PlatformMatcher.match("ftp://youtube.com/watch?v=abc"))
        assertNull(PlatformMatcher.match("https://notyoutube.com/watch?v=abc"))
        assertNull(PlatformMatcher.match("https://youtube.com.evil.example/watch?v=abc"))
    }
}
