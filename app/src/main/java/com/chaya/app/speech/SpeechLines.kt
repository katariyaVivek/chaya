package com.chaya.app.speech

import com.chaya.app.subtitles.SubtitleLine
import kotlin.math.abs

/**
 * Subtitle lines that follow the speech, from the words whisper.cpp heard and when each was said: its own segments run
 * to fifteen seconds and two hundred characters, more than anyone reads in one go. What was said is first cut where a
 * sentence ends and at clear pauses; whatever is still longer than five seconds or about forty characters is cut again
 * at its best gap between two words, and the halves again, until every line fits. A gap scores higher after a comma,
 * at a longer pause, before a word that starts a clause or a preposition, and when it leaves the halves alike; lower
 * after a word that leads into the next ("the", "of", "to"), between an adjective and its noun, and when it leaves a
 * half too short. A piece too small to read on its own joins a neighbour. Each line shows 0.2 s before its first word
 * and stays 0.3 s after its last, and at least a second where there is room. Languages written without spaces keep
 * whisper.cpp's segments.
 *
 * Windows' `speech::lines`, ported with its tests. Plain Kotlin.
 */
object SpeechLines {
    /** A line longer than this is cut, unless it is one word. */
    const val MOST_CHARS = 44

    /** Milliseconds. A line said over longer than this is cut, unless it is one word. */
    const val LONGEST = 5_000L

    /** A pause this long ends a line, unless the word before leads into the next: then only twice as long does. */
    private const val CLEAR_PAUSE = 700L

    /** A half shorter than this scores low: a line of two words flashes by. */
    private const val TOO_SHORT = 12

    /** A piece this short, or said this quickly, joins a neighbour when the two fit on one line. */
    private const val TINY_CHARS = 10
    private const val TINY_TIME = 600L

    /** How much earlier than its first word a line shows, and how much longer than its last it stays. */
    const val EARLY = 200L
    const val LATE = 300L

    /** A line stays on screen at least this long, where the next one leaves room. */
    private const val SHORTEST_SHOWN = 1_000L

    /** Alphanumeric as Rust's `char::is_alphanumeric` has it: letters (with their marks, as in Hindi) and numbers. */
    fun isAlnum(cp: Int): Boolean = Character.isAlphabetic(cp) || when (Character.getType(cp)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    private fun count(s: String) = s.codePointCount(0, s.length)

    /** The subtitle lines for what whisper.cpp heard. */
    fun lines(transcript: Transcript): List<SubtitleLine> {
        val spoken = transcript.segments.filter { it.text.isNotEmpty() && !Transcript.isMarker(it.text) }
        val words = Words.of(transcript.language)
        if (words.unspaced) return shown(spoken.filter { it.end > it.start }.map { Triple(it.start, it.end, it.text) })
        // whisper.cpp's segments end where its window of sound does as often as where a sentence does, so its words
        // are taken as one stream.
        val all = spoken.flatMap { it.words }.filter { it.text.isNotBlank() }
        val pieces = mutableListOf<Pair<Int, Int>>()
        var from = 0
        for (at in 1..all.size) {
            val cut = at == all.size ||
                words.endsSentence(all[at - 1].text) ||
                pause(all[at - 1], all[at]) >= CLEAR_PAUSE * if (words.leadsOn(bare(all[at - 1].text))) 2 else 1
            if (cut) {
                split(all, from, at, words, pieces)
                from = at
            }
        }
        val joined = joinTiny(all, pieces)
        return shown(joined.map { (a, b) -> Triple(all[a].start, all[b - 1].end, text(all.subList(a, b))) })
    }

    /** The silence between two words, in milliseconds. */
    private fun pause(before: Word, after: Word) = maxOf(0, after.start - before.end)

    private fun text(words: List<Word>) = words.joinToString(" ") { it.text.trim() }

    private fun chars(words: List<Word>) = words.sumOf { count(it.text.trim()) } + maxOf(0, words.size - 1)

    private fun fits(words: List<Word>): Boolean {
        val time = maxOf(0, (words.lastOrNull()?.end ?: 0) - (words.firstOrNull()?.start ?: 0))
        return words.size <= 1 || (chars(words) <= MOST_CHARS && time <= LONGEST)
    }

    /** Cuts words from..to into pieces that fit, at the best gap and then each half's, onto [pieces]. */
    private fun split(all: List<Word>, from: Int, to: Int, words: Words, pieces: MutableList<Pair<Int, Int>>) {
        val line = all.subList(from, to)
        if (fits(line)) {
            pieces += from to to
            return
        }
        var best = from + 1
        var bestScore = -Float.MAX_VALUE
        for (at in from + 1 until to) {
            val s = score(line, at - from, words)
            if (s > bestScore) {
                best = at
                bestScore = s
            }
        }
        split(all, from, best, words, pieces)
        split(all, best, to, words, pieces)
    }

    /** How good a place the gap before line[at] is to end a line. */
    fun score(line: List<Word>, at: Int, words: Words): Float {
        val before = line[at - 1]
        val after = line[at]
        val trimmed = before.text.trimEnd()
        val last = if (trimmed.isEmpty()) ' '.code else trimmed.codePointBefore(trimmed.length)
        val marked = !isAlnum(last)
        var score = 0f
        when {
            words.endsSentence(before.text) -> score += 5f
            last in listOf(';', ':', '—', '–').map { it.code } -> score += 3.5f
            last in listOf(',', '،', '、', '，').map { it.code } -> score += 3f
        }
        // A point for each quarter second of silence, up to four.
        score += minOf(pause(before, after) / 250f, 4f)
        val prev = bare(before.text)
        val next = bare(after.text)
        if (words.startsClause(next)) score += 2f else if (next in words.prepositions) score += 1f
        if (!marked && words.leadsOn(prev)) score -= 4f
        if (words.followsOn(next)) score -= 4f
        if (!marked && words.adjectiveBeforeNoun(prev, next)) score -= 2f
        val a = chars(line.subList(0, at))
        val b = chars(line.subList(at, line.size))
        score += 2f * (1f - abs(a - b).toFloat() / maxOf(a + b, 1))
        if (minOf(a, b) < TOO_SHORT) score -= 4f
        return score
    }

    /** A word as the lists hold it: lower case, without the punctuation around it. */
    fun bare(word: String): String {
        fun keep(cp: Int) = isAlnum(cp) || cp == '\''.code
        var start = 0
        var end = word.length
        while (start < end && !keep(word.codePointAt(start))) start += Character.charCount(word.codePointAt(start))
        while (end > start && !keep(word.codePointBefore(end))) end -= Character.charCount(word.codePointBefore(end))
        return word.substring(start, end).lowercase()
    }

    /** Joins each piece too small to read on its own to the neighbour it is closer to in time, when the two fit. */
    private fun joinTiny(all: List<Word>, start: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val pieces = start.toMutableList()
        fun tiny(p: Pair<Int, Int>): Boolean {
            val w = all.subList(p.first, p.second)
            return chars(w) < TINY_CHARS || maxOf(0, w.last().end - w.first().start) < TINY_TIME
        }
        var i = 0
        while (i < pieces.size) {
            if (!tiny(pieces[i])) {
                i++
                continue
            }
            val (from, to) = pieces[i]
            fun joined(a: Int, b: Int) = fits(all.subList(a, b))
            val before = if (i > 0 && joined(pieces[i - 1].first, to)) pause(all[from - 1], all[from]) else null
            val after = if (i + 1 < pieces.size && joined(from, pieces[i + 1].second)) pause(all[to - 1], all[to]) else null
            when {
                before != null && (after == null || before <= after) -> {
                    pieces[i - 1] = pieces[i - 1].first to to
                    pieces.removeAt(i)
                    i--
                }
                after != null -> {
                    pieces[i + 1] = from to pieces[i + 1].second
                    pieces.removeAt(i)
                }
                else -> i++
            }
        }
        return pieces
    }

    /** The lines as shown: a little before the first word to a little after the last, never over the next. */
    private fun shown(said: List<Triple<Long, Long, String>>): List<SubtitleLine> {
        val lines = said.map { (start, end, text) ->
            val from = maxOf(0, start - EARLY)
            SubtitleLine(from, maxOf(end + LATE, from + SHORTEST_SHOWN), text)
        }.toMutableList()
        for (i in 1 until lines.size) {
            if (lines[i - 1].end > lines[i].start) {
                // The next line comes in early, unless the one before is still being said.
                val cut = maxOf(lines[i].start, said[i - 1].second)
                lines[i - 1] = lines[i - 1].copy(end = cut)
                lines[i] = lines[i].copy(start = cut)
            }
            lines[i] = lines[i].copy(end = maxOf(lines[i].end, lines[i].start + 1))
        }
        return lines
    }

    /** The words a language's lines are cut by. */
    class Words(
        /** Written without spaces between words: no word lists. */
        val unspaced: Boolean = false,
        private val clause: Set<String> = emptySet(),
        private val leading: Set<String> = emptySet(),
        val prepositions: Set<String> = emptySet(),
        private val following: Set<String> = emptySet(),
        private val abbreviations: Set<String> = emptySet(),
        private val adjectives: Set<String> = emptySet(),
    ) {
        fun endsSentence(word: String): Boolean {
            val w = word.trimEnd('"', '\'', '”', '’', ')', ']', '»')
            if (w.isEmpty()) return false
            return when (w.codePointBefore(w.length)) {
                '?'.code, '!'.code, '…'.code, '।'.code, '॥'.code, '؟'.code, '。'.code, '？'.code, '！'.code -> true
                '.'.code -> {
                    val stripped = w.trimEnd('.')
                    var s = 0
                    while (s < stripped.length && !isAlnum(stripped.codePointAt(s))) s += Character.charCount(stripped.codePointAt(s))
                    val bare = stripped.substring(s)
                    // "Mr." and "U.S." are not a sentence's end, nor is a single letter ("John F. Kennedy").
                    !(bare.lowercase() in abbreviations || count(bare) == 1 || '.' in bare)
                }
                else -> false
            }
        }

        fun startsClause(word: String) = word in clause
        fun leadsOn(word: String) = word in leading
        fun followsOn(word: String) = word in following

        /** [prev] is an adjective (or a number) and [next] could be its noun. */
        fun adjectiveBeforeNoun(prev: String, next: String): Boolean {
            if (adjectives.isEmpty()) return false
            val adjective = prev in adjectives || prev.all { it in '0'..'9' } ||
                (prev.toByteArray().size > 5 && listOf("ful", "ous", "ive", "able", "ible", "less", "ical", "ish").any { prev.endsWith(it) })
            val noun = !(next.isEmpty() || next in leading || next in clause || next in adjectives)
            return adjective && noun
        }

        companion object {
            private fun set(words: String) = words.trim().split(Regex("\\s+")).toSet()

            /** The lists for [language] ("en"), or none for a language Chaya has none for. */
            fun of(language: String): Words = when (language) {
                "zh", "ja", "th", "lo", "my", "km", "yue", "bo" -> Words(unspaced = true)
                "en" -> Words(
                    clause = set("and but because or so which when while if although though since unless until where whereas who whose yet then before after"),
                    leading = set(
                        "the a an of to in on at for with by from into onto about my your his her its our their this these those that's " +
                            "i i'm i've i'll i'd we're you're they're it's is are was were be been very really some any every each no not and or " +
                            "but can could will would should must might may do does did don't didn't doesn't can't won't have has had as than more most " +
                            "less such how what which who whose",
                    ),
                    // Not "to", which leads into a verb as often ("has come | to seem").
                    prepositions = set("of in on at for with by from into onto about over under through across against among beyond without within during like"),
                    following = set("'s n't"),
                    abbreviations = set("mr mrs ms dr prof st sr jr vs etc e.g i.e u.s u.k no vol approx inc ltd co mt ft"),
                    adjectives = set(
                        "new old good great big small little long short high low young important few last first next other own same right wrong " +
                            "different large best better worse worst bad real whole free full easy hard early late major main only true simple open " +
                            "close nice beautiful huge tiny entire certain special strong clear black white red blue green dark light deep hot cold " +
                            "fast slow rich poor single local national public private human social political american several many much two three " +
                            "four five six seven eight nine ten hundred thousand million billion",
                    ),
                )
                "es" -> Words(
                    clause = set("y pero porque o aunque cuando mientras si donde pues sino"),
                    leading = set("el la los las un una unos unas de del a al en con por para sin sobre mi tu su mis tus sus nuestro nuestra este esta ese esa que no muy más y o lo se me te le"),
                    prepositions = set("de a en con por para sin sobre desde hasta entre"),
                    abbreviations = set("sr sra dr dra etc ud uds"),
                )
                "pt" -> Words(
                    clause = set("e mas porque ou embora quando enquanto se onde pois"),
                    leading = set("o a os as um uma de do da dos das em no na nos nas com por para pelo pela sem meu minha seu sua este esta esse essa que não muito mais e ou se me te lhe"),
                    prepositions = set("de em com por para sem sobre desde até entre"),
                    abbreviations = set("sr sra dr dra etc"),
                )
                "fr" -> Words(
                    clause = set("et mais parce ou donc car quand lorsque si puisque alors"),
                    leading = set("le la les l' un une des de du d' à au aux en dans sur avec pour par sans mon ma mes ton ta tes son sa ses notre votre leur ce cette ces que qu' ne très plus et ou je tu il elle on nous vous ils elles se me te"),
                    prepositions = set("de à en dans sur avec pour par sans chez vers entre"),
                    abbreviations = set("m mme dr etc"),
                )
                "it" -> Words(
                    clause = set("e ma perché o anche quando mentre se dove però quindi"),
                    leading = set("il lo la i gli le un uno una di del della a al alla da in nel nella con su per tra fra mio mia suo sua questo questa quel quella che non molto più e o si"),
                    prepositions = set("di a da in con su per tra fra senza verso"),
                    abbreviations = set("sig dott ecc"),
                )
                "de" -> Words(
                    clause = set("und aber weil oder denn dass wenn als obwohl während sondern"),
                    leading = set("der die das den dem des ein eine einen einem einer eines von zu zum zur in im an am auf mit für bei aus nach über unter mein meine dein sein seine ihr ihre unser dieser diese dieses nicht sehr und oder ich du er sie es wir"),
                    prepositions = set("von zu in an auf mit für bei aus nach über unter ohne"),
                    abbreviations = set("hr fr dr z.b usw bzw ca nr"),
                )
                "hi" -> Words(
                    clause = set("और लेकिन क्योंकि या तो जब अगर जो कि मगर इसलिए"),
                    leading = set("यह वह ये वो एक मेरा मेरी मेरे आपका आपकी उसका उसकी बहुत"),
                    // Postpositions and the verb's helpers come after the word they go with.
                    following = set("का की के में से को ने पर तक है हैं था थी थे हूँ हो गया गई रहा रही रहे वाला वाली वाले भी ही"),
                )
                else -> Words()
            }
        }
    }
}
