package com.chaya.app.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PlatformLinksTest {

    private val video = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    private val other = "https://www.youtube.com/watch?v=abc123xyz"

    private val temporaryFiles = mutableListOf<File>()

    @After
    fun cleanUp() {
        temporaryFiles.forEach { it.delete() }
    }

    @Test
    fun `a supported link is looked up and its choices are offered best first`() = runTest {
        val finder = finder { media(complete("m720", 1280, 720), complete("m360", 640, 360)) }
        val links = PlatformLinks(backgroundScope, finder)

        assertTrue(links.look(video))
        assertTrue(links.state.value is LinkState.Looking)
        advanceUntilIdle()

        val found = links.state.value as LinkState.Found
        assertEquals(listOf("720p", "360p"), found.choices.map { it.label })
        assertEquals("720p", found.best.label)
        assertEquals(Platform.YOUTUBE, found.match.platform)
        assertEquals(listOf(video), finder.urls)
    }

    @Test
    fun `a link that is not one supported video clears the state and says so`() = runTest {
        val links = PlatformLinks(backgroundScope, finder { media(complete("m", 1280, 720)) })
        links.look(video)
        advanceUntilIdle()
        assertTrue(links.state.value is LinkState.Found)

        assertFalse(links.look("https://www.youtube.com/@creator"))

        assertEquals(LinkState.Idle, links.state.value)
        assertFalse(links.look("https://example.com/video.mp4"))
        assertFalse(links.look(""))
    }

    @Test
    fun `the same video is not looked up twice, however its link is spelled`() = runTest {
        val finder = finder { media(complete("m", 1280, 720)) }
        val links = PlatformLinks(backgroundScope, finder)

        links.look(video)
        links.look(video)
        links.look("$video&t=42s")
        links.look("https://youtu.be/dQw4w9WgXcQ")
        advanceUntilIdle()
        links.look(video)

        assertEquals(1, finder.urls.size)
    }

    @Test
    fun `a different video replaces the earlier lookup, and the late answer for the first is dropped`() = runTest {
        val slowFirst = CompletableDeferred<PlatformMedia>()
        val finder = finderByUrl { url ->
            if (url == video) slowFirst.await() else media(complete("second", 1280, 720), title = "Second")
        }
        val links = PlatformLinks(backgroundScope, finder)
        links.look(video)
        runCurrent()

        links.look(other)
        advanceUntilIdle()
        slowFirst.complete(media(complete("first", 1280, 720), title = "First"))
        advanceUntilIdle()

        assertEquals("Second", (links.state.value as LinkState.Found).media.title)
    }

    @Test
    fun `answers are remembered so coming back is instant, until they go stale`() = runTest {
        var now = 0L
        val finder = finderByUrl { url -> media(complete(url.takeLast(6), 1280, 720)) }
        val links = PlatformLinks(backgroundScope, finder, clock = { now })
        links.look(video)
        advanceUntilIdle()
        links.look(other)
        advanceUntilIdle()

        links.look(video)

        // Straight back to the answer, with no lookup in between.
        assertTrue(links.state.value is LinkState.Found)
        assertEquals("dQw4w9WgXcQ", links.state.value.match?.id)
        assertEquals(2, finder.urls.size)

        now += 21 * 60 * 1000L
        links.look(other)
        assertTrue("an old answer must not be reused", links.state.value is LinkState.Looking)
        advanceUntilIdle()
        assertEquals(3, finder.urls.size)
    }

    @Test
    fun `a failure is shown, not retried by itself, and retry asks again`() = runTest {
        val finder = finder { throw PlatformException(PlatformException.Kind.PRIVATE) }
        val links = PlatformLinks(backgroundScope, finder)
        links.look(video)
        advanceUntilIdle()

        val failed = links.state.value as LinkState.Failed
        assertEquals(PlatformException.Kind.PRIVATE, failed.problem.kind)
        assertFalse(failed.signInMayHelp)

        links.look(video) // the page reporting the same address again
        advanceUntilIdle()
        assertEquals(1, finder.urls.size)

        finder.answer = { _, _ -> media(complete("m", 1280, 720)) }
        links.retry()
        advanceUntilIdle()

        assertTrue(links.state.value is LinkState.Found)
        assertEquals(2, finder.urls.size)
    }

    @Test
    fun `a sign-in wall offers the account only when the browser is signed in`() = runTest {
        val botCheck = failing(PlatformException.Kind.BOT_CHECK, FakeSignIn(signedIn = true), backgroundScope)
        val loginWall = failing(PlatformException.Kind.NEEDS_LOGIN, FakeSignIn(signedIn = true), backgroundScope)
        val withoutAccount = failing(PlatformException.Kind.BOT_CHECK, FakeSignIn(signedIn = false), backgroundScope)
        val unrelated = failing(PlatformException.Kind.GEO, FakeSignIn(signedIn = true), backgroundScope)
        advanceUntilIdle()

        assertTrue((botCheck.state.value as LinkState.Failed).signInMayHelp)
        assertTrue((loginWall.state.value as LinkState.Failed).signInMayHelp)
        assertFalse((withoutAccount.state.value as LinkState.Failed).signInMayHelp)
        assertFalse((unrelated.state.value as LinkState.Failed).signInMayHelp)
    }

    @Test
    fun `retrying with the account hands the engine its cookies and removes the file afterwards`() = runTest {
        val signIn = FakeSignIn(signedIn = true)
        var cookieFileSeen: File? = null
        var existedWhileUsed = false
        val finder = FakeFinder { _, cookies ->
            if (cookies == null) throw PlatformException(PlatformException.Kind.BOT_CHECK)
            cookieFileSeen = cookies
            existedWhileUsed = cookies.exists()
            media(complete("m", 1280, 720))
        }
        val links = PlatformLinks(backgroundScope, finder, signIn)
        links.look(video)
        advanceUntilIdle()
        assertTrue((links.state.value as LinkState.Failed).signInMayHelp)
        assertNull("the first try uses no account", finder.cookieFiles.single())

        links.retry(useSignIn = true)
        advanceUntilIdle()

        assertTrue(links.state.value is LinkState.Found)
        assertTrue("the engine had a cookie file to read", existedWhileUsed)
        assertFalse("the session's cookies must not be left on disk", cookieFileSeen!!.exists())
    }

    @Test
    fun `the cookie file is removed even when the lookup with the account fails`() = runTest {
        val signIn = FakeSignIn(signedIn = true)
        val links = PlatformLinks(
            backgroundScope,
            finder { throw PlatformException(PlatformException.Kind.UNAVAILABLE) },
            signIn,
        )
        links.look(video)
        advanceUntilIdle()

        links.retry(useSignIn = true)
        advanceUntilIdle()

        assertTrue(links.state.value is LinkState.Failed)
        assertTrue(signIn.created.isNotEmpty())
        assertTrue(signIn.created.none { it.exists() })
    }

    @Test
    fun `a live broadcast is explained`() = runTest {
        val links = PlatformLinks(backgroundScope, finder { media(complete("live", 1280, 720), live = true) })
        links.look(video)
        advanceUntilIdle()

        assertEquals(PlatformException.Kind.LIVE, (links.state.value as LinkState.Failed).problem.kind)
    }

    @Test
    fun `a video none of whose formats can be saved is explained`() = runTest {
        // Only an AV1 picture, which the join cannot carry yet, and no sound to offer on its own.
        val links = PlatformLinks(backgroundScope, finder { media(av1Picture()) })
        links.look(video)
        advanceUntilIdle()

        assertEquals(PlatformException.Kind.NO_FORMAT, (links.state.value as LinkState.Failed).problem.kind)
    }

    @Test
    fun `the sound is still offered when no picture can be saved`() = runTest {
        val links = PlatformLinks(
            backgroundScope,
            finder {
                media(
                    av1Picture(),
                    PlatformFormatFixtures.format(id = "140", ext = "m4a", vcodec = "none", acodec = "mp4a.40.2"),
                )
            },
        )
        links.look(video)
        advanceUntilIdle()

        assertEquals(listOf("Audio only"), (links.state.value as LinkState.Found).choices.map { it.label })
    }

    @Test
    fun `a picture and a sound the join can carry are offered as a quality`() = runTest {
        val links = PlatformLinks(
            backgroundScope,
            finder {
                media(
                    PlatformFormatFixtures.format(
                        id = "137", ext = "mp4", width = 1920, height = 1080, vcodec = "avc1.640028", acodec = "none",
                    ),
                    PlatformFormatFixtures.format(id = "140", ext = "m4a", vcodec = "none", acodec = "mp4a.40.2"),
                )
            },
        )
        links.look(video)
        advanceUntilIdle()

        val best = (links.state.value as LinkState.Found).best
        assertEquals("1080p", best.label)
        assertTrue(best.needsMerge)
    }

    @Test
    fun `an engine crash is reported as a problem with the finder, not left spinning`() = runTest {
        val links = PlatformLinks(backgroundScope, finder { throw IllegalStateException("boom") })
        links.look(video)
        advanceUntilIdle()

        val failed = links.state.value as LinkState.Failed
        assertEquals(PlatformException.Kind.ENGINE, failed.problem.kind)
        assertEquals("boom", failed.problem.detail)
    }

    @Test
    fun `clearing stops a lookup in flight and keeps its answer from appearing`() = runTest {
        val pending = CompletableDeferred<PlatformMedia>()
        val links = PlatformLinks(backgroundScope, finder { pending.await() })
        links.look(video)
        runCurrent()

        links.clear()
        pending.complete(media(complete("m", 1280, 720)))
        advanceUntilIdle()

        assertEquals(LinkState.Idle, links.state.value)
    }

    // ---- fixtures ---- //

    private fun complete(id: String, width: Int, height: Int) = PlatformFormatFixtures.format(
        id = id, ext = "mp4", width = width, height = height, vcodec = "avc1.42001E", acodec = "mp4a.40.2",
    )

    private fun av1Picture() = PlatformFormatFixtures.format(
        id = "av1", ext = "mp4", width = 1920, height = 1080, vcodec = "av01.0.08M.08", acodec = "none",
    )

    private fun media(vararg formats: PlatformFormat, title: String = "A video", live: Boolean = false) = PlatformMedia(
        id = "id",
        title = title,
        author = null,
        durationSeconds = null,
        thumbnailUrl = null,
        pageUrl = null,
        extractor = "Youtube",
        isLive = live,
        formats = formats.toList(),
    )

    /** A finder that answers every lookup the same way. */
    private fun finder(answer: suspend () -> PlatformMedia) = FakeFinder { _, _ -> answer() }

    /** A finder whose answer depends on the link. */
    private fun finderByUrl(answer: suspend (String) -> PlatformMedia) = FakeFinder { url, _ -> answer(url) }

    /** Links whose every lookup fails with [kind], already asked about [video]. */
    private fun failing(kind: PlatformException.Kind, signIn: SignIn, scope: CoroutineScope): PlatformLinks =
        PlatformLinks(scope, finder { throw PlatformException(kind) }, signIn).also { it.look(video) }

    /** Answers each lookup with [answer], and records what it was asked. */
    private class FakeFinder(var answer: suspend (String, File?) -> PlatformMedia) : LinkFinder {
        val urls = mutableListOf<String>()
        val cookieFiles = mutableListOf<File?>()

        override suspend fun find(url: String, cookieFile: File?): PlatformMedia {
            urls += url
            cookieFiles += cookieFile
            return answer(url, cookieFile)
        }
    }

    private inner class FakeSignIn(private val signedIn: Boolean) : SignIn {
        val created = mutableListOf<File>()

        override fun isSignedIn(platform: Platform) = signedIn

        override fun cookieFile(platform: Platform): File? =
            File.createTempFile("signin", ".txt").also {
                created += it
                temporaryFiles += it
            }
    }
}
