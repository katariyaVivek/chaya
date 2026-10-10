package com.chaya.app.platform

import com.chaya.app.download.ARCHIVE_SCHEME
import com.chaya.app.download.ArchiveLister
import com.chaya.app.download.ArchiveListingException
import com.chaya.app.download.ArchiveRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale

/** An account on Instagram or X, whose posts can be saved together as one ZIP. */
data class ProfileMatch(val platform: Platform, val username: String, val url: String) {

    /** What a ZIP of this account's posts is called and how its download is addressed. */
    fun archiveRequest(useSignIn: Boolean): ArchiveRequest = ArchiveRequest(
        source = "$ARCHIVE_SCHEME${siteOf(platform)}:$username" + if (useSignIn) SIGN_IN_FLAG else "",
        fileName = "$username (${platform.displayName}).zip",
        title = "@$username · ${platform.displayName} posts",
        pageUrl = url,
    )

    companion object {
        private const val SIGN_IN_FLAG = "?signin"

        /** The account and the sign-in choice an archive's address holds; null when it is not one of these. */
        fun fromSource(source: String): Pair<ProfileMatch, Boolean>? {
            if (!source.startsWith(ARCHIVE_SCHEME)) return null
            val rest = source.removePrefix(ARCHIVE_SCHEME)
            val useSignIn = rest.endsWith(SIGN_IN_FLAG)
            val (site, username) = rest.removeSuffix(SIGN_IN_FLAG).split(':', limit = 2).takeIf { it.size == 2 }
                ?: return null
            val platform = Platform.entries.firstOrNull { siteOf(it) == site } ?: return null
            if (!ProfileMatcher.isUsername(platform, username)) return null
            return ProfileMatch(platform, username, ProfileMatcher.pageOf(platform, username)) to useSignIn
        }

        /** gallery-dl's name for the site. */
        fun siteOf(platform: Platform): String = when (platform) {
            Platform.TWITTER -> "twitter"
            else -> platform.name.lowercase(Locale.ROOT)
        }
    }
}

/**
 * Recognizes a link to an account on Instagram or X: instagram.com/someone, x.com/someone, or their posts
 * and media pages. A post, a reel or any of the sites' own pages (explore, settings...) is not an account.
 */
object ProfileMatcher {
    private val instagramHosts = setOf("instagram.com", "www.instagram.com", "m.instagram.com")
    private val xHosts = setOf("x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com", "mobile.x.com")

    private val instagramPages = setOf(
        "p", "reel", "reels", "tv", "stories", "explore", "accounts", "direct", "about", "developer", "legal",
        "web", "challenge", "emails", "privacy", "terms", "session", "oauth", "directory",
    )
    private val xPages = setOf(
        "home", "explore", "notifications", "messages", "i", "settings", "search", "compose", "login", "logout",
        "signup", "tos", "privacy", "hashtag", "intent", "share", "jobs", "download", "account",
    )
    private val instagramTabs = setOf("posts", "reels", "tagged")
    private val xTabs = setOf("media", "with_replies", "highlights", "likes", "photo")

    private val instagramUsername = Regex("[A-Za-z0-9._]{1,30}")
    private val xUsername = Regex("[A-Za-z0-9_]{1,15}")

    fun match(input: String): ProfileMatch? {
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val parts = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        val name = parts.firstOrNull() ?: return null
        val tab = parts.getOrNull(1)
        if (parts.size > 2) return null

        val platform = when {
            host in instagramHosts && name.lowercase(Locale.ROOT) !in instagramPages &&
                (tab == null || tab in instagramTabs) -> Platform.INSTAGRAM
            host in xHosts && name.lowercase(Locale.ROOT) !in xPages && (tab == null || tab in xTabs) -> Platform.TWITTER
            else -> return null
        }
        if (!isUsername(platform, name)) return null
        return ProfileMatch(platform, name, input.trim())
    }

    internal fun isUsername(platform: Platform, name: String): Boolean = when (platform) {
        Platform.INSTAGRAM -> instagramUsername.matches(name) && !name.startsWith('.') && !name.endsWith('.')
        Platform.TWITTER -> xUsername.matches(name)
        else -> false
    }

    internal fun pageOf(platform: Platform, username: String): String = when (platform) {
        Platform.INSTAGRAM -> "https://www.instagram.com/$username/"
        else -> "https://x.com/$username"
    }
}

/** Runs the engine's account listing (`chaya_engine.profiles.list_profile`); [PlatformEngine] is the real one. */
fun interface ProfileListing {
    /** Returns the engine's JSON summary. Blocks until the listing ends, is stopped, or fails. */
    suspend fun list(profile: ProfileMatch, cookieFile: File?, out: File, stop: File): String
}

/**
 * Lists an account's posts for its ZIP archive. The person's sign-in is used only when they chose it for this
 * archive; its cookie file is deleted as soon as the listing ends, however it ends.
 */
class ProfileLister(
    private val engine: ProfileListing,
    private val signIn: SignIn,
    private val progressEveryMillis: Long = 1_000,
) : ArchiveLister {

    override suspend fun list(source: String, list: File, stop: File, onFound: (Int) -> Unit) {
        val (profile, useSignIn) = ProfileMatch.fromSource(source)
            ?: throw ArchiveListingException("This isn't an account Chaya can save", retryable = false)
        val site = profile.platform.displayName
        val cookies = if (useSignIn) {
            signIn.cookieFile(profile.platform)
                ?: throw ArchiveListingException("Sign in to $site in Chaya's browser first, then try again")
        } else null

        val partial = File(list.path + ".partial")
        val summary = try {
            coroutineScope {
                val counter = launch {
                    while (isActive) {
                        delay(progressEveryMillis)
                        onFound(lineCount(partial))
                    }
                }
                try {
                    engine.list(profile, cookies, list, stop)
                } finally {
                    counter.cancel()
                }
            }
        } finally {
            cookies?.delete()
        }

        val json = runCatching { JSONObject(summary) }.getOrNull()
            ?: throw ArchiveListingException("Couldn't list this account")
        json.optJSONObject("error")?.let { error ->
            partial.delete()
            throw explain(error.optString("kind"), site, signedIn = useSignIn)
        }
        if (json.optBoolean("stopped")) {
            partial.delete()
            return
        }
        if (!list.exists()) throw ArchiveListingException("Couldn't finish listing this account")
        onFound(lineCount(list))
    }

    private fun explain(kind: String, site: String, signedIn: Boolean): ArchiveListingException = when (kind) {
        "needs_login", "bot_check" -> ArchiveListingException(
            if (signedIn) "$site wants you to sign in again in Chaya's browser"
            else "$site shows this account only to signed-in people. Sign in to $site in Chaya's browser and save it with your sign-in",
            retryable = signedIn,
        )
        "private" -> ArchiveListingException("This account is private, and you don't follow it", retryable = false)
        "unavailable" -> ArchiveListingException("This account doesn't exist or was removed", retryable = false)
        "network" -> ArchiveListingException("Connection lost — check your network")
        else -> ArchiveListingException("Couldn't list this account")
    }

    private fun lineCount(file: File): Int =
        if (file.exists()) runCatching { file.useLines { lines -> lines.count { it.isNotBlank() } } }.getOrDefault(0) else 0
}
