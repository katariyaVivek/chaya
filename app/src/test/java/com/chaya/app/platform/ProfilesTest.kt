package com.chaya.app.platform

import com.chaya.app.download.ArchiveListingException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfilesTest {

    // ---- recognising an account ---- //

    @Test
    fun `account pages on Instagram and X are recognised`() {
        assertEquals(ProfileMatch(Platform.INSTAGRAM, "some.one_1", "https://www.instagram.com/some.one_1/"),
            ProfileMatcher.match("https://www.instagram.com/some.one_1/"))
        assertEquals("someone", ProfileMatcher.match("https://instagram.com/someone/posts")?.username)
        assertEquals(Platform.TWITTER, ProfileMatcher.match("https://x.com/Some_One")?.platform)
        assertEquals("Some_One", ProfileMatcher.match("https://twitter.com/Some_One/media")?.username)
    }

    @Test
    fun `posts, reels and the sites' own pages are not accounts`() {
        listOf(
            "https://www.instagram.com/p/Cabc123/",
            "https://www.instagram.com/reel/Cabc/",
            "https://www.instagram.com/explore/",
            "https://www.instagram.com/accounts/login/",
            "https://x.com/someone/status/123",
            "https://x.com/home",
            "https://x.com/i/flow/login",
            "https://x.com/",
            "https://www.youtube.com/@someone",
            "https://x.com/name_that_is_far_too_long",
            "ftp://x.com/someone",
        ).forEach { assertNull(it, ProfileMatcher.match(it)) }
    }

    @Test
    fun `an archive's address keeps the account and whether the sign-in may be used`() {
        val profile = ProfileMatcher.match("https://www.instagram.com/someone/")!!

        val signedIn = profile.archiveRequest(useSignIn = true)
        val visitor = profile.archiveRequest(useSignIn = false)

        assertEquals("someone (Instagram).zip", signedIn.fileName)
        assertEquals(true, ProfileMatch.fromSource(signedIn.source)?.second)
        assertEquals(false, ProfileMatch.fromSource(visitor.source)?.second)
        assertEquals("someone", ProfileMatch.fromSource(visitor.source)?.first?.username)
        assertNull(ProfileMatch.fromSource("chaya-archive:instagram:../etc"))
        assertNull(ProfileMatch.fromSource("https://www.instagram.com/someone/"))
    }

    // ---- listing ---- //

    private val folder: File = Files.createTempDirectory("profiles").toFile()
    private val list = File(folder, "list.jsonl")
    private val stop = File(folder, "stop")
    private val profile = ProfileMatcher.match("https://www.instagram.com/someone/")!!

    private class Engine(val answer: (File?, File) -> String) : ProfileListing {
        val cookies = mutableListOf<File?>()
        override suspend fun list(profile: ProfileMatch, cookieFile: File?, out: File, stop: File): String {
            cookies += cookieFile
            return answer(cookieFile, out)
        }
    }

    private class Account(private val signedIn: Boolean, private val folder: File) : SignIn {
        val made = mutableListOf<File>()
        override fun isSignedIn(platform: Platform) = signedIn
        override fun cookieFile(platform: Platform): File? =
            if (signedIn) File.createTempFile("cookies", ".txt", folder).also { made += it } else null
    }

    @Test
    fun `the sign-in is used only when chosen, and its file is gone afterwards`() = runBlocking {
        val account = Account(signedIn = true, folder)
        val engine = Engine { cookies, out ->
            if (cookies != null) assertTrue("the cookie file exists while listing", cookies.exists())
            out.writeText("{}\n{}\n")
            """{"count": 2}"""
        }
        var found = 0

        ProfileLister(engine, account).list(profile.archiveRequest(useSignIn = true).source, list, stop) { found = it }
        ProfileLister(engine, account).list(profile.archiveRequest(useSignIn = false).source, list, stop) { }

        assertEquals(2, found)
        assertEquals("the visitor listing gets no cookies", null, engine.cookies[1])
        assertEquals(1, account.made.size)
        assertFalse(account.made.single().exists())
    }

    @Test
    fun `choosing the sign-in without being signed in asks for it`() {
        val error = assertThrows(ArchiveListingException::class.java) {
            runBlocking {
                ProfileLister(Engine { _, _ -> """{"count": 0}""" }, Account(false, folder))
                    .list(profile.archiveRequest(useSignIn = true).source, list, stop) { }
            }
        }
        assertEquals("Sign in to Instagram in Chaya's browser first, then try again", error.message)
    }

    @Test
    fun `a visitor turned away is told to sign in, and that is not retried as is`() {
        val error = assertThrows(ArchiveListingException::class.java) {
            runBlocking {
                ProfileLister(Engine { _, _ -> """{"error": {"kind": "needs_login"}}""" }, Account(false, folder))
                    .list(profile.archiveRequest(useSignIn = false).source, list, stop) { }
            }
        }
        assertTrue(error.message!!.contains("Sign in to Instagram"))
        assertFalse(error.retryable)
    }

    @Test
    fun `a stopped listing returns quietly and leaves no list`() = runBlocking {
        ProfileLister(Engine { _, _ -> """{"stopped": true, "count": 3}""" }, Account(false, folder))
            .list(profile.archiveRequest(useSignIn = false).source, list, stop) { }

        assertFalse(list.exists())
    }

    @Test
    fun `a private account is explained and not retried`() {
        val error = assertThrows(ArchiveListingException::class.java) {
            runBlocking {
                ProfileLister(Engine { _, _ -> """{"error": {"kind": "private"}}""" }, Account(true, folder))
                    .list(profile.archiveRequest(useSignIn = true).source, list, stop) { }
            }
        }
        assertEquals("This account is private, and you don't follow it", error.message)
        assertFalse(error.retryable)
    }
}
