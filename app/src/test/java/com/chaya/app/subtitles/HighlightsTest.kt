package com.chaya.app.subtitles

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.ai.AiClient
import com.chaya.app.ai.AiError
import com.chaya.app.ai.AiResult
import com.chaya.app.ai.AiSettings
import com.chaya.app.ai.Endpoint
import com.chaya.app.ui.player.HighlightsSheet
import com.chaya.app.ui.player.highlightSpans
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `core::highlights`' cases, ported, against a stand-in model; the highlights kept, and their sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HighlightsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    private fun endpoint(): Endpoint =
        (Endpoint.of(AiSettings(service = "openai", address = server.url("/v1").toString(), model = "m"), "k") as AiResult.Done).value

    /** [count] lines, each 4 s long with a second between. */
    private fun lines(count: Int) = (0 until count).map { SubtitleLine(it * 5_000L, it * 5_000L + 4_000, "Line ${it + 1} of the talk.") }

    private fun answering(highlights: String) {
        val content = JSONObject().put("highlights", JSONArray(highlights)).toString()
        server.enqueue(
            MockResponse().setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString()),
        )
    }

    private suspend fun run(lines: List<SubtitleLine>, count: Int) =
        HighlightFinder(AiClient()).find(endpoint(), "m", HighlightFinder.Video("A talk", lines, "French"), count)

    @Test
    fun `highlights come back in order, timed by their lines`() = runTest {
        answering(
            """[{"from": 30, "to": 34, "title": "The turn", "reason": "Where it changes.", "score": 7},
                {"from": 3, "to": 6, "title": "A clear claim", "reason": "With numbers.", "score": 9}]""",
        )
        val found = (run(lines(50), 2) as AiResult.Done).value
        assertEquals("in the order they come in the video", listOf("A clear claim", "The turn"), found.map { it.title })
        // Lines 3 to 6: from line 3's start to line 6's end.
        assertEquals(Highlight("A clear claim", "With numbers.", 2, 5, 10_000, 29_000), found[0])
        val user = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content")
        assertTrue(user.contains("Video title: A talk"))
        assertTrue("numbered, with times", user.contains("3 [0:10] Line 3 of the talk."))
        assertTrue(user.contains("Find the 2 best highlights"))
        assertTrue(user.contains("Write the titles and reasons in French."))
    }

    @Test
    fun `ranges outside the video, backwards, overlapping or untitled are put right or left out`() = runTest {
        answering(
            """[{"from": 0, "to": 3, "title": "Before the first line", "score": 9},
                {"from": 48, "to": 60, "title": "Past the end", "score": 9},
                {"from": 12, "to": 10, "title": "Backwards", "score": 8},
                {"from": 11, "to": 14, "title": "Overlaps a better one", "score": 6},
                {"from": 20, "to": 22, "title": "", "score": 9},
                {"from": "40", "to": "42", "title": "\"Numbers as words\"", "score": 5}]""",
        )
        val found = (run(lines(50), 5) as AiResult.Done).value
        assertEquals(listOf(Triple("Backwards", 9, 11), Triple("Numbers as words", 39, 41)), found.map { Triple(it.title, it.first, it.last) })
    }

    @Test
    fun `no more than asked for, the best by score`() = runTest {
        answering(
            """[{"from": 1, "to": 2, "title": "Fine", "score": 4},
                {"from": 10, "to": 12, "title": "Best", "score": 10},
                {"from": 20, "to": 22, "title": "Good", "score": 8}]""",
        )
        assertEquals(listOf("Best", "Good"), (run(lines(30), 2) as AiResult.Done).value.map { it.title })
    }

    @Test
    fun `a model that answers in prose is asked once more, then said so`() = runTest {
        repeat(2) {
            server.enqueue(MockResponse().setBody("""{"choices": [{"message": {"content": "Here are some great moments from the talk!"}}]}"""))
        }
        assertEquals(AiResult.Failed(AiError.NotJson), run(lines(10), 3))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a long video goes in parts, each asked for its share`() {
        val long = (0 until 6_000).map { SubtitleLine(it * 3_000L, it * 3_000L + 2_500, "A fairly ordinary line of a long talk.") }
        val parts = HighlightFinder.parts(long)
        assertTrue("$parts", parts.size > 1)
        assertEquals(0, parts.first().first)
        assertEquals(long.size, parts.last().second)
        assertTrue("every line in one part", parts.zipWithNext().all { (a, b) -> a.second == b.first })
        val question = HighlightFinder.question(HighlightFinder.Video("Long", long, "English"), parts[1].first, parts[1].second, 2)
        assertTrue(question.length < HighlightFinder.PART + 30_000)
        assertTrue(question.contains("using only the numbers ${parts[1].first + 1} to ${parts[1].second}"))
    }

    @Test
    fun `highlights are kept beside the subtitles, go with them, and are marked on the seek bar`() {
        val dir = folder.newFolder()
        val store = HighlightStore(dir)
        assertNull(store.load(12))
        val kept = FoundHighlights("en", "Groq · llama", 5, listOf(Highlight("A", "Why.", 2, 5, 10_000, 30_000)))
        store.keep(12, kept)
        assertEquals(kept, store.load(12))
        SubtitleStore(dir).forget(12)
        assertNull(store.load(12))
        assertEquals(listOf(0.1f..0.3f), highlightSpans(kept.items, 100_000))
        assertEquals(emptyList<ClosedFloatingPointRange<Float>>(), highlightSpans(kept.items, 0))
    }

    @Test
    fun `the sheet lists each with its time and why, and a tap plays it`() {
        val played = mutableListOf<Highlight>()
        val found = FoundHighlights("en", "Groq · llama", 5, listOf(Highlight("A clear claim", "With numbers.", 2, 5, 10_000, 29_000)))
        composeRule.setContent {
            HighlightsSheet(found, Finding.Idle, onPlay = { played += it }, onFind = {}, onStop = {}, onSetUp = {}, onDismiss = {})
        }
        composeRule.onNodeWithText("0:10–0:29").assertExists()
        composeRule.onNodeWithText("With numbers.").assertExists()
        composeRule.onNodeWithText("A clear claim").performClick()
        assertEquals(found.items, played)
    }
}
