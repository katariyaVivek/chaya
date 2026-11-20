package com.chaya.app.subtitles

import com.chaya.app.ai.AiClient
import com.chaya.app.ai.AiError
import com.chaya.app.ai.AiResult
import com.chaya.app.ai.Ask
import com.chaya.app.ai.Endpoint
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * A video's subtitles translated by the person's own AI model, as Chaya for Windows does it (`core::translate`,
 * ported with its tests): every request carries the video's title and the whole transcript, so a misheard name is put
 * right and a term is translated the same way each time, and the lines go out numbered and must come back with the
 * same numbers, one for one, so the translation stays in step with the original.
 *
 * The lines go a batch at a time, small enough for any model. A line a reply leaves out (or merges into the one
 * before) is asked for again on its own. What is translated is handed back after every batch to be kept, so a
 * translation stopped (the coroutine cancelled) or failed part-way carries on from there and nothing is paid for twice.
 */
class Translator(private val client: AiClient) {

    /** What is translated: the video's title, its lines, the language they go into ("Spanish"), and the model. */
    class Job(val title: String, val lines: List<String>, val into: String, val model: String)

    /**
     * Translates [job]'s lines into [done] (one place per line; those already there are kept), and hands [done] to
     * [onBatch] after every batch. Cancelling the coroutine stops it between requests or during one.
     */
    suspend fun translate(
        endpoint: Endpoint,
        job: Job,
        done: MutableList<String?>,
        onBatch: (List<String?>) -> Unit = {},
    ): TranslateError? {
        while (done.size < job.lines.size) done.add(null)
        while (done.size > job.lines.size) done.removeAt(done.lastIndex)
        for (batch in batches(job.lines, done)) {
            when (val answered = ask(endpoint, job, batch)) {
                is Asked.Lines -> keep(answered.lines, batch, done)
                // A reply that wasn't the JSON asked for: each line on its own, below.
                Asked.NotJson -> Unit
                is Asked.Failed -> return answered.error
            }
            for (n in batch.filter { done[it] == null }) {
                repeat(ALONE) {
                    if (done[n] != null) return@repeat
                    when (val answered = ask(endpoint, job, listOf(n))) {
                        is Asked.Lines -> keep(answered.lines, listOf(n), done)
                        Asked.NotJson -> Unit
                        is Asked.Failed -> return answered.error
                    }
                }
            }
            onBatch(done.toList())
        }
        val missing = done.count { it == null }
        return if (missing == 0) null else TranslateError.Incomplete(missing)
    }

    private sealed class Asked {
        class Lines(val lines: List<Pair<Int, String>>) : Asked()
        data object NotJson : Asked()
        class Failed(val error: TranslateError) : Asked()
    }

    /** Asks for lines [asked] (numbered from 0 here, from 1 to the model), waiting out a rate limit. */
    private suspend fun ask(endpoint: Endpoint, job: Job, asked: List<Int>): Asked {
        val question = Ask(model = job.model, system = SYSTEM, user = question(job, asked), maxTokens = MAX_TOKENS)
        var tried = 1
        while (true) {
            when (val reply = client.askJson(endpoint, question) { it.optJSONArray("lines") != null }) {
                is AiResult.Done -> return Asked.Lines(read(reply.value, job.lines.size))
                is AiResult.Failed -> when (val error = reply.error) {
                    AiError.NotJson -> return Asked.NotJson
                    is AiError.RateLimited -> if (tried < RATE_TRIES) {
                        delay((error.seconds ?: 20L).coerceAtMost(LONGEST_WAIT) * 1000)
                        tried++
                    } else {
                        return Asked.Failed(TranslateError.Ai(error))
                    }
                    else -> return Asked.Failed(TranslateError.Ai(error))
                }
            }
        }
    }

    companion object {
        /** At most this many lines, and characters of them, in one request. */
        const val BATCH_LINES = 40
        const val BATCH_CHARS = 3_000

        /** The whole transcript goes with each request while it is this short; a longer one, the lines around the batch. */
        const val WHOLE = 60_000
        const val AROUND = 16_000

        /** How often a line left out of its batch is asked for on its own. */
        const val ALONE = 2

        /** How often a request refused for too many requests is tried again, and the longest wait (s) before it. */
        const val RATE_TRIES = 4
        const val LONGEST_WAIT = 90L

        /** How long a translation may run on, for Anthropic's protocol, which asks. */
        const val MAX_TOKENS = 8_192

        // Beyond Windows' core::translate: a sentence said over two lines ("this is the most beautiful hands" | "I have
        // ever seen") made no sense in another language translated line by line, so a sentence's lines go together.
        private const val SYSTEM = "You translate a video's subtitles. Keep every line's number: give exactly one " +
            "non-empty item for each number asked for, never merging two numbers into one or leaving one out. When a " +
            "sentence runs over several numbered lines, translate that whole sentence so it reads naturally in the " +
            "target language, in its own word order, then share the translation out over those same lines, in order, " +
            "each line taking the part that reads well as it is shown. Use the title and the whole transcript to get " +
            "names and terms right (put right a name the transcript misheard) and to translate each term the same way " +
            "every time. Write natural, short subtitles. Answer with one JSON object and nothing else."

        /** Whether [line] ends a sentence: a stop, a question or an exclamation (in the scripts Chaya's people use). */
        fun endsSentence(line: String): Boolean {
            val end = line.trimEnd().trimEnd('"', '\'', '”', '’', ')', ']', '»')
            return end.isEmpty() || end.last() in ".?!…。？！؟।♪"
        }

        /** The runs of lines among [asked] that make one sentence together, as their numbers from 1 ("4–5"). */
        fun sentences(lines: List<String>, asked: List<Int>): List<IntRange> {
            val runs = mutableListOf<IntRange>()
            var from = asked.firstOrNull() ?: return runs
            for ((i, n) in asked.withIndex()) {
                val next = asked.getOrNull(i + 1)
                if (endsSentence(lines[n]) || next == null || next != n + 1) {
                    if (n > from) runs += (from + 1)..(n + 1)
                    from = next ?: break
                }
            }
            return runs
        }

        /**
         * The lines not yet translated, in runs of consecutive lines no longer than a batch; a batch that is full ends
         * where a sentence does when it can, so a sentence's lines are translated together.
         */
        fun batches(lines: List<String>, done: List<String?>): List<List<Int>> {
            val batches = mutableListOf<MutableList<Int>>()
            var chars = 0
            for (n in lines.indices.filter { done[it] == null }) {
                val last = batches.lastOrNull()
                val follows = last != null && last.last() == n - 1
                val fits = last != null && last.size < BATCH_LINES && chars + lines[n].length <= BATCH_CHARS && follows
                if (!fits) {
                    val next = mutableListOf<Int>()
                    // A full batch cut mid-sentence gives the sentence's start to the next batch, if it ends somewhere
                    if (last != null && follows && !endsSentence(lines[last.last()])) {
                        val cut = last.indexOfLast { endsSentence(lines[it]) }
                        if (cut >= 0) {
                            next += last.subList(cut + 1, last.size)
                            repeat(last.size - cut - 1) { last.removeAt(last.lastIndex) }
                        }
                    }
                    batches.add(next)
                    chars = next.sumOf { lines[it].length }
                }
                chars += lines[n].length
                batches.last().add(n)
            }
            return batches
        }

        /** Keeps each translation the reply gave for a line it was asked for; anything else in it is left. */
        private fun keep(lines: List<Pair<Int, String>>, asked: List<Int>, done: MutableList<String?>) {
            for ((n, text) in lines) if (n in asked && done[n] == null) done[n] = text
        }

        /** The question for lines [asked]: the title, the transcript (all of it, or the lines around), and the lines. */
        fun question(job: Job, asked: List<Int>): String {
            fun numbered(n: Int) = "${n + 1}: ${job.lines[n].replace('\n', ' ')}"
            val whole = job.lines.sumOf { it.length + 8 }
            val first = asked.first()
            val last = asked.last()
            val around = if (whole <= WHOLE) {
                job.lines.indices
            } else {
                // As many lines either side as fit, half before and half after.
                var from = first
                var to = last + 1
                var room = AROUND
                while (room > 0 && (from > 0 || to < job.lines.size)) {
                    if (from > 0) {
                        from--
                        room = (room - (job.lines[from].length + 8)).coerceAtLeast(0)
                    }
                    if (to < job.lines.size) {
                        room = (room - (job.lines[to].length + 8)).coerceAtLeast(0)
                        to++
                    }
                }
                from until to
            }
            val transcript = around.joinToString("\n") { numbered(it) }
            val lines = asked.joinToString("\n") { numbered(it) }
            val numbers = asked.joinToString(", ") { (it + 1).toString() }
            val runs = sentences(job.lines, asked)
            val together = if (runs.isEmpty()) "" else "\n\nThese lines are one sentence each, translated as a whole and shared " +
                "out over the same lines: ${runs.joinToString(", ") { "${it.first}–${it.last}" }}."
            return "Video title: ${job.title}\n\nThe transcript, for context (number: line):\n$transcript\n\nTranslate these " +
                "lines into ${job.into}. Answer with {\"lines\": [{\"n\": number, \"text\": \"the translation\"}]}, one item " +
                "for each of the numbers $numbers and no others:\n$lines$together"
        }

        /** The lines in a reply, by line (from 0), each translation trimmed and not empty. */
        fun read(reply: JSONObject, count: Int): List<Pair<Int, String>> {
            val items = reply.optJSONArray("lines") ?: return emptyList()
            return (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val number: Any? = item.opt("n")
                val n = (if (number is Number) number.toLong().takeIf { number.toDouble() == it.toDouble() } else null)
                    ?: (number as? String)?.trim()?.toLongOrNull()
                    ?: return@mapNotNull null
                val text = (item.opt("text") as? String)?.trim() ?: return@mapNotNull null
                if (n in 1..count && text.isNotEmpty()) (n - 1).toInt() to text else null
            }
        }
    }
}

/** Why a translation stopped short. */
sealed class TranslateError {
    data class Ai(val error: AiError) : TranslateError()

    /** Every line was asked for, and this many never came back. */
    data class Incomplete(val missing: Int) : TranslateError()

    fun message(service: String): String = when (this) {
        is Ai -> error.message(service)
        is Incomplete -> if (missing == 1) "The model left out a line. Try again: what's translated is kept."
        else "The model left out $missing lines. Try again: what's translated is kept."
    }
}
