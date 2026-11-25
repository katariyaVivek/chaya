package com.chaya.app.subtitles

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

/** A clip: its number (unique in its video, never given again), its name, and where it starts and ends (ms). */
data class Clip(val id: Int, val name: String, val start: Long, val end: Long) {
    val length: Long get() = end - start
}

/** A clip asked for: its name (empty for "Clip 3") and times. */
data class NewClip(val name: String, val start: Long, val end: Long)

/**
 * A saved video's clips, as Chaya for Windows keeps them (`core::clips`, ported with its tests): put right before
 * they are kept, whatever the screen sent: the right way round, long enough, inside the video, named, and the same clip
 * never kept twice. Clips may overlap: two cuts of one moment are both kept. Plain Kotlin.
 */
object Clips {
    /** The shortest clip, in milliseconds. */
    const val SHORTEST = 500L

    /** The most clips a video keeps. */
    const val MOST = 500

    /** The longest name, in characters. */
    const val LONGEST_NAME = 80

    /** An edge this close to a line's edge goes to it, in milliseconds (the timeline's snap, from Windows' clips.js). */
    const val SNAP = 1_500L

    /**
     * start..end put right: turned round when backwards, inside a video [duration] long when that is known, and at
     * least [SHORTEST] long (longer at the end when it can be, else earlier); null when the video is shorter than that.
     */
    fun range(start: Long, end: Long, duration: Long?): Pair<Long, Long>? {
        var from = maxOf(0, minOf(start, end))
        var to = maxOf(start, end)
        if (duration != null) {
            if (duration < SHORTEST) return null
            from = minOf(from, duration - SHORTEST)
            to = minOf(to, duration)
        }
        if (to < from + SHORTEST) to = from + SHORTEST
        return from to to
    }

    fun tidyName(name: String): String = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(LONGEST_NAME)

    private fun ordered(clips: List<Clip>) = clips.sortedWith(compareBy({ it.start }, { it.end }, { it.id }))

    /**
     * [clips] with [wanted] added, each put right and named ("Clip 4" when it has no name); one with the same times as
     * a clip there already is that clip. With the ids asked for, in order, null for one that can't be made.
     */
    fun add(clips: List<Clip>, wanted: List<NewClip>, duration: Long?): Pair<List<Clip>, List<Int?>> {
        val kept = clips.toMutableList()
        var next = (clips.maxOfOrNull { it.id } ?: 0) + 1
        val ids = wanted.map { new ->
            val (start, end) = range(new.start, new.end, duration) ?: return@map null
            kept.firstOrNull { it.start == start && it.end == end }?.let { return@map it.id }
            if (kept.size >= MOST) return@map null
            val name = tidyName(new.name).ifEmpty { "Clip $next" }
            kept += Clip(next, name, start, end)
            next++
            next - 1
        }
        return ordered(kept) to ids
    }

    /** Clip [changed]`.id` given its name and times, put right (an empty name keeps the one it had); null when none. */
    fun change(clips: List<Clip>, changed: Clip, duration: Long?): List<Clip>? {
        val (start, end) = range(changed.start, changed.end, duration) ?: return null
        val old = clips.firstOrNull { it.id == changed.id } ?: return null
        val name = tidyName(changed.name).ifEmpty { old.name }
        return ordered(clips.map { if (it.id == changed.id) Clip(it.id, name, start, end) else it })
    }

    /** [clips] without clip [id]; null when there is none. */
    fun remove(clips: List<Clip>, id: Int): List<Clip>? = clips.filter { it.id != id }.takeIf { it.size < clips.size }

    /** Lines a and b and those between, either way round, as (first, last); null without lines. */
    fun chosen(lineCount: Int, a: Int, b: Int): IntRange? {
        if (lineCount == 0) return null
        return maxOf(0, minOf(a, b))..minOf(lineCount - 1, maxOf(a, b))
    }

    /** A clip's name from the first line it starts with: its first words, about 40 characters at most, no stop at the end. */
    fun nameFrom(text: String): String {
        val words = text.replace(Regex("\\s+"), " ").trim().trimEnd('.', ',', ';', ':', '!', '?', '…').split(' ').filter { it.isNotEmpty() }
        var name = ""
        for (word in words) {
            val longer = if (name.isEmpty()) word else "$name $word"
            if (longer.length > 40) break
            name = longer
        }
        return name.ifEmpty { words.firstOrNull()?.take(40).orEmpty() }
    }

    /** What an edge snaps to: each line's start or end ([start] says which), and the chapters' starts. */
    fun edges(lines: List<SubtitleLine>, chapters: List<Chapter>, start: Boolean): List<Long> =
        lines.map { if (start) it.start else it.end } + chapters.map { it.start }

    /** Where an edge let go at [ms] goes: the nearest of [edges] within [SNAP], else the nearest tenth of a second. */
    fun snap(edges: List<Long>, ms: Long): Long {
        val rounded = maxOf(0, (ms / 100.0).roundToLong() * 100)
        return edges.filter { abs(it - ms) <= SNAP }.minByOrNull { abs(it - ms) } ?: rounded
    }

    /** [clip] with its start (or end) moved to [ms], snapped, inside the video, at least [SHORTEST] from its other edge. */
    fun moveEdge(clip: Clip, start: Boolean, ms: Long, edges: List<Long>, duration: Long?): Clip {
        val at = snap(edges, ms)
        return if (start) clip.copy(start = maxOf(0, minOf(at, clip.end - SHORTEST)))
        else clip.copy(end = minOf(duration?.takeIf { it > 0 } ?: Long.MAX_VALUE, maxOf(at, clip.start + SHORTEST)))
    }

    /** The part of the video the timeline shows for [clip]: the clip, with room either side to drag its edges into. */
    fun window(clip: Clip, duration: Long): LongRange {
        val room = maxOf(5_000L, clip.length / 3)
        val from = maxOf(0, clip.start - room)
        val to = if (duration > 0) minOf(duration, clip.end + room) else clip.end + room
        return from..maxOf(to, from + 1)
    }
}

/** A video's clips kept beside its subtitles ("12.clips.json"), deleted with the download. */
class ClipStore(private val dir: File) {
    private fun file(id: Long) = File(dir, "$id.clips.json")

    @Synchronized
    fun load(id: Long): List<Clip> = runCatching {
        val list = JSONObject(file(id).takeIf { it.isFile }?.readText() ?: return emptyList()).getJSONArray("clips")
        (0 until list.length()).map { i ->
            val c = list.getJSONObject(i)
            Clip(c.getInt("id"), c.getString("name"), c.getLong("start"), c.getLong("end"))
        }
    }.getOrDefault(emptyList())

    /** Keeps [clips] for download [id], in place of those before. */
    @Synchronized
    fun keep(id: Long, clips: List<Clip>): List<Clip> {
        val json = JSONObject().put(
            "clips",
            JSONArray().apply {
                clips.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("start", it.start).put("end", it.end)) }
            },
        )
        runCatching {
            dir.mkdirs()
            val partial = File(dir, "${file(id).name}.part")
            partial.writeText(json.toString())
            if (!partial.renameTo(file(id))) partial.delete()
        }
        return clips
    }
}

/**
 * How loud a video's sound is, [perSecond] times a second, each the loudest moment of its part as a byte (0 silent,
 * 255 as loud as can be), as Windows' `sound::peaks` measures it; fed 16-bit samples at any rate, channels together.
 */
class PeakMeter(sampleRate: Int, private val channels: Int, perSecond: Int = PER_SECOND) {
    private val perPeak = maxOf(1, sampleRate / perSecond)
    private val peaks = java.io.ByteArrayOutputStream()
    private var loudest = 0
    private var samples = 0
    private var channel = 0

    /** One 16-bit sample of one channel; a frame of every channel counts once. */
    fun add(sample: Short) {
        loudest = maxOf(loudest, abs(sample.toInt()))
        channel++
        if (channel < channels) return
        channel = 0
        samples++
        if (samples == perPeak) {
            peaks.write(byte(loudest))
            loudest = 0
            samples = 0
        }
    }

    /** The peaks, the part at the end included. */
    fun peaks(): ByteArray {
        if (samples > 0) {
            peaks.write(byte(loudest))
            loudest = 0
            samples = 0
        }
        return peaks.toByteArray()
    }

    companion object {
        /** Peaks a second: a tenth of a second is five of them, as fine as the timeline ever shows sound. */
        const val PER_SECOND = 50

        /** A sample's size as a byte: 0 to 255. */
        fun byte(loudest: Int): Int = ((minOf(loudest, 32_768) * 255 + 16_384) / 32_768)
    }
}
