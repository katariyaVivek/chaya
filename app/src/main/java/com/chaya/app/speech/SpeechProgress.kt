package com.chaya.app.speech

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * How far making a video's subtitles got: the engine, the five-minute pieces heard of all, the language heard first and
 * what was said so far. Kept after every piece, so making that was stopped, or ended with the app (the phone closing it,
 * or a crash), carries on from there instead of starting again.
 */
data class Partial(
    val engine: Engine,
    val piecesDone: Int,
    val pieces: Int,
    val language: String?,
    val segments: List<Segment>,
) {
    /** How much of the video was heard, in percent. */
    val percent: Int get() = if (pieces <= 0) 0 else (piecesDone * 100 / pieces).coerceIn(0, 99)
}

/** [Partial]s, one file per video (`<id>.partial.json`) beside its subtitles, so deleting the video deletes it too. */
class SpeechProgress(private val dir: File) {
    private fun file(id: Long) = File(dir, "$id.partial.json")

    fun load(id: Long): Partial? = runCatching {
        val f = file(id).takeIf { it.isFile } ?: return null
        decode(f.readText())
    }.getOrNull()

    /** Kept whole to a file beside it first, so a crash never leaves half. */
    fun keep(id: Long, partial: Partial): Boolean = runCatching {
        dir.mkdirs()
        val part = File(dir, "$id.partial.json.part")
        part.writeText(encode(partial))
        part.renameTo(file(id)).also { if (!it) part.delete() }
    }.getOrDefault(false)

    fun clear(id: Long) {
        file(id).delete()
    }

    companion object {
        fun encode(p: Partial): String = JSONObject()
            .put("engine", p.engine.key)
            .put("done", p.piecesDone)
            .put("pieces", p.pieces)
            .put("language", p.language ?: "")
            .put(
                "segments",
                JSONArray(
                    p.segments.map { s ->
                        JSONObject().put("s", s.start).put("e", s.end).put("t", s.text).put(
                            "w",
                            JSONArray(s.words.map { w -> JSONArray().put(w.text).put(w.start).put(w.end).put(w.p.toDouble()) }),
                        )
                    },
                ),
            ).toString()

        fun decode(text: String): Partial? = try {
            val o = JSONObject(text)
            val engine = Engine.of(o.getString("engine")) ?: return null
            val list = o.getJSONArray("segments")
            val segments = (0 until list.length()).map { i ->
                val s = list.getJSONObject(i)
                val w = s.optJSONArray("w") ?: JSONArray()
                Segment(
                    s.getLong("s"),
                    s.getLong("e"),
                    s.getString("t"),
                    (0 until w.length()).map { j ->
                        val a = w.getJSONArray(j)
                        Word(a.getString(0), a.getLong(1), a.getLong(2), a.getDouble(3).toFloat())
                    },
                )
            }
            Partial(engine, o.getInt("done"), o.getInt("pieces"), o.optString("language").takeIf { it.isNotEmpty() }, segments)
        } catch (_: JSONException) {
            null
        }
    }
}
