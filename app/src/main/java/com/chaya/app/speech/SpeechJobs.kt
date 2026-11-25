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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** What listens: a model on the phone, or a service with the person's key (the sound is then sent to it). */
sealed class Engine {
    data class OnPhone(val model: SpeechModel) : Engine()
    data class Cloud(val speech: CloudSpeech) : Engine()

    /** The engine as a word, kept with a [Partial] and by the card so the choice survives the phone turning. */
    val key: String
        get() = when (this) {
            is OnPhone -> "phone:${model.name}"
            is Cloud -> "cloud:${speech.name}"
        }

    /** Which lane it listens in: one video at a time on the phone's processor, and one at a time sent to a service. */
    val lane: Lane get() = if (this is OnPhone) Lane.PHONE else Lane.CLOUD

    companion object {
        fun of(key: String): Engine? {
            val (kind, name) = key.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            return when (kind) {
                "phone" -> SpeechModel.entries.firstOrNull { it.name == name }?.let { OnPhone(it) }
                "cloud" -> CloudSpeech.entries.firstOrNull { it.name == name }?.let { Cloud(it) }
                else -> null
            }
        }
    }
}

/** Where a video is listened to. The phone and a service work at the same time, each on one video. */
enum class Lane { PHONE, CLOUD }

/** A service's answer for a piece: what was said, or why not in words. */
sealed class CloudHeard {
    data class Said(val transcript: Transcript) : CloudHeard()
    data class Failed(val message: String) : CloudHeard()
}

/** How making a video's subtitles is going. One value per video. */
sealed class Making {
    data object Idle : Making()

    /** Its lane is busy with another video; it starts when that one is done. */
    data object Waiting : Making()

    /** The model arriving, the first time: bytes so far of all. */
    data class Fetching(val model: SpeechModel, val done: Long, val total: Long) : Making()

    /** Listening, this far through the video; [service] names the service the sound is sent to, null on the phone. */
    data class Listening(val percent: Int, val service: String? = null) : Making()

    data class Failed(val message: String) : Making()

    /** Waiting, fetching or listening: what keeps the app working in the background, and what *Stop* ends. */
    val isWorking: Boolean get() = this is Waiting || this is Fetching || this is Listening
}

/**
 * *Make subtitles*: a saved video with none listened to on the phone (whisper.cpp or Parakeet) or by a service with the
 * person's key, its words cut into short lines ([SpeechLines]) and kept as the video's subtitles, so the player, the
 * transcript, the Library's search, translation and highlights all have them. A model is fetched the first time.
 *
 * The phone and a service each listen to one video at a time, at the same time; another video asked for waits its turn
 * ([Making.Waiting]). What was heard is kept after every five-minute piece ([SpeechProgress]), so making that was stopped
 * or ended with the app carries on from there. Runs in the app's scope; [onBusy] is told when work starts, so the app can
 * keep itself running while it goes on in the background.
 */
class SpeechJobs(
    private val scope: CoroutineScope,
    private val models: SpeechModels,
    /** Decodes download [Long]'s sound into 16 kHz pieces for the callback (with each piece's start, ms); false without sound. */
    private val decode: (Long, (FloatArray, Long) -> Unit) -> Boolean,
    /** whisper.cpp: the JSON it writes for a piece, or null. */
    private val listen: (File, SpeechModel, FloatArray, String?, Whisper.Listener) -> String?,
    /** Parakeet (sherpa-onnx): what it heard in a piece, with the model's folder, hearing how far in percent and asking whether to stop; or null. */
    private val listenParakeet: (File, FloatArray, (Int) -> Unit, () -> Boolean) -> Transcript? = { _, _, _, _ -> null },
    /** A service: what it heard in a piece (sent with the person's key), or why not. Blocking, as [listen] is. */
    private val listenCloud: (CloudSpeech, FloatArray, String?) -> CloudHeard = { _, _, _ -> CloudHeard.Failed("No service is set up.") },
    /** Keeps the lines as download [Long]'s subtitles in [String] (a language tag); whether they were kept. */
    private val keep: (Long, String, List<SubtitleLine>) -> Boolean,
    /** Ends whisper.cpp's listening under way. */
    private val stopListening: () -> Unit = {},
    /** Ends the upload or answer under way from a service. */
    private val stopCloud: () -> Unit = {},
    /** Making on the phone is over (done, failed or stopped): a model loaded for it lets its memory go. */
    private val done: () -> Unit = {},
    /** Where how far each video got is kept; null keeps nothing. */
    private val progress: SpeechProgress? = null,
    /** Work has started or is waiting. */
    private val onBusy: () -> Unit = {},
    /** A video's making ended on its own, with its subtitles kept ([Making.Idle]) or [Making.Failed]; not when stopped. */
    private val onFinished: (Long, Making) -> Unit = { _, _ -> },
) {
    private val _states = MutableStateFlow<Map<Long, Making>>(emptyMap())
    val states: StateFlow<Map<Long, Making>> = _states.asStateFlow()

    private class Run(val id: Long, val engine: Engine, val lengthMs: Long) {
        val stopped = AtomicBoolean(false)
        var job: Job? = null
    }

    private val running = mutableMapOf<Lane, Run>()
    private val waiting = mutableListOf<Run>()

    private fun set(id: Long, state: Making) = _states.update { it + (id to state) }

    /** Sets [run]'s state unless it was stopped: *Stop* says Idle at once, whatever the engine is still finishing. */
    private fun set(run: Run, state: Making) {
        if (!run.stopped.get()) set(run.id, state)
    }

    /** How far making download [id]'s subtitles got before it stopped, or null. */
    fun partial(id: Long): Partial? = progress?.load(id)

    /** Forgets how far download [id] got, so the next making starts from the beginning. */
    fun startOver(id: Long) {
        progress?.clear(id)
    }

    /**
     * Makes download [id]'s subtitles with [engine]; [lengthMs] says how far along it is. Carries on from what was heard
     * before with the same engine. Waits when [engine]'s lane is busy with another video.
     */
    @Synchronized
    fun start(id: Long, engine: Engine, lengthMs: Long) {
        if (running.values.any { it.id == id && !it.stopped.get() } || waiting.any { it.id == id }) return
        val run = Run(id, engine, lengthMs)
        val busy = running[engine.lane]
        if (busy != null) {
            waiting += run
            set(id, Making.Waiting)
        } else {
            launch(run)
        }
        onBusy()
    }

    private fun launch(run: Run) {
        running[run.engine.lane] = run
        set(run.id, Making.Listening(0, (run.engine as? Engine.Cloud)?.speech?.label))
        run.job = scope.launch { run(run) }
    }

    /** Stops making download [id]'s subtitles, keeping what was heard so far; it says Idle at once. */
    @Synchronized
    fun stop(id: Long) {
        waiting.removeAll { it.id == id }.also { if (it) set(id, Making.Idle) }
        val run = running.values.firstOrNull { it.id == id } ?: return
        run.stopped.set(true)
        set(id, Making.Idle)
        run.job?.cancel()
        when (run.engine.lane) {
            Lane.PHONE -> stopListening()
            Lane.CLOUD -> stopCloud()
        }
    }

    /** Stops every video's making. */
    fun stopAll() {
        val ids = synchronized(this) { running.values.map { it.id } + waiting.map { it.id } }
        ids.forEach(::stop)
    }

    /** A service refused a piece: making stops there, saying why. */
    private class CloudStopped(message: String) : Exception(message)

    /** Stopped while listening: the pieces' loop ends here. */
    private class Stopped : CancellationException("Stopped")

    private suspend fun run(run: Run) {
        val id = run.id
        val engine = run.engine
        var ended: Making? = null
        try {
            val file = (engine as? Engine.OnPhone)?.let { phone ->
                models.fetch(phone.model) { done, total -> set(run, Making.Fetching(phone.model, done, total)) }
            }
            val service = (engine as? Engine.Cloud)?.speech?.label
            val pieces = maxOf(1, ((run.lengthMs + Sound16k.CHUNK_SECONDS * 1000L - 1) / (Sound16k.CHUNK_SECONDS * 1000L)).toInt())
            // What was heard before with this engine, carried on from; with another, started again
            val before = progress?.load(id)?.takeIf { it.engine == engine }
            if (before == null) progress?.clear(id)
            val heard = before?.segments?.toMutableList() ?: mutableListOf()
            var language: String? = before?.language
            val from = before?.piecesDone ?: 0
            set(run, Making.Listening((from * 100 / pieces).coerceIn(0, 99), service))
            var piece = 0
            var failed = false
            val context = currentCoroutineContext()
            fun stopped() = run.stopped.get() || !context.isActive
            val hasSound = decode(id) { samples, offset ->
                if (stopped()) throw Stopped()
                val done = piece
                piece++
                if (done < from) return@decode
                // Soft voices lifted to be heard as well as loud ones (Leveler), for whichever engine listens
                val even = Leveler.even(samples)
                val transcript = when (engine) {
                    is Engine.OnPhone -> {
                        val hearing = { percent: Int ->
                            set(run, Making.Listening(((done * 100L + percent) / pieces).toInt().coerceIn(0, 99), service))
                        }
                        when (engine.model.engine) {
                            SpeechEngine.WHISPER -> listen(file!!, engine.model, even, language) { hearing(it) }?.let(Transcript::parse)
                            SpeechEngine.PARAKEET -> listenParakeet(file!!, even, hearing, ::stopped)
                        }
                    }
                    is Engine.Cloud -> when (val said = listenCloud(engine.speech, even, language)) {
                        is CloudHeard.Said -> said.transcript
                        is CloudHeard.Failed -> if (stopped()) throw Stopped() else throw CloudStopped(said.message)
                    }
                }
                if (stopped()) throw Stopped()
                set(run, Making.Listening(((done + 1) * 100L / pieces).toInt().coerceIn(0, 99), service))
                if (transcript == null) {
                    failed = true
                } else {
                    // The language heard in the first piece is kept for the rest, so a video stays in one.
                    if (language == null) language = transcript.language.takeIf { it.isNotEmpty() }
                    heard += transcript.segments.map { s ->
                        s.copy(start = s.start + offset, end = s.end + offset, words = s.words.map { it.copy(start = it.start + offset, end = it.end + offset) })
                    }
                    progress?.keep(id, Partial(engine, done + 1, pieces, language, heard))
                }
            }
            if (stopped()) throw Stopped()
            val said = Transcript(language ?: "", heard)
            val lines = said.lines()
            ended = when {
                !hasSound -> Making.Failed("This video has no sound to listen to.")
                failed && lines.isEmpty() -> Making.Failed("Chaya couldn't listen to this video. The phone may be short of memory: try the Tiny model.")
                lines.isEmpty() -> Making.Failed("Nothing was said that Chaya could hear.")
                !keep(id, Subtitles.fileLanguage(said.language) ?: "und", lines) -> Making.Failed("Chaya couldn't keep the subtitles.")
                else -> Making.Idle.also { progress?.clear(id) }
            }
        } catch (e: CloudStopped) {
            ended = Making.Failed(e.message ?: "The service couldn't make the subtitles.")
        } catch (e: ModelException) {
            ended = Making.Failed(e.message ?: "Chaya couldn't fetch the model.")
        } catch (stopped: CancellationException) {
            set(id, Making.Idle)
            if (stopped !is Stopped) throw stopped
        } finally {
            ended?.let { set(run, it) }
            if (engine.lane == Lane.PHONE) runCatching { done() }
            if (ended != null && !run.stopped.get()) runCatching { onFinished(id, ended) }
            synchronized(this) {
                if (running[engine.lane] === run) running.remove(engine.lane)
                waiting.firstOrNull { it.engine.lane == engine.lane }?.let { next ->
                    waiting.remove(next)
                    launch(next)
                }
            }
        }
    }
}
