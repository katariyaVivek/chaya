package com.chaya.app.subtitles

import com.chaya.app.ai.AiResult
import com.chaya.app.ai.AiSettings
import com.chaya.app.ai.Endpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** How finding a video's highlights is going. One value per video. */
sealed class Finding {
    data object Idle : Finding()
    data object Running : Finding()

    /** It stopped short, for this reason in words; highlights found before are kept. */
    data class Failed(val message: String) : Finding()
}

/** Finding highlights, one video at a time each, in the app's scope: leaving the player does not stop it. */
class HighlightJobs(
    private val scope: CoroutineScope,
    private val store: HighlightStore,
    private val subtitles: (Long) -> SavedSubtitles?,
    private val finder: HighlightFinder,
    private val settings: () -> AiSettings,
    private val endpoint: (AiSettings) -> AiResult<Endpoint>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _states = MutableStateFlow<Map<Long, Finding>>(emptyMap())
    val states: StateFlow<Map<Long, Finding>> = _states.asStateFlow()
    private val running = mutableMapOf<Long, Job>()

    private fun set(id: Long, state: Finding) = _states.update { it + (id to state) }

    /** Finds about [count] highlights in download [id]'s subtitles, in place of those found before. */
    @Synchronized
    fun start(id: Long, title: String, count: Int) {
        if (running[id]?.isActive == true) return
        running[id] = scope.launch { run(id, title, count) }
    }

    @Synchronized
    fun stop(id: Long) {
        running.remove(id)?.cancel()
    }

    private suspend fun run(id: Long, title: String, count: Int) {
        val chosen = settings()
        val service = chosen.service()
        val model = chosen.model?.trim()?.takeIf { it.isNotEmpty() }
        if (service == null || model == null) return set(id, Finding.Failed("Set up your AI model first, under Library › ⋮ › AI model."))
        val reached = when (val e = endpoint(chosen)) {
            is AiResult.Done -> e.value
            is AiResult.Failed -> return set(id, Finding.Failed(e.error.message(service.spoken)))
        }
        val source = subtitles(id) ?: return set(id, Finding.Failed("This video has no subtitles to find highlights in."))
        set(id, Finding.Running)
        try {
            val video = HighlightFinder.Video(title, source.lines, TranslationJobs.englishName(source.language))
            when (val found = finder.find(reached, model, video, count)) {
                is AiResult.Done -> {
                    if (found.value.isEmpty()) return set(id, Finding.Failed("The model found no highlights. Try another model."))
                    store.keep(id, FoundHighlights(source.language, TranslationJobs.label(service.id, service.name, model), now(), found.value))
                    set(id, Finding.Idle)
                }
                is AiResult.Failed -> set(id, Finding.Failed(found.error.message(service.spoken)))
            }
        } catch (stopped: CancellationException) {
            set(id, Finding.Idle)
            throw stopped
        } finally {
            synchronized(this) { if (running[id]?.isActive != true) running.remove(id) }
        }
    }
}
