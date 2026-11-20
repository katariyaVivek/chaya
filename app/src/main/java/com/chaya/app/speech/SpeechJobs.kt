package com.chaya.app.speech

import com.chaya.app.subtitles.SubtitleLine
import com.chaya.app.subtitles.Subtitles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** What listens: a model on the phone, or a service with the person's key (the sound is then sent to it). */
sealed class Engine {
    data class OnPhone(val model: SpeechModel) : Engine()
    data class Cloud(val speech: CloudSpeech) : Engine()
}

/** A service's answer for a piece: what was said, or why not in words. */
sealed class CloudHeard {
    data class Said(val transcript: Transcript) : CloudHeard()
    data class Failed(val message: String) : CloudHeard()
}

/** How making a video's subtitles is going. One value per video. */
sealed class Making {
    data object Idle : Making()

    /** The model arriving, the first time: bytes so far of all. */
    data class Fetching(val model: SpeechModel, val done: Long, val total: Long) : Making()

    /** Listening, this far through the video; [service] names the service the sound is sent to, null on the phone. */
    data class Listening(val percent: Int, val service: String? = null) : Making()

    data class Failed(val message: String) : Making()
}

/**
 * *Make subtitles*: a saved video with none listened to on the phone by whisper.cpp, its words cut into short lines
 * ([SpeechLines]) and kept as the video's subtitles, so the player, the transcript, the Library's search, translation
 * and highlights all have them. The model is fetched the first time. Nothing leaves the phone but that one fetch.
 * One video at a time, in the app's scope.
 */
class SpeechJobs(
    private val scope: CoroutineScope,
    private val models: SpeechModels,
    /** Decodes download [Long]'s sound into 16 kHz pieces for the callback (with each piece's start, ms); false without sound. */
    private val decode: (Long, (FloatArray, Long) -> Unit) -> Boolean,
    /** whisper.cpp: the JSON it writes for a piece, or null. */
    private val listen: (File, SpeechModel, FloatArray, String?, Whisper.Listener) -> String?,
    /** A service: what it heard in a piece (sent with the person's key), or why not. Blocking, as [listen] is. */
    private val listenCloud: (CloudSpeech, FloatArray, String?) -> CloudHeard = { _, _, _ -> CloudHeard.Failed("No service is set up.") },
    /** Keeps the lines as download [Long]'s subtitles in [String] (a language tag); whether they were kept. */
    private val keep: (Long, String, List<SubtitleLine>) -> Boolean,
    private val stopListening: () -> Unit = {},
) {
    private val _states = MutableStateFlow<Map<Long, Making>>(emptyMap())
    val states: StateFlow<Map<Long, Making>> = _states.asStateFlow()
    private var running: Pair<Long, Job>? = null

    private fun set(id: Long, state: Making) = _states.update { it + (id to state) }

    /** Makes download [id]'s subtitles with [engine]; [lengthMs] says how far along it is. One video at a time. */
    @Synchronized
    fun start(id: Long, engine: Engine, lengthMs: Long) {
        val now = running
        if (now != null && now.second.isActive) {
            if (now.first != id) set(id, Making.Failed("Chaya is making another video's subtitles. Try again when it's done."))
            return
        }
        running = id to scope.launch { run(id, engine, lengthMs) }
    }

    @Synchronized
    fun stop(id: Long) {
        val now = running ?: return
        if (now.first != id) return
        now.second.cancel()
        stopListening()
    }

    /** A service refused a piece: making stops there, saying why. */
    private class CloudStopped(message: String) : Exception(message)

    private suspend fun run(id: Long, engine: Engine, lengthMs: Long) {
        try {
            val file = (engine as? Engine.OnPhone)?.let { phone ->
                models.fetch(phone.model) { done, total -> set(id, Making.Fetching(phone.model, done, total)) }
            }
            val service = (engine as? Engine.Cloud)?.speech?.label
            set(id, Making.Listening(0, service))
            val pieces = maxOf(1L, (lengthMs + Sound16k.CHUNK_SECONDS * 1000L - 1) / (Sound16k.CHUNK_SECONDS * 1000L))
            val heard = mutableListOf<Segment>()
            var language: String? = null
            var piece = 0
            var failed = false
            val context = currentCoroutineContext()
            val hasSound = decode(id) { samples, offset ->
                if (!context.isActive) throw CancellationException("Stopped")
                val done = piece
                // Soft voices lifted to be heard as well as loud ones (Leveler), for whichever engine listens
                val even = Leveler.even(samples)
                val transcript = when (engine) {
                    is Engine.OnPhone -> listen(file!!, engine.model, even, language) { percent ->
                        set(id, Making.Listening(((done * 100L + percent) / pieces).toInt().coerceIn(0, 99), service))
                    }?.let(Transcript::parse)
                    is Engine.Cloud -> when (val heard = listenCloud(engine.speech, even, language)) {
                        is CloudHeard.Said -> heard.transcript
                        is CloudHeard.Failed -> throw CloudStopped(heard.message)
                    }
                }
                if (!context.isActive) throw CancellationException("Stopped")
                set(id, Making.Listening(((done + 1) * 100L / pieces).toInt().coerceIn(0, 99), service))
                if (transcript == null) {
                    failed = true
                } else {
                    // The language heard in the first piece is kept for the rest, so a video stays in one.
                    if (language == null) language = transcript.language.takeIf { it.isNotEmpty() }
                    heard += transcript.segments.map { s ->
                        s.copy(start = s.start + offset, end = s.end + offset, words = s.words.map { it.copy(start = it.start + offset, end = it.end + offset) })
                    }
                }
                piece++
            }
            val said = Transcript(language ?: "", heard)
            val lines = said.lines()
            set(
                id,
                when {
                    !hasSound -> Making.Failed("This video has no sound to listen to.")
                    failed && lines.isEmpty() -> Making.Failed("Chaya couldn't listen to this video. The phone may be short of memory: try the Tiny model.")
                    lines.isEmpty() -> Making.Failed("Nothing was said that Chaya could hear.")
                    !keep(id, Subtitles.fileLanguage(said.language) ?: "und", lines) -> Making.Failed("Chaya couldn't keep the subtitles.")
                    else -> Making.Idle
                },
            )
        } catch (e: CloudStopped) {
            set(id, Making.Failed(e.message ?: "The service couldn't make the subtitles."))
        } catch (e: ModelException) {
            set(id, Making.Failed(e.message ?: "Chaya couldn't fetch the model."))
        } catch (stopped: CancellationException) {
            set(id, Making.Idle)
            throw stopped
        } finally {
            synchronized(this) { if (running?.first == id && running?.second?.isActive != true) running = null }
        }
    }
}
