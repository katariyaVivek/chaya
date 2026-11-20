package com.chaya.app.subtitles

import com.chaya.app.ai.AiResult
import com.chaya.app.ai.AiSettings
import com.chaya.app.ai.Endpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** How translating a video is going. One value per video. */
sealed class Translating {
    /** Not translating (never, finished, or stopped). */
    data object Idle : Translating()

    /** [done] of [total] lines into [to], by [model]. */
    data class Running(val to: String, val model: String, val done: Int, val total: Int) : Translating()

    /** It stopped short, for this reason in words; what was translated is kept. */
    data class Failed(val message: String) : Translating()
}

/**
 * Translations under way, one per video, in the app's scope: leaving the player does not stop one. What is translated
 * is kept after every batch, so a translation stopped or failed part-way carries on from there.
 */
class TranslationJobs(
    private val scope: CoroutineScope,
    private val store: TranslationStore,
    private val subtitles: (Long) -> SavedSubtitles?,
    private val translator: Translator,
    /** What the person chose in Settings › AI model, and the endpoint it gives (with its key). */
    private val settings: () -> AiSettings,
    private val endpoint: (AiSettings) -> AiResult<Endpoint>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _states = MutableStateFlow<Map<Long, Translating>>(emptyMap())
    val states: StateFlow<Map<Long, Translating>> = _states.asStateFlow()
    private val running = mutableMapOf<Long, Job>()

    fun state(id: Long): Translating = _states.value[id] ?: Translating.Idle

    private fun set(id: Long, state: Translating) = _states.update { it + (id to state) }

    /** Translates download [id]'s subtitles into [to] ("es"), carrying on from the same model's version if any. */
    @Synchronized
    fun start(id: Long, title: String, to: String) {
        if (running[id]?.isActive == true) return
        running[id] = scope.launch { run(id, title, to) }
    }

    /** Stops download [id]'s translation; what is translated is kept. */
    @Synchronized
    fun stop(id: Long) {
        running.remove(id)?.cancel()
    }

    private suspend fun run(id: Long, title: String, to: String) {
        val chosen = settings()
        val service = chosen.service()
        val model = chosen.model?.trim()?.takeIf { it.isNotEmpty() }
        if (service == null || model == null) return set(id, Translating.Failed("Set up your AI model first, under Library › ⋮ › AI model."))
        val reached = when (val e = endpoint(chosen)) {
            is AiResult.Done -> e.value
            is AiResult.Failed -> return set(id, Translating.Failed(e.error.message(service.spoken)))
        }
        val source = subtitles(id) ?: return set(id, Translating.Failed("This video has no subtitles to translate."))
        val texts = source.lines.map { it.text }
        val label = label(service.id, service.name, model)
        val kept = store.find(id, source.language, to, label)?.takeIf { it.lines.size == texts.size }
        val done: MutableList<String?> = kept?.lines?.toMutableList() ?: MutableList(texts.size) { null }
        var translation = Translation(source.language, to, label, kept?.made ?: now(), done.toList())
        store.keep(id, translation)
        set(id, Translating.Running(to, label, translation.done, texts.size))
        val job = Translator.Job(title = title, lines = texts, into = englishName(to), model = model)
        try {
            val error = translator.translate(reached, job, done) { batch ->
                translation = translation.copy(lines = batch)
                store.keep(id, translation)
                set(id, Translating.Running(to, label, translation.done, texts.size))
            }
            translation = translation.copy(lines = done.toList())
            store.keep(id, translation)
            set(id, error?.let { Translating.Failed(it.message(service.spoken)) } ?: Translating.Idle)
        } catch (stopped: CancellationException) {
            withContext(NonCancellable) {
                store.keep(id, translation.copy(lines = done.toList()))
                set(id, Translating.Idle)
            }
            throw stopped
        } finally {
            synchronized(this) { if (running[id]?.isActive != true) running.remove(id) }
        }
    }

    companion object {
        /** How a version is named: "Groq · llama-3.3-70b", "Your service · m" for one the person named. */
        fun label(serviceId: String, serviceName: String, model: String): String =
            "${if (serviceId == com.chaya.app.ai.AiService.CUSTOM) "Your service" else serviceName.removeSuffix(" on a PC")} · ${model.trim()}"

        /** The language's name in English, for the model ("Spanish" for "es"). */
        fun englishName(tag: String): String =
            Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH).takeIf { it.isNotBlank() } ?: tag
    }
}
