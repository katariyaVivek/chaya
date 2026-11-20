package com.chaya.app.speech

import org.json.JSONException
import org.json.JSONObject

/** One word whisper.cpp heard, and when (milliseconds); [p] is how sure it was, from 0 to 1. */
data class Word(val text: String, val start: Long, val end: Long, val p: Float)

/** One stretch of speech as whisper.cpp gave it, its words with their times. */
data class Segment(val start: Long, val end: Long, val text: String, val words: List<Word> = emptyList())

/** What whisper.cpp heard in a video: the language (detected, or the one it was told), and what was said. */
data class Transcript(val language: String, val segments: List<Segment>) {
    /** The subtitle lines: short ones that follow the speech ([SpeechLines]), leaving out the silences it marks. */
    fun lines() = SpeechLines.lines(this)

    companion object {
        /** whisper.cpp's word for a stretch with nothing said in it. */
        fun isMarker(text: String) = text == "[BLANK_AUDIO]" || text == "[ Silence ]" || text == "[SILENCE]"

        /** A word whose length is known only from its start lasts no longer than this many times the usual word's. */
        private const val LONGEST_WORD = 2

        /**
         * What whisper.cpp heard, from the JSON its full output writes (whisper-cli's `-ojf`, which Chaya's bridge
         * writes too): each segment, its tokens put back together into words. A word starts at its DTW time when
         * every word has one, and ends at its last piece's, or twice a usual word's length after it starts if that is
         * later, never past the next word's start. Without DTW times a word has whisper.cpp's own rougher times.
         * Windows' `speech::whisper::parse`, ported with its tests. Null when it is not that JSON.
         */
        fun parse(json: String): Transcript? {
            val cleaned = json.map { if (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') ' ' else it }.joinToString("")
                // A "p" or "t_dtw" whisper.cpp could not compute is written as nan or inf, which JSON has no word for.
                .replace(": -nan", ": null").replace(": nan", ": null").replace(": -inf", ": null").replace(": inf", ": null")
            val root = try {
                JSONObject(cleaned)
            } catch (_: JSONException) {
                return null
            }
            val language = root.optJSONObject("result")?.optString("language").orEmpty()
            val list = root.optJSONArray("transcription") ?: return null
            var timed = true
            val segments = (0 until list.length()).mapNotNull { i ->
                val s = list.optJSONObject(i) ?: return@mapNotNull null
                val words = mutableListOf<Word>()
                val tokens = s.optJSONArray("tokens")
                for (t in 0 until (tokens?.length() ?: 0)) {
                    val token = tokens!!.optJSONObject(t) ?: continue
                    val text = token.optString("text")
                    if (text.startsWith("[_") || text.isEmpty()) continue
                    val offsets = token.optJSONObject("offsets") ?: continue
                    if (!offsets.has("from") || !offsets.has("to")) continue
                    val from = offsets.optLong("from")
                    val to = offsets.optLong("to")
                    val dtw = if (token.isNull("t_dtw") || !token.has("t_dtw")) null else token.optLong("t_dtw", -1).takeIf { it >= 0 }?.times(10)
                    timed = timed && dtw != null
                    val start = dtw ?: from
                    val end = dtw ?: to
                    val p = if (token.isNull("p")) 0f else token.optDouble("p", 0.0).toFloat().takeIf { !it.isNaN() } ?: 0f
                    val last = words.lastOrNull()
                    if (last != null && !text.startsWith(' ')) {
                        // A piece of the word before: no space in front of it.
                        words[words.lastIndex] = last.copy(text = last.text + text, end = maxOf(last.end, end), p = minOf(last.p, p))
                    } else {
                        words += Word(text.trimStart(), start, end, p)
                    }
                }
                val offsets = s.optJSONObject("offsets") ?: return@mapNotNull null
                if (!offsets.has("from") || !offsets.has("to")) return@mapNotNull null
                Segment(offsets.optLong("from"), offsets.optLong("to"), s.optString("text").trim(), words.filter { it.text.isNotBlank() })
            }
            val ended = if (timed) endWords(segments) else segments
            return Transcript(language, dropRepeats(ended))
        }

        /** Leaves out a segment that says just what the one before said: whisper.cpp, stuck, says one line over and over. */
        private fun dropRepeats(segments: List<Segment>): List<Segment> {
            fun said(s: Segment) = s.text.lowercase().filter { SpeechLines.isAlnum(it.code) }
            var last = ""
            return segments.filter { segment ->
                val text = said(segment)
                val repeat = text.isNotEmpty() && text == last
                last = text
                !repeat
            }
        }

        /** Ends each word timed by DTW at its last piece's time, or twice a usual word after its start if later. */
        private fun endWords(segments: List<Segment>): List<Segment> {
            val starts = segments.flatMap { s -> s.words.map { it.start } }
            val lengths = starts.zipWithNext { a, b -> maxOf(0, b - a) }.sorted()
            // The usual word: the median gap from one word's start to the next, between a fifth and a half of a second.
            val usual = (lengths.getOrNull(lengths.size / 2) ?: 400L).coerceIn(200, 500)
            var next = 1
            return segments.map { segment ->
                val count = segment.words.size
                segment.copy(
                    words = segment.words.mapIndexed { i, word ->
                        var until = starts.getOrNull(next++) ?: Long.MAX_VALUE
                        if (i + 1 == count && segment.end > word.start) until = minOf(until, segment.end)
                        word.copy(end = maxOf(minOf(until, maxOf(word.end, word.start + LONGEST_WORD * usual)), word.start))
                    },
                )
            }
        }
    }
}
