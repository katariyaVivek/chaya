package com.chaya.app.platform

import com.chaya.app.download.Mp4Merger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** What the app knows about the video behind a link on a supported site. */
sealed interface LinkState {
    /** The link this state is about; null when there is none. */
    val match: PlatformMatch?

    data object Idle : LinkState {
        override val match: PlatformMatch? = null
    }

    data class Looking(override val match: PlatformMatch) : LinkState

    /** Something found behind the link that can be saved: a video in some qualities, or a post's items. */
    sealed interface Answer : LinkState {
        override val match: PlatformMatch
        val media: PlatformMedia
    }

    /** A video. [choices] is never empty, and its first item is the best one. */
    data class Found(
        override val match: PlatformMatch,
        override val media: PlatformMedia,
        val choices: List<PlatformChoice>,
    ) : Answer {
        val best: PlatformChoice get() = choices.first()
    }

    /** A post with pictures: [media]'s items, never empty, each saved as it is. */
    data class FoundPost(
        override val match: PlatformMatch,
        override val media: PlatformMedia,
    ) : Answer {
        val items: List<PostItem> get() = media.items
    }

    /**
     * [signInMayHelp] says the site wants a sign-in and the browser here is signed in to it, so trying again
     * with that account could work. It is only ever offered, never done on its own.
     */
    data class Failed(
        override val match: PlatformMatch,
        val problem: PlatformException,
        val signInMayHelp: Boolean,
    ) : LinkState
}

/** Finds out what a link holds. [PlatformEngine] is the real one; tests use a stand-in. */
fun interface LinkFinder {
    /** @throws PlatformException when the link can't be used. */
    suspend fun find(url: String, cookieFile: File?): PlatformMedia
}

/** The browser's signed-in session for a site, in a form yt-dlp can use. */
interface SignIn {
    /** Whether the browser here is signed in to [platform]. */
    fun isSignedIn(platform: Platform): Boolean

    /** A temporary file of the session's cookies for [platform], which the caller deletes; null when there are none. */
    fun cookieFile(platform: Platform): File?

    object None : SignIn {
        override fun isSignedIn(platform: Platform) = false
        override fun cookieFile(platform: Platform): File? = null
    }
}

/**
 * Looks up the video behind a link and keeps the answer, so the pill and the sheet can show it.
 * One link at a time: asking about another cancels the first, and a late answer for a link the person has
 * already moved on from is dropped.
 */
class PlatformLinks(
    private val scope: CoroutineScope,
    private val finder: LinkFinder,
    private val signIn: SignIn = SignIn.None,
    private val matcher: (String) -> PlatformMatch? = PlatformMatcher::match,
    private val canJoin: (PlatformFormat) -> Boolean = { Mp4Merger.canJoinPicture(it.videoCodec) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /** Identifies the newest question; an answer is only used while its ticket is still the newest. */
    private val ticket = AtomicInteger(0)

    private var running: Job? = null

    private class Remembered(val answer: LinkState.Answer, val at: Long)

    private val remembered = LinkedHashMap<String, Remembered>()

    /**
     * Looks up [url] if it is a link to one video on a supported site; anything else clears the state.
     * The same video already being looked up or answered is left alone unless [force] asks to try again.
     * [useSignIn] retries with the browser's signed-in session. Returns whether [url] is a supported link.
     */
    fun look(url: String, force: Boolean = false, useSignIn: Boolean = false): Boolean {
        val match = matcher(url)
        if (match == null) {
            clear()
            return false
        }
        val state = _state.value
        val current = state.match
        val sameVideo = current != null && current.platform == match.platform && current.id == match.id
        if (!force && sameVideo && !(state is LinkState.Answer && isStale(state))) return true

        val mine = ticket.incrementAndGet()
        running?.cancel()
        if (!force) {
            recall(match)?.let {
                _state.value = it
                return true
            }
        }
        _state.value = LinkState.Looking(match)
        running = scope.launch {
            val answer = resolve(match, useSignIn)
            if (ticket.get() == mine) _state.value = answer
        }
        return true
    }

    /**
     * The current answer while its addresses can still be used. An answer older than the engine's addresses
     * last is looked up again instead, and null is returned until the new one arrives.
     */
    fun freshAnswer(): LinkState.Answer? {
        val answer = _state.value as? LinkState.Answer ?: return null
        if (!isStale(answer)) return answer
        look(answer.match.url, force = true)
        return null
    }

    /** [freshAnswer] when it is a video. */
    fun freshFound(): LinkState.Found? = freshAnswer() as? LinkState.Found

    /** Asks again about the current link, for a failure the person chooses to retry. */
    fun retry(useSignIn: Boolean = false) {
        val match = _state.value.match ?: return
        look(match.url, force = true, useSignIn = useSignIn)
    }

    /** Forgets the current link and stops looking it up. */
    fun clear() {
        ticket.incrementAndGet()
        running?.cancel()
        _state.value = LinkState.Idle
    }

    private suspend fun resolve(match: PlatformMatch, useSignIn: Boolean): LinkState {
        val cookies = if (useSignIn) signIn.cookieFile(match.platform) else null
        return try {
            val media = finder.find(match.url, cookies)
            val choices = FormatSelector.choices(media, canJoin)
            when {
                media.items.isNotEmpty() -> LinkState.FoundPost(match, media).also { remember(it) }
                choices.isNotEmpty() -> LinkState.Found(match, media, choices).also { remember(it) }
                media.isLive -> failed(match, PlatformException.Kind.LIVE)
                else -> failed(match, PlatformException.Kind.NO_FORMAT)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlatformException) {
            val signInWall = e.kind == PlatformException.Kind.BOT_CHECK || e.kind == PlatformException.Kind.NEEDS_LOGIN
            LinkState.Failed(
                match,
                e,
                signInMayHelp = signInWall && !useSignIn && signIn.isSignedIn(match.platform),
            )
        } catch (e: Exception) {
            LinkState.Failed(match, PlatformException(PlatformException.Kind.ENGINE, e.message), signInMayHelp = false)
        } finally {
            cookies?.delete()
        }
    }

    private fun failed(match: PlatformMatch, kind: PlatformException.Kind) =
        LinkState.Failed(match, PlatformException(kind), signInMayHelp = false)

    // Addresses the engine finds stop working after a while, so an answer is only kept for a short time.

    private fun keyOf(match: PlatformMatch) = "${match.platform}:${match.id}"

    private fun remember(answer: LinkState.Answer) {
        synchronized(remembered) {
            remembered.remove(keyOf(answer.match))
            remembered[keyOf(answer.match)] = Remembered(answer, clock())
            while (remembered.size > REMEMBERED_LIMIT) remembered.remove(remembered.keys.first())
        }
    }

    private fun isStale(answer: LinkState.Answer): Boolean = synchronized(remembered) {
        val entry = remembered[keyOf(answer.match)]
        entry == null || clock() - entry.at > REMEMBERED_FOR_MILLIS
    }

    private fun recall(match: PlatformMatch): LinkState.Answer? = synchronized(remembered) {
        val entry = remembered[keyOf(match)]
        when {
            entry == null -> null
            clock() - entry.at > REMEMBERED_FOR_MILLIS -> {
                remembered.remove(keyOf(match))
                null
            }
            else -> entry.answer
        }
    }

    private companion object {
        const val REMEMBERED_LIMIT = 8
        const val REMEMBERED_FOR_MILLIS = 20 * 60 * 1000L
    }
}
