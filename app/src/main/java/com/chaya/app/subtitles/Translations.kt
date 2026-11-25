package com.chaya.app.subtitles

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One version of a video's translation: of its subtitles in [from], into [to] ("es"), by [model] ("Groq ·
 * llama-3.3-70b"), made at [made] (ms), with a line for each of the original's (null for one not translated yet).
 */
data class Translation(
    val from: String,
    val to: String,
    val model: String,
    val made: Long,
    val lines: List<String?>,
) {
    val whole: Boolean get() = lines.all { it != null }
    val done: Int get() = lines.count { it != null }

    /** The same language and model: a translation carried on, rather than another version. */
    fun sameAs(other: Translation) = from == other.from && to == other.to && model == other.model
}

/** A video's translations, and which is shown under the original (none: the original alone). */
data class VideoTranslations(val versions: List<Translation> = emptyList(), val shown: Int? = null) {
    /** The version shown, when there is one and it fits [lineCount] (the original's lines, which a fix may change). */
    fun shown(lineCount: Int): Translation? = shown?.let { versions.getOrNull(it) }?.takeIf { it.lines.size == lineCount }
}

/**
 * Translations kept beside the subtitles ("12.translations.json"), a version for each language and model, so trying
 * another model never loses the one before; deleted with the download, as the subtitles are ([SubtitleStore.forget]).
 * Each is written whole to a file beside it first, so a crash never leaves half.
 */
class TranslationStore(private val dir: File) {
    private fun file(id: Long) = File(dir, "$id.translations.json")

    @Synchronized
    fun load(id: Long): VideoTranslations {
        val text = runCatching { file(id).takeIf { it.isFile }?.readText() }.getOrNull() ?: return VideoTranslations()
        return runCatching { fromJson(JSONObject(text)) }.getOrDefault(VideoTranslations())
    }

    /** Keeps [translation] in place of the version of the same language and model, or as a new one; shows it. */
    @Synchronized
    fun keep(id: Long, translation: Translation): VideoTranslations {
        val now = load(id)
        val at = now.versions.indexOfFirst { it.sameAs(translation) }
        val versions = if (at >= 0) now.versions.toMutableList().also { it[at] = translation } else now.versions + translation
        return write(id, VideoTranslations(versions, shown = if (at >= 0) at else versions.lastIndex))
    }

    /**
     * Deletes version [index]; the one shown stays shown (or none is, if it was the one deleted). Nothing is kept
     * when no version is left.
     */
    @Synchronized
    fun remove(id: Long, index: Int): VideoTranslations {
        val now = load(id)
        if (index !in now.versions.indices) return now
        val versions = now.versions.filterIndexed { i, _ -> i != index }
        val shown = now.shown?.let { s -> when { s == index -> null; s > index -> s - 1; else -> s } }
        if (versions.isEmpty()) {
            file(id).delete()
            return VideoTranslations()
        }
        return write(id, VideoTranslations(versions, shown))
    }

    /** Shows version [index] under the original, or none. */
    @Synchronized
    fun show(id: Long, index: Int?): VideoTranslations {
        val now = load(id)
        return write(id, now.copy(shown = index?.takeIf { it in now.versions.indices }))
    }

    /** Line [line] of version [index] put right by the person, as the original's lines are. */
    @Synchronized
    fun setLine(id: Long, index: Int, line: Int, text: String): VideoTranslations {
        val now = load(id)
        val version = now.versions.getOrNull(index) ?: return now
        if (line !in version.lines.indices) return now
        val lines = version.lines.toMutableList().also { it[line] = text.trim() }
        return write(id, now.copy(versions = now.versions.toMutableList().also { it[index] = version.copy(lines = lines) }))
    }

    /**
     * Line [line] of the original taken out of its [lineCount]: taken out of every version made for those lines too,
     * so they stay beside the lines they translate.
     */
    @Synchronized
    fun takeOut(id: Long, line: Int, lineCount: Int): VideoTranslations {
        val now = load(id)
        if (now.versions.none { it.lines.size == lineCount && line in it.lines.indices }) return now
        val versions = now.versions.map { v ->
            if (v.lines.size == lineCount && line in v.lines.indices) v.copy(lines = v.lines.filterIndexed { i, _ -> i != line }) else v
        }
        return write(id, now.copy(versions = versions))
    }

    /** The version of [to] by [model] from [from], to carry on with; null when there is none. */
    fun find(id: Long, from: String, to: String, model: String): Translation? =
        load(id).versions.firstOrNull { it.from == from && it.to == to && it.model == model }

    private fun write(id: Long, translations: VideoTranslations): VideoTranslations {
        runCatching {
            dir.mkdirs()
            val partial = File(dir, "${file(id).name}.part")
            partial.writeText(toJson(translations).toString())
            if (!partial.renameTo(file(id))) partial.delete()
        }
        return translations
    }

    companion object {
        fun toJson(translations: VideoTranslations): JSONObject = JSONObject().apply {
            translations.shown?.let { put("shown", it) }
            put("versions", JSONArray().apply {
                translations.versions.forEach { t ->
                    put(
                        JSONObject()
                            .put("from", t.from).put("to", t.to).put("model", t.model).put("made", t.made)
                            .put("lines", JSONArray().apply { t.lines.forEach { put(it ?: JSONObject.NULL) } }),
                    )
                }
            })
        }

        fun fromJson(json: JSONObject): VideoTranslations {
            val list = json.optJSONArray("versions") ?: JSONArray()
            val versions = (0 until list.length()).mapNotNull { i ->
                val v = list.optJSONObject(i) ?: return@mapNotNull null
                val lines = v.optJSONArray("lines") ?: return@mapNotNull null
                Translation(
                    from = v.optString("from"),
                    to = v.optString("to").ifEmpty { return@mapNotNull null },
                    model = v.optString("model"),
                    made = v.optLong("made"),
                    lines = (0 until lines.length()).map { n -> if (lines.isNull(n)) null else lines.optString(n) },
                )
            }
            val shown = if (json.has("shown")) json.optInt("shown").takeIf { it in versions.indices } else null
            return VideoTranslations(versions, shown)
        }
    }
}
