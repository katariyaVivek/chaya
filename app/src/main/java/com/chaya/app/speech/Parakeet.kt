package com.chaya.app.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import kotlin.math.roundToLong

/**
 * NVIDIA's Parakeet TDT 0.6B v3 through sherpa-onnx: English, Russian, Ukrainian and 22 more European languages, with
 * punctuation and capitals, heard far better than by Whisper Base and faster. It hears up to half a minute at a time
 * well (more costs memory a phone does not have), so a piece is cut into windows of about 25 s at their quietest
 * moment ([windows]); each window's tokens, which come with their times, become words ([words]), which [SpeechLines]
 * cut into lines as it does whisper.cpp's. Parakeet does not say which language it heard, so it is guessed from the
 * words ([LanguageGuess]).
 */
object Parakeet {
    /** Windows are about this long (samples at 16 kHz), cut at the quietest 0.2 s between [SHORTEST] and [LONGEST]. */
    const val SHORTEST = 20 * Sound16k.RATE
    const val LONGEST = 30 * Sound16k.RATE
    private const val QUIET_STEP = Sound16k.RATE / 5

    private var loaded: Pair<File, OfflineRecognizer>? = null

    /** Whether sherpa-onnx is in this build for this phone. */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("sherpa-onnx-jni") }.isSuccess
    }

    /**
     * What was said in [samples] (16 kHz mono), heard with the model in [folder] on [threads] threads; [heard] hears
     * how far, in percent; [stopped] is asked between windows, and ends it with null.
     * Null when the model would not load.
     */
    @Synchronized
    fun transcribe(folder: File, samples: FloatArray, threads: Int, heard: (Int) -> Unit, stopped: () -> Boolean = { false }): Transcript? {
        if (!available) return null
        val recognizer = recognizer(folder, threads) ?: return null
        val cuts = windows(samples)
        val segments = mutableListOf<Segment>()
        cuts.forEachIndexed { i, range ->
            if (stopped()) return null
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples.copyOfRange(range.first, range.last + 1), Sound16k.RATE)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                val offset = range.first * 1000L / Sound16k.RATE
                val end = (range.last + 1) * 1000L / Sound16k.RATE
                val words = words(result.tokens.toList(), result.timestamps.toList(), offset, end)
                if (words.isNotEmpty()) segments += Segment(words.first().start, words.last().end, result.text.trim(), words)
            } finally {
                stream.release()
            }
            heard(((i + 1) * 100) / cuts.size)
        }
        val language = LanguageGuess.of(segments.joinToString(" ") { it.text })
        return Transcript(language, segments)
    }

    /** The recognizer for [folder], made once and kept while it is used (loading 670 MB takes a few seconds). */
    private fun recognizer(folder: File, threads: Int): OfflineRecognizer? {
        loaded?.takeIf { it.first == folder }?.let { return it.second }
        release()
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = Sound16k.RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(folder, "encoder.int8.onnx").path,
                    decoder = File(folder, "decoder.int8.onnx").path,
                    joiner = File(folder, "joiner.int8.onnx").path,
                ),
                tokens = File(folder, "tokens.txt").path,
                modelType = "nemo_transducer",
                numThreads = threads,
            ),
            decodingMethod = "greedy_search",
        )
        val made = runCatching { OfflineRecognizer(config = config) }.getOrNull() ?: return null
        loaded = folder to made
        return made
    }

    /** Lets the model go, giving its memory back, when making subtitles is done. */
    @Synchronized
    fun release() {
        loaded?.second?.release()
        loaded = null
    }

    /**
     * [samples] cut into windows (as ranges of indices) of [SHORTEST] to [LONGEST], each ending at the quietest 0.2 s
     * in that stretch, so a cut falls between words rather than through one.
     */
    fun windows(samples: FloatArray): List<IntRange> {
        val cuts = mutableListOf<IntRange>()
        var from = 0
        while (from < samples.size) {
            if (samples.size - from <= LONGEST) {
                cuts += from until samples.size
                break
            }
            var quietest = from + LONGEST
            var least = Double.MAX_VALUE
            var at = from + SHORTEST
            while (at + QUIET_STEP <= from + LONGEST) {
                var sum = 0.0
                for (i in at until at + QUIET_STEP) sum += samples[i] * samples[i]
                if (sum < least) {
                    least = sum
                    quietest = at + QUIET_STEP / 2
                }
                at += QUIET_STEP / 2
            }
            cuts += from until quietest
            from = quietest
        }
        return cuts
    }

    /**
     * The words in a window's tokens: a token starting with "▁" (or a space) starts a word, any other joins the one
     * before (the rest of a word, or its punctuation). Each word starts at its first token's time ([timestamps], in
     * seconds from [offset]) and ends where the next word starts, at most 1.5 s after it starts; the last at the
     * window's [end].
     */
    fun words(tokens: List<String>, timestamps: List<Float>, offset: Long, end: Long): List<Word> {
        data class Building(val text: StringBuilder, val start: Long)
        val built = mutableListOf<Building>()
        tokens.forEachIndexed { i, token ->
            if (token.startsWith("<|") || token.isEmpty()) return@forEachIndexed
            val at = offset + ((timestamps.getOrNull(i) ?: 0f) * 1000).roundToLong()
            val starts = token.startsWith("▁") || token.startsWith(" ")
            val text = token.trimStart('▁', ' ')
            if (starts || built.isEmpty()) built += Building(StringBuilder(text), at) else built.last().text.append(text)
        }
        return built.filter { it.text.isNotBlank() }.let { list ->
            list.mapIndexed { i, b ->
                val next = list.getOrNull(i + 1)?.start ?: end
                Word(b.text.toString(), b.start, maxOf(b.start, minOf(next, b.start + 1_500, end)), 1f)
            }
        }
    }
}

/**
 * Which of Parakeet's languages a transcript is in, from its letters and its commonest little words, for the subtitle
 * file's language and the line rules ([SpeechLines]); "und" when it can't tell. Plain Kotlin.
 */
object LanguageGuess {
    private val common = mapOf(
        "en" to setOf("the", "and", "is", "you", "that", "it", "of", "to", "what", "this", "i", "we", "was", "for"),
        "de" to setOf("der", "die", "und", "das", "ist", "nicht", "ich", "sie", "es", "ein", "zu", "mit", "wir", "auf"),
        "fr" to setOf("le", "la", "et", "les", "est", "je", "vous", "pas", "que", "une", "des", "il", "nous", "ce"),
        "es" to setOf("el", "la", "que", "y", "es", "los", "no", "en", "un", "por", "con", "para", "una", "se"),
        "it" to setOf("il", "che", "e", "di", "non", "la", "un", "è", "per", "sono", "con", "una", "mi", "ho"),
        "pt" to setOf("o", "que", "e", "não", "de", "um", "uma", "é", "com", "para", "os", "você", "eu", "se"),
        "nl" to setOf("de", "het", "een", "en", "is", "niet", "ik", "je", "dat", "van", "wat", "we", "zijn", "op"),
        "pl" to setOf("nie", "i", "w", "to", "się", "na", "jest", "że", "z", "co", "jak", "ja", "tak", "do"),
        "ru" to setOf("и", "не", "в", "что", "на", "я", "это", "он", "с", "как", "мы", "вы", "но", "так"),
        "uk" to setOf("і", "не", "в", "що", "на", "я", "це", "він", "з", "як", "ми", "ви", "але", "так"),
    )

    fun of(text: String): String {
        val lower = text.lowercase()
        val letters = lower.filter { it.isLetter() }
        if (letters.isEmpty()) return "und"
        val cyrillic = letters.count { it in 'а'..'я' || it in "ёіїєґ" }
        val words = lower.split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (cyrillic * 2 > letters.length) {
            // Ukrainian has і, ї, є and ґ; Russian has ы, э and ъ
            val uk = letters.count { it in "іїєґ" }
            val ru = letters.count { it in "ыэъё" }
            if (uk != ru) return if (uk > ru) "uk" else "ru"
            return best(words, listOf("ru", "uk")) ?: "ru"
        }
        return best(words, common.keys.filter { it != "ru" && it != "uk" }) ?: "und"
    }

    private fun best(words: List<String>, among: List<String>): String? =
        among.map { lang -> lang to words.count { it in common.getValue(lang) } }
            .maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first
}
