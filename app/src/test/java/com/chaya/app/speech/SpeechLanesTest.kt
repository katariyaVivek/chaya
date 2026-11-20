package com.chaya.app.speech

import com.chaya.app.subtitles.SubtitleLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections

/**
 * Making subtitles in the background: the phone and a service at once, another video waiting its turn; *Stop* that
 * stops at once; what was heard kept after each piece and carried on from; what the notification says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechLanesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun hello(at: Long = 0) = Transcript(
        "en",
        listOf(Segment(at, at + 1_000, "Hello there.", Parakeet.words(listOf("▁Hello", "▁there."), listOf(0f, 0.5f), at, at + 1_000))),
    )

    /** Parakeet's files, as if fetched: only their sizes are looked at. */
    private fun parakeetFetched(dir: File) = SpeechModel.PARAKEET.files.forEach { f ->
        File(dir, "${SpeechModel.PARAKEET.folder}/${f.name}").apply { parentFile!!.mkdirs() }
            .let { RandomAccessFile(it, "rw").use { raf -> raf.setLength(f.size) } }
    }

    @Test
    fun `the phone and a service work at once, and a second video for the phone waits its turn`() = runBlocking {
        val dir = folder.newFolder()
        parakeetFetched(dir)
        val phoneGo = CompletableDeferred<Unit>()
        val cloudGo = CompletableDeferred<Unit>()
        val kept = Collections.synchronizedList(mutableListOf<Long>())
        var busy = 0
        val jobs = SpeechJobs(
            scope = CoroutineScope(Dispatchers.IO),
            models = SpeechModels(dir),
            decode = { _, onPiece -> onPiece(FloatArray(10), 0); true },
            listen = { _, _, _, _, _ -> null },
            listenParakeet = { _, _, _, _ -> runBlocking { phoneGo.await() }; hello() },
            listenCloud = { _, _, _ -> runBlocking { cloudGo.await() }; CloudHeard.Said(hello()) },
            keep = { id, _, _ -> kept += id; true },
            onBusy = { busy++ },
        )
        jobs.start(1, Engine.OnPhone(SpeechModel.PARAKEET), 1_000)
        jobs.start(2, Engine.Cloud(CloudSpeech.GROQ), 1_000)
        jobs.start(3, Engine.OnPhone(SpeechModel.PARAKEET), 1_000)
        val both = withTimeout(10_000) { jobs.states.first { it[1] is Making.Listening && it[2] is Making.Listening } }
        assertEquals("the third waits for the phone", Making.Waiting, both[3])
        assertEquals(3, busy)

        cloudGo.complete(Unit)
        withTimeout(10_000) { jobs.states.first { it[2] == Making.Idle } }
        assertEquals("the service finished while the phone was still listening", listOf(2L), kept.toList())
        phoneGo.complete(Unit)
        withTimeout(10_000) { jobs.states.first { it[1] == Making.Idle && it[3] == Making.Idle && kept.size == 3 } }
        assertEquals(listOf(2L, 1L, 3L), kept.toList())
    }

    @Test
    fun `stop says so at once, ends Parakeet between windows, and what was heard is carried on from`() = runBlocking {
        val dir = folder.newFolder()
        parakeetFetched(dir)
        val progress = SpeechProgress(folder.newFolder())
        val second = CompletableDeferred<Unit>()
        val listened = Collections.synchronizedList(mutableListOf<Long>())
        val kept = mutableListOf<List<SubtitleLine>>()
        val finished = mutableListOf<Making>()
        var hold = true
        val jobs = SpeechJobs(
            scope = CoroutineScope(Dispatchers.IO),
            models = SpeechModels(dir),
            decode = { _, onPiece ->
                onPiece(FloatArray(10), 0)
                onPiece(FloatArray(10), 300_000)
                true
            },
            listen = { _, _, _, _, _ -> null },
            listenParakeet = { _, _, _, stopped ->
                listened += listened.size.toLong()
                if (listened.size == 2 && hold) {
                    second.complete(Unit)
                    // Listening to the second piece until asked to stop, as a long window would
                    while (!stopped()) Thread.sleep(5)
                    null
                } else {
                    hello()
                }
            },
            keep = { _, _, lines -> kept += lines; true },
            progress = progress,
            onFinished = { _, ended -> finished += ended },
        )
        val engine = Engine.OnPhone(SpeechModel.PARAKEET)
        jobs.start(5, engine, 600_000)
        withTimeout(10_000) { second.await() }
        jobs.stop(5)
        assertEquals("Idle the moment Stop is pressed", Making.Idle, jobs.states.value[5])
        val partial = withTimeout(10_000) {
            var p: Partial? = null
            while (p == null) p = progress.load(5)
            p
        }
        assertEquals(Partial(engine, 1, 2, "en", hello().segments), partial)
        assertEquals(50, partial.percent)
        assertEquals("a stopped making is not announced", emptyList<Making>(), finished)

        // Carried on: only the second piece is listened to again
        hold = false
        listened.clear()
        jobs.start(5, engine, 600_000)
        withTimeout(10_000) { jobs.states.first { it[5] == Making.Idle && kept.isNotEmpty() } }
        assertEquals(1, listened.size)
        assertEquals("the first piece's lines kept, the second's after them", 2, kept.single().size)
        assertNull("nothing left to carry on from", progress.load(5))
        assertEquals(listOf<Making>(Making.Idle), finished)
    }

    @Test
    fun `a service refused part way keeps what it heard, and another engine starts over`() = runBlocking {
        val progress = SpeechProgress(folder.newFolder())
        var refuse = false
        val sent = mutableListOf<Long>()
        val jobs = SpeechJobs(
            scope = CoroutineScope(Dispatchers.IO),
            models = SpeechModels(folder.newFolder()),
            decode = { _, onPiece ->
                onPiece(FloatArray(10), 0)
                onPiece(FloatArray(10), 300_000)
                true
            },
            listen = { _, _, _, _, _ -> null },
            listenCloud = { speech, _, _ ->
                sent += if (speech == CloudSpeech.GROQ) 1 else 2
                // Groq hears the first piece, then is busy
                if (speech == CloudSpeech.GROQ && refuse) CloudHeard.Failed("Groq is busy.") else CloudHeard.Said(hello()).also { refuse = true }
            },
            keep = { _, _, _ -> true },
            progress = progress,
        )
        jobs.start(9, Engine.Cloud(CloudSpeech.GROQ), 600_000)
        withTimeout(10_000) { jobs.states.first { it[9] is Making.Failed } }
        assertEquals(50, progress.load(9)!!.percent)

        jobs.start(9, Engine.Cloud(CloudSpeech.OPENAI), 600_000)
        withTimeout(10_000) { jobs.states.first { it[9] == Making.Idle } }
        assertEquals("OpenAI heard both pieces itself", listOf(1L, 1L, 2L, 2L), sent)
    }

    @Test
    fun `what was heard is kept and read back whole`() {
        val store = SpeechProgress(folder.newFolder())
        val partial = Partial(Engine.Cloud(CloudSpeech.OPENAI), 3, 5, null, hello(600_000).segments)
        assertTrue(store.keep(4, partial))
        assertEquals(partial, store.load(4))
        assertEquals(60, partial.percent)
        store.clear(4)
        assertNull(store.load(4))
        assertEquals(Engine.OnPhone(SpeechModel.BASE), Engine.of(Engine.OnPhone(SpeechModel.BASE).key))
        assertNull(Engine.of("phone:GONE"))
    }

    @Test
    fun `the notification names the video, or how many, and how far each has got`() {
        val one = SpeechNotice.of(mapOf(1L to Making.Listening(40)), mapOf(1L to "Talk"))
        assertEquals(SpeechNotice("Talk", "On the phone · 40%", 40), one)
        val two = SpeechNotice.of(
            mapOf(1L to Making.Listening(40), 2L to Making.Listening(80, "Groq"), 3L to Making.Waiting, 4L to Making.Idle),
            mapOf(1L to "Talk", 2L to "Song"),
        )
        assertEquals(SpeechNotice("Making subtitles for 2 videos", "On the phone · 40% · Groq · 80% · 1 waiting", 60), two)
        assertNull(SpeechNotice.of(mapOf(1L to Making.Fetching(SpeechModel.TINY, 1, 2)), emptyMap()).percent)
    }
}
