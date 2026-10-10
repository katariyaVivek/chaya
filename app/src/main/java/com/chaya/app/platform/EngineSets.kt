package com.chaya.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One of the Python packages the engine is made of: its name on PyPI and the module it is imported as. */
data class EnginePackage(val name: String, val module: String)

/** yt-dlp, the solver scripts it was built with, and gallery-dl, in the order they go on `sys.path`. */
val ENGINE_PACKAGES = listOf(
    EnginePackage("yt-dlp", "yt_dlp"),
    EnginePackage("yt-dlp-ejs", "yt_dlp_ejs"),
    EnginePackage("gallery-dl", "gallery_dl"),
)

/** What [EngineSets] needs from Python to change which copy of the engine is imported. */
interface EngineRuntime {
    /** Puts [files] (compiled packages) at the front of `sys.path`, in order, and forgets engine modules already imported. */
    fun use(files: List<File>)

    /** Takes them off `sys.path` and forgets their modules, so the next import uses the copy in the app. */
    fun drop()

    /** `chaya_engine.selftest.status()`: the versions imported, as JSON. Throws when the engine cannot be imported. */
    fun status(): String
}

/**
 * The copies of yt-dlp, yt-dlp-ejs and gallery-dl that [EngineUpdater] fetched from PyPI, which one the
 * engine uses, and whether each can be trusted. Kept in `state.json` beside the compiled packages.
 *
 * A new set is used from the next start of the engine, never mid-run. The first time, it is checked before
 * anything else (`selftest.status` must report its versions); if that fails, or if the engine later fails
 * twice in a row with it, the set is skipped from then on and the engine goes back to the last good set, or
 * to the copy in the app. The last two good sets are kept, so there is a fallback besides the app's copy.
 */
class EngineSets(
    val dir: File,
    /** The versions inside the app, by package name. */
    val bundled: Map<String, String>,
) {
    enum class Status { NEW, TRUSTED, BAD }

    /** One version of every package; [files] names the compiled package in [dir] for each one not from the app. */
    data class EngineSet(
        val versions: Map<String, String>,
        val files: Map<String, String>,
        val addedAt: Long,
        val status: Status,
        val failures: Int = 0,
    ) {
        val id: String get() = idOf(versions)
    }

    /** The last answer PyPI gave for a package, so an unchanged one can be asked about with `If-None-Match`. */
    data class Seen(val etag: String?, val version: String)

    /** What the Diagnostics screen shows. */
    data class EngineStatus(
        /** The versions in use, or that will be used when the engine starts. */
        val versions: Map<String, String>,
        val fromApp: Boolean,
        /** Whether the engine has started in this run of the app. */
        val started: Boolean,
        /** A newer set that will be checked and used from the next start. */
        val waiting: Map<String, String>?,
        val checkedAt: Long?,
        val updatesOn: Boolean,
        /** What happened last, in a sentence: an update, a refusal, a fallback. */
        val note: String?,
    )

    private data class State(
        val sets: List<EngineSet> = emptyList(),
        val skipped: List<String> = emptyList(),
        val seen: Map<String, Seen> = emptyMap(),
        val checkedAt: Long = 0,
        val triedAt: Long = 0,
        val checking: String? = null,
        val updatesOn: Boolean = true,
        val note: String? = null,
    )

    private val stateFile = File(dir, "state.json")

    // Read from disk on first use, not when the app builds this object on its main thread.
    private var loaded: State? = null
    private var state: State
        get() = loaded ?: load().also { loaded = it }
        set(value) {
            loaded = value
        }

    /** The set this run of the app imports, once [activate] has run; null while that is the app's copy. */
    var active: EngineSet? = null
        private set
    private var started = false

    private val _status by lazy { MutableStateFlow(synchronized(this) { statusOf() }) }
    val status: StateFlow<EngineStatus> get() = _status.asStateFlow()

    val bundledId: String get() = idOf(bundled)

    @Synchronized
    fun updatesOn(): Boolean = state.updatesOn

    /** Turns fetching updates on or off. Off, the engine uses the copy in the app from its next start. */
    @Synchronized
    fun setUpdatesOn(on: Boolean) = update { it.copy(updatesOn = on) }

    /**
     * Chooses the copy of the engine for this run and puts it on `sys.path` through [runtime]; call before the
     * first `import yt_dlp`. A new set is checked first, and dropped if the check fails. Returns the set in
     * use, or null for the copy in the app.
     */
    @Synchronized
    fun activate(runtime: EngineRuntime): EngineSet? {
        // A check that never finished: the app died while trying that set.
        state.checking?.let { id ->
            val set = state.sets.firstOrNull { it.id == id }
            update { s ->
                markBad(s, id).copy(checking = null, note = set?.let { "${describe(it)} stopped the app while being checked; not used" } ?: s.note)
            }
        }
        active = null
        if (state.updatesOn) {
            while (true) {
                val set = state.sets.filter { it.status != Status.BAD }.maxByOrNull { it.addedAt } ?: break
                val files = set.files.values.map { File(dir, it) }
                if (!files.all { it.isFile }) {
                    update { it.copy(sets = it.sets - set) }
                    continue
                }
                if (set.status == Status.TRUSTED) {
                    if (runCatching { runtime.use(ordered(set)) }.isSuccess) {
                        active = set
                        break
                    }
                    runCatching { runtime.drop() }
                    update { markBad(it, set.id).copy(note = "${describe(set)} could not be loaded; not used") }
                    continue
                }
                update { it.copy(checking = set.id) }
                val failure = runCatching {
                    runtime.use(ordered(set))
                    whyNot(set, JSONObject(runtime.status()))
                }.getOrElse { "it did not load (${it.message?.lineSequence()?.firstOrNull { l -> l.isNotBlank() } ?: it.javaClass.simpleName})" }
                if (failure == null) {
                    val trusted = set.copy(status = Status.TRUSTED)
                    active = trusted
                    update { s -> s.copy(sets = s.sets.map { if (it.id == set.id) trusted else it }, checking = null, note = "Now using ${describe(set)}") }
                    break
                }
                runCatching { runtime.drop() }
                update { markBad(it, set.id).copy(checking = null, note = "${describe(set)} failed its check: $failure; not used") }
            }
        }
        started = true
        update { pruned(it) }
        return active
    }

    /**
     * Records how a lookup went. [engineFailed] means the engine itself broke (an error escaped it), not that a
     * link could not be used. Twice in a row with a set from PyPI, and that set is skipped from the next start.
     */
    @Synchronized
    fun runFinished(engineFailed: Boolean) {
        val set = active ?: return
        val current = state.sets.firstOrNull { it.id == set.id } ?: return
        val failures = if (engineFailed) current.failures + 1 else 0
        if (failures == current.failures) return
        if (failures >= FAILURES_BEFORE_FALLBACK) {
            update { markBad(it, set.id).copy(note = "${describe(set)} failed $failures times in a row; the next start goes back to the last good copy") }
        } else {
            update { s -> s.copy(sets = s.sets.map { if (it.id == set.id) it.copy(failures = failures) else it }) }
        }
        active = state.sets.firstOrNull { it.id == set.id }
    }

    // ---- for EngineUpdater ---- //

    @Synchronized
    internal fun seen(name: String): Seen? = state.seen[name]

    @Synchronized
    internal fun checkedAt(): Long = state.checkedAt

    @Synchronized
    internal fun triedAt(): Long = state.triedAt

    @Synchronized
    internal fun recordTry(now: Long) = update { it.copy(triedAt = now) }

    /** A check that reached an answer: PyPI's answers are remembered, and [note] says what came of it. */
    @Synchronized
    internal fun recordCheck(now: Long, seen: Map<String, Seen>, note: String?) =
        update { it.copy(checkedAt = now, seen = it.seen + seen, note = note ?: it.note) }

    @Synchronized
    internal fun recordNote(note: String) = update { it.copy(note = note) }

    /** Whether a set of these versions is already kept, in use or waiting for the next start. */
    @Synchronized
    internal fun isKept(versions: Map<String, String>): Boolean =
        state.sets.any { it.id == idOf(versions) && it.status != Status.BAD }

    @Synchronized
    internal fun isSkipped(versions: Map<String, String>): Boolean = idOf(versions) in state.skipped

    @Synchronized
    internal fun skip(versions: Map<String, String>) =
        update { it.copy(skipped = (it.skipped - idOf(versions) + idOf(versions)).takeLast(MAX_SKIPPED)) }

    /** A compiled package already kept for [name] at [version], to use again in a new set. */
    @Synchronized
    internal fun fileFor(name: String, version: String): String? =
        state.sets.firstOrNull { it.versions[name] == version && it.files[name] != null && File(dir, it.files[name]!!).isFile }
            ?.files?.get(name)

    /** The newest set that is not skipped, whatever its status; what a check compares PyPI's versions with. */
    @Synchronized
    internal fun newest(): EngineSet? = state.sets.filter { it.status != Status.BAD }.maxByOrNull { it.addedAt }

    /** Adds a set fetched from PyPI; it is checked and used from the next start. */
    @Synchronized
    internal fun add(set: EngineSet) = update { s -> pruned(s.copy(sets = s.sets.filter { it.id != set.id } + set)) }

    // ---- state ---- //

    private fun ordered(set: EngineSet): List<File> =
        ENGINE_PACKAGES.mapNotNull { pkg -> set.files[pkg.name]?.let { File(dir, it) } }

    /** Why a set's self-test answer does not show it working, or null when it does. */
    private fun whyNot(set: EngineSet, status: JSONObject): String? {
        for (pkg in ENGINE_PACKAGES) {
            val wanted = set.versions[pkg.name] ?: continue
            val got = status.optString(pkg.module)
            if (!PyVersion.same(got, wanted)) return "${pkg.name} reported $got instead of $wanted"
        }
        if (!status.optBoolean("provider_registered")) return "the YouTube solver did not register"
        if (!status.optBoolean("post_links_known")) return "gallery-dl did not know post links"
        return null
    }

    private fun markBad(state: State, id: String): State = state.copy(
        sets = state.sets.map { if (it.id == id) it.copy(status = Status.BAD) else it },
        skipped = (state.skipped - id + id).takeLast(MAX_SKIPPED),
    )

    /**
     * Keeps the set in use, the two newest trusted sets, and the newest new set if it is newer than those;
     * deletes the rest and any compiled package no kept set names.
     */
    private fun pruned(state: State): State {
        val trusted = state.sets.filter { it.status == Status.TRUSTED }.sortedByDescending { it.addedAt }.take(KEPT_TRUSTED)
        val newestTrusted = trusted.firstOrNull()?.addedAt ?: Long.MIN_VALUE
        val fresh = state.sets.filter { it.status == Status.NEW && it.addedAt > newestTrusted }.maxByOrNull { it.addedAt }
        val inUse = active?.let { a -> state.sets.firstOrNull { it.id == a.id } }
        val kept = state.sets.filter { it in trusted || it == fresh || it == inUse }
        val keptFiles = kept.flatMap { it.files.values }.toSet()
        (state.sets - kept.toSet()).flatMap { it.files.values }.filter { it !in keptFiles }.forEach { File(dir, it).delete() }
        return state.copy(sets = kept)
    }

    private fun update(change: (State) -> State) {
        state = change(state)
        save(state)
        _status.value = statusOf()
    }

    private fun statusOf(): EngineStatus {
        val s = state
        val inUse = when {
            started -> active
            !s.updatesOn -> null
            else -> s.sets.filter { it.status == Status.TRUSTED }.maxByOrNull { it.addedAt }
        }
        val waiting = s.sets.filter { it.status == Status.NEW }.maxByOrNull { it.addedAt }
            ?.takeIf { s.updatesOn && it.addedAt > (inUse?.addedAt ?: Long.MIN_VALUE) }
        return EngineStatus(
            versions = inUse?.versions ?: bundled,
            fromApp = inUse == null,
            started = started,
            waiting = waiting?.versions,
            checkedAt = s.checkedAt.takeIf { it > 0 },
            updatesOn = s.updatesOn,
            note = s.note,
        )
    }

    private fun load(): State {
        if (!stateFile.isFile) return State()
        return runCatching { parse(JSONObject(stateFile.readText())) }.getOrElse {
            // Unreadable: start again from the copy in the app, without leaving compiled packages behind.
            dir.listFiles { f -> f.name.endsWith(".zip") }?.forEach { it.delete() }
            State()
        }
    }

    private fun save(state: State) {
        dir.mkdirs()
        val temp = File(dir, "state.json.tmp")
        temp.writeText(toJson(state).toString(1))
        if (!temp.renameTo(stateFile)) {
            stateFile.delete()
            temp.renameTo(stateFile)
        }
    }

    private fun toJson(state: State) = JSONObject().apply {
        put("updatesOn", state.updatesOn)
        put("checkedAt", state.checkedAt)
        put("triedAt", state.triedAt)
        state.checking?.let { put("checking", it) }
        state.note?.let { put("note", it) }
        put("skipped", JSONArray(state.skipped))
        put("seen", JSONObject().apply {
            state.seen.forEach { (name, seen) ->
                put(name, JSONObject().put("version", seen.version).apply { seen.etag?.let { put("etag", it) } })
            }
        })
        put("sets", JSONArray().apply {
            state.sets.forEach { set ->
                put(JSONObject().apply {
                    put("versions", JSONObject(set.versions))
                    put("files", JSONObject(set.files))
                    put("addedAt", set.addedAt)
                    put("status", set.status.name)
                    put("failures", set.failures)
                })
            }
        })
    }

    private fun parse(json: JSONObject): State {
        fun JSONObject.strings(): Map<String, String> = keys().asSequence().associateWith { getString(it) }
        val seen = json.optJSONObject("seen") ?: JSONObject()
        val sets = json.optJSONArray("sets") ?: JSONArray()
        val skipped = json.optJSONArray("skipped") ?: JSONArray()
        return State(
            updatesOn = json.optBoolean("updatesOn", true),
            checkedAt = json.optLong("checkedAt"),
            triedAt = json.optLong("triedAt"),
            checking = json.optString("checking").ifEmpty { null },
            note = json.optString("note").ifEmpty { null },
            skipped = (0 until skipped.length()).map { skipped.getString(it) },
            seen = seen.keys().asSequence().associateWith { name ->
                val entry = seen.getJSONObject(name)
                Seen(entry.optString("etag").ifEmpty { null }, entry.getString("version"))
            },
            sets = (0 until sets.length()).map { i ->
                val set = sets.getJSONObject(i)
                EngineSet(
                    versions = set.getJSONObject("versions").strings(),
                    files = set.getJSONObject("files").strings(),
                    addedAt = set.getLong("addedAt"),
                    status = Status.valueOf(set.getString("status")),
                    failures = set.optInt("failures"),
                )
            },
        )
    }

    companion object {
        const val FAILURES_BEFORE_FALLBACK = 2
        const val KEPT_TRUSTED = 2
        const val MAX_SKIPPED = 20

        fun idOf(versions: Map<String, String>) = ENGINE_PACKAGES.joinToString(", ") { "${it.name} ${versions[it.name]}" }

        /** Reads the app's pins, `yt-dlp==2026.8.19,yt-dlp-ejs==0.8.0,…`, as BuildConfig carries them. */
        fun parsePins(pins: String): Map<String, String> =
            pins.split(',').map { it.trim() }.filter { it.contains("==") }
                .associate { it.substringBefore("==").trim() to it.substringAfter("==").trim() }

        /** "yt-dlp 2026.9.1 and gallery-dl 1.33.0": the packages of [set] that do not come from the app. */
        fun describe(set: EngineSet): String =
            ENGINE_PACKAGES.filter { set.files.containsKey(it.name) }
                .joinToString(" and ") { "${it.name} ${set.versions[it.name]}" }
                .ifEmpty { "the app's own copy" }
    }
}
