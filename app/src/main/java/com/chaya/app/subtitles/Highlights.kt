package com.chaya.app.subtitles

import com.chaya.app.ai.AiClient
import com.chaya.app.ai.AiError
import com.chaya.app.ai.AiResult
import com.chaya.app.ai.Ask
import com.chaya.app.ai.Endpoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** A highlight: its title, why it stands out, its lines (from 0, both included), and its times in milliseconds. */
data class Highlight(val title: String, val reason: String, val first: Int, val last: Int, val start: Long, val end: Long)

/**
 * The moments of a video worth watching on their own, found by the person's AI model, as Chaya for Windows finds them
 * (`core::highlights`, ported with its tests): the model is given the video's title and its subtitles, numbered line
 * by line, and answers with ranges of lines, each with a title and the reason it stands out. Every range is checked
 * (inside the video, the right way round, not overlapping) and turned into times by its lines, so a highlight starts
 * with a line and never in the middle of a word. A long video goes in parts, each asked for its share with a score,
 * and the best are kept. Cancelling the coroutine stops it.
 */
class HighlightFinder(private val client: AiClient) {

    /** What highlights are found in: the title, the subtitles, and their language ("French"), which they are written in. */
    class Video(val title: String, val lines: List<SubtitleLine>, val language: String)

    /** About [count] highlights in [video], the best of them, in the order they come in the video. */
    suspend fun find(endpoint: Endpoint, model: String, video: Video, count: Int): AiResult<List<Highlight>> {
        val lines = video.lines
        val wanted = count.coerceIn(1, MOST)
        val parts = parts(lines)
        val found = mutableListOf<Pair<Double, Highlight>>()
        for ((from, to) in parts) {
            // Each part asked for its share, and a little more to choose from.
            val share = maxOf(1, ceilDiv(wanted * (to - from), maxOf(lines.size, 1))) + if (parts.size > 1) 1 else 0
            val ask = Ask(model = model, system = SYSTEM, user = question(video, from, to, share), maxTokens = MAX_TOKENS)
            // A reply that isn't the JSON asked for, once more.
            var reply: AiResult<JSONObject> = client.askJson(endpoint, ask) { it.optJSONArray("highlights") != null }
            if (reply == AiResult.Failed(AiError.NotJson)) {
                reply = client.askJson(endpoint, ask) { it.optJSONArray("highlights") != null }
            }
            when (val r = reply) {
                is AiResult.Done -> found += read(r.value, lines, from, to)
                is AiResult.Failed -> return r
            }
        }
        return AiResult.Done(best(found, wanted))
    }

    companion object {
        /** A part of the subtitles in one request, at most this many characters of them. */
        const val PART = 60_000

        /** How long the model's answer may run (Anthropic's protocol asks). */
        const val MAX_TOKENS = 4_096

        /** The most highlights asked for. */
        const val MOST = 20

        private const val SYSTEM = "You find the moments of a video worth watching on their own: a clear claim, a " +
            "striking story, a turn, a joke that lands, a useful answer. Each is a run of whole lines that makes sense " +
            "without the rest, usually twenty seconds to two minutes long. Give each a short title (a few words, no " +
            "quotes) and the reason it stands out (one short sentence). Answer with one JSON object and nothing else."

        private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

        /** The subtitles in parts of at most [PART] characters, as (first line, line after the last). */
        fun parts(lines: List<SubtitleLine>): List<Pair<Int, Int>> {
            val parts = mutableListOf<Pair<Int, Int>>()
            var from = 0
            var chars = 0
            lines.forEachIndexed { i, line ->
                if (chars > 0 && chars + line.text.length > PART) {
                    parts += from to i
                    from = i
                    chars = 0
                }
                chars += line.text.length + 16
            }
            if (from < lines.size) parts += from to lines.size
            return parts
        }

        private fun clock(ms: Long): String {
            val seconds = ms / 1000
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
        }

        /** The question for lines from..to: the title, the lines numbered (from 1, with their times), how many to find. */
        fun question(video: Video, from: Int, to: Int, count: Int): String {
            val numbered = (from until to).joinToString("\n") { n ->
                "${n + 1} [${clock(video.lines[n].start)}] ${video.lines[n].text.replace('\n', ' ')}"
            }
            return "Video title: ${video.title}\n\nIts subtitles, line by line (number [time] text):\n$numbered\n\nFind the " +
                "$count best highlights in these lines. Answer with {\"highlights\": [{\"from\": first line's number, \"to\": " +
                "last line's number, \"title\": \"…\", \"reason\": \"…\", \"score\": 1 to 10}]}, using only the numbers " +
                "${from + 1} to $to. Write the titles and reasons in ${video.language}."
        }

        private fun number(value: Any?): Int? {
            if (value is Number) return value.toLong().takeIf { value.toDouble() == it.toDouble() && it in 0..Int.MAX_VALUE }?.toInt()
            return (value as? String)?.trim()?.toIntOrNull()
        }

        /**
         * The highlights in a reply that can be used: inside lines from..to, with a title; a range given backwards is
         * turned round, and its times come from its lines. Each with its score (5 when not given).
         */
        fun read(reply: JSONObject, lines: List<SubtitleLine>, from: Int, to: Int): List<Pair<Double, Highlight>> {
            val items = reply.optJSONArray("highlights") ?: return emptyList()
            return (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val a = number(item.opt("from")) ?: return@mapNotNull null
                val b = number(item.opt("to")) ?: return@mapNotNull null
                val first = minOf(a, b) - 1
                val last = maxOf(a, b) - 1
                if (first < from || last >= to) return@mapNotNull null
                val title = (item.opt("title") as? String)?.trim()?.trim('"')?.take(80) ?: return@mapNotNull null
                if (title.isEmpty()) return@mapNotNull null
                val reason = (item.opt("reason") as? String).orEmpty().trim().take(240)
                val score = (item.opt("score") as? Number)?.toDouble() ?: 5.0
                score to Highlight(title, reason, first, last, lines[first].start, lines[last].end)
            }
        }

        /** The [count] best highlights by score, none overlapping another, in the order they come in the video. */
        fun best(found: List<Pair<Double, Highlight>>, count: Int): List<Highlight> {
            val kept = mutableListOf<Highlight>()
            for ((_, highlight) in found.sortedByDescending { it.first }) {
                val overlaps = kept.any { highlight.first <= it.last && it.first <= highlight.last }
                if (!overlaps && kept.size < count) kept += highlight
            }
            return kept.sortedBy { it.first }
        }
    }
}

/** A video's highlights as kept: found in which language's subtitles, by which model, and each. */
data class FoundHighlights(val from: String, val model: String, val made: Long, val items: List<Highlight>)

/** The highlights found for a video, kept beside its subtitles ("12.highlights.json") and deleted with them. */
class HighlightStore(private val dir: File) {
    private fun file(id: Long) = File(dir, "$id.highlights.json")

    fun load(id: Long): FoundHighlights? = runCatching {
        val json = JSONObject(file(id).takeIf { it.isFile }?.readText() ?: return null)
        val items = json.getJSONArray("items")
        FoundHighlights(
            from = json.optString("from"),
            model = json.optString("model"),
            made = json.optLong("made"),
            items = (0 until items.length()).map { i ->
                val h = items.getJSONObject(i)
                Highlight(h.getString("title"), h.optString("reason"), h.getInt("first"), h.getInt("last"), h.getLong("start"), h.getLong("end"))
            },
        )
    }.getOrNull()

    fun keep(id: Long, found: FoundHighlights) {
        val json = JSONObject().put("from", found.from).put("model", found.model).put("made", found.made).put(
            "items",
            JSONArray().apply {
                found.items.forEach {
                    put(
                        JSONObject().put("title", it.title).put("reason", it.reason).put("first", it.first)
                            .put("last", it.last).put("start", it.start).put("end", it.end),
                    )
                }
            },
        )
        runCatching {
            dir.mkdirs()
            val partial = File(dir, "${file(id).name}.part")
            partial.writeText(json.toString())
            if (!partial.renameTo(file(id))) partial.delete()
        }
    }
}
