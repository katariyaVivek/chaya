package com.chaya.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Keeps yt-dlp, its solver scripts (yt-dlp-ejs) and gallery-dl current. Sites change often and these ship
 * fixes within days, but the copies inside the app only change with a new APK.
 *
 * About once a day it asks PyPI's JSON API for the latest release of each. A newer one is fetched as its
 * pure-Python wheel, checked against the SHA-256 PyPI lists for it, and compiled for the phone's Python; the
 * set is then handed to [EngineSets], which checks it and uses it from the engine's next start. yt-dlp and
 * yt-dlp-ejs go together: the ejs release is the one the new yt-dlp names. A release that needs a newer
 * library than the app carries, or another Python, is refused. The requests carry nothing about the person.
 */
class EngineUpdater(
    private val sets: EngineSets,
    /** Compiles a wheel into a package Python imports without compiling it again on each start. */
    private val compile: (wheel: File, out: File) -> Unit,
    /** The version of a library inside the app (`requests`, `urllib3`…), or null when it has none. */
    private val installedVersion: (name: String) -> String?,
    private val client: OkHttpClient = defaultClient(),
    private val pypi: HttpUrl = "https://pypi.org/".toHttpUrl(),
    /** Wheels are only fetched from these hosts; PyPI keeps its files on files.pythonhosted.org. */
    private val wheelHosts: Set<String> = setOf("files.pythonhosted.org"),
    private val environment: PyEnvironment = PyEnvironment.ANDROID,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { NOT_DUE, UP_TO_DATE, DOWNLOADED, REFUSED, FAILED }

    /** A release PyPI offers that Chaya will not use; remembered, so it is not fetched again. */
    private class Refused(message: String) : Exception(message)

    /** Whether to ask PyPI now: updates are on, a day has passed since the last answer, and hours since a failed try. */
    fun isUpdateDue(): Boolean {
        if (!sets.updatesOn()) return false
        val now = clock()
        return now - sets.checkedAt() >= CHECK_EVERY_MILLIS && now - sets.triedAt() >= RETRY_AFTER_MILLIS
    }

    suspend fun updateIfDue(): Outcome = if (isUpdateDue()) check() else Outcome.NOT_DUE

    /** Asks PyPI now and fetches a newer set if there is one. */
    suspend fun check(): Outcome = withContext(Dispatchers.IO) {
        sets.recordTry(clock())
        val seen = HashMap<String, EngineSets.Seen>()
        var candidate: Map<String, String>? = null
        try {
            val latest = listOf("yt-dlp", "gallery-dl").associateWith { name -> latestVersion(name).also { seen[name] = it }.version }
            val releases = HashMap<String, JSONObject>()
            fun release(name: String, version: String) = releases.getOrPut(name) { releaseJson(name, version) }

            val versions = LinkedHashMap<String, String>()
            versions["yt-dlp"] = newerOrBundled("yt-dlp", latest.getValue("yt-dlp"))
            versions["yt-dlp-ejs"] = if (versions["yt-dlp"] == bundled("yt-dlp")) {
                bundled("yt-dlp-ejs")
            } else {
                // yt-dlp checks its solver scripts against the ejs release it was built with.
                ejsFor(release("yt-dlp", versions.getValue("yt-dlp")))
            }
            versions["gallery-dl"] = newerOrBundled("gallery-dl", latest.getValue("gallery-dl"))
            candidate = versions

            val fromPypi = ENGINE_PACKAGES.map { it.name }.filter { !PyVersion.same(versions.getValue(it), bundled(it)) }
            if (fromPypi.isEmpty() || sets.isKept(versions)) {
                sets.recordCheck(clock(), seen, null)
                return@withContext Outcome.UP_TO_DATE
            }
            if (sets.isSkipped(versions)) {
                sets.recordCheck(clock(), seen, null)
                return@withContext Outcome.REFUSED
            }

            val files = LinkedHashMap<String, String>()
            for (name in fromPypi) {
                val version = versions.getValue(name)
                files[name] = sets.fileFor(name, version) ?: fetch(name, version, release(name, version), versions)
            }
            sets.add(EngineSets.EngineSet(versions, files, clock(), EngineSets.Status.NEW))
            val described = fromPypi.joinToString(" and ") { "$it ${versions[it]}" }
            sets.recordCheck(clock(), seen, "Fetched $described; used from the next start")
            Outcome.DOWNLOADED
        } catch (e: Refused) {
            candidate?.let { sets.skip(it) }
            sets.recordCheck(clock(), seen, "Not updated: ${e.message}")
            Outcome.REFUSED
        } catch (e: IOException) {
            sets.recordNote("Could not check for engine updates: ${e.message}")
            Outcome.FAILED
        } catch (e: org.json.JSONException) {
            sets.recordNote("Could not read PyPI's answer: ${e.message}")
            Outcome.FAILED
        }
    }

    private fun bundled(name: String) = sets.bundled.getValue(name)

    private fun newerOrBundled(name: String, latest: String) =
        if (PyVersion.compare(latest, bundled(name)) > 0) latest else bundled(name)

    /** The latest release of [name], or the one PyPI named last time when it answers that nothing changed. */
    private fun latestVersion(name: String): EngineSets.Seen {
        val previous = sets.seen(name)
        val request = Request.Builder().url(pypi.resolve("pypi/$name/json")!!)
            .apply { previous?.etag?.let { header("If-None-Match", it) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 304 && previous != null) return previous
            if (!response.isSuccessful) throw IOException("PyPI answered ${response.code} for $name")
            val version = JSONObject(response.body!!.string()).getJSONObject("info").getString("version")
            if (!PyVersion.isValid(version) || PyVersion.isPrerelease(version)) throw IOException("$name $version is not a release")
            return EngineSets.Seen(response.header("ETag"), version)
        }
    }

    private fun releaseJson(name: String, version: String): JSONObject {
        val request = Request.Builder().url(pypi.resolve("pypi/$name/$version/json")!!).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("PyPI answered ${response.code} for $name $version")
            return JSONObject(response.body!!.string())
        }
    }

    /** The yt-dlp-ejs version a yt-dlp release names (`yt-dlp-ejs==0.8.0; extra == "default"`). */
    private fun ejsFor(release: JSONObject): String {
        val version = requirements(release)
            .filter { it.name == "yt-dlp-ejs" && it.appliesTo(environment, setOf("default")) }
            .firstNotNullOfOrNull { requirement -> requirement.specifiers.singleOrNull { it.first == "==" }?.second }
            ?: throw Refused("yt-dlp ${release.getJSONObject("info").optString("version")} does not name its yt-dlp-ejs")
        if (!PyVersion.isValid(version) || version.endsWith(".*")) throw Refused("yt-dlp names yt-dlp-ejs $version")
        return version
    }

    private fun requirements(release: JSONObject): List<PyRequirement> {
        val lines = release.getJSONObject("info").optJSONArray("requires_dist") ?: JSONArray()
        return (0 until lines.length()).mapNotNull { i ->
            val line = lines.getString(i)
            PyRequirement.parse(line) ?: if (Regex("""\bextra\b""").containsMatchIn(line)) null else throw Refused("cannot read its requirement \"$line\"")
        }
    }

    /**
     * Checks that [name] [version] can run beside what the app carries, then fetches, verifies and compiles its
     * wheel. Returns the compiled package's file name in [EngineSets.dir].
     */
    private fun fetch(name: String, version: String, release: JSONObject, versions: Map<String, String>): String {
        val label = "$name $version"
        val info = release.getJSONObject("info")
        info.optString("requires_python").takeIf { it.isNotBlank() && it != "null" }?.let { spec ->
            val python = PyRequirement.parse("python $spec")
            if (python == null || !python.allows(environment.pythonFullVersion)) throw Refused("$label needs Python $spec")
        }
        for (requirement in requirements(release)) {
            val engine = ENGINE_PACKAGES.firstOrNull { it.name == requirement.name }
            if (engine != null) {
                val applies = requirement.appliesTo(environment) || requirement.appliesTo(environment, setOf("default"))
                if (applies && !requirement.allows(versions.getValue(engine.name))) {
                    throw Refused("$label needs ${requirement.name} ${spec(requirement)}")
                }
                continue
            }
            val required = requirement.appliesTo(environment)
            if (!required && !requirement.appliesTo(environment, setOf("default"))) continue
            val installed = try {
                installedVersion(requirement.name)
            } catch (e: Exception) {
                throw IOException("could not tell which ${requirement.name} the app has: ${e.message}")
            }
            if (installed == null) {
                // A library yt-dlp only uses when it is there (mutagen, brotli…) may be missing; a required one may not.
                if (required) throw Refused("$label needs ${requirement.name}, which the app does not have")
            } else if (!requirement.allows(installed)) {
                throw Refused("$label needs ${requirement.name} ${spec(requirement)}; the app has $installed")
            }
        }

        val wheel = pureWheel(release) ?: throw Refused("$label has no pure-Python wheel")
        val url = wheel.getString("url").toHttpUrlOrNull()
        if (url == null || url.host !in wheelHosts) throw Refused("$label's wheel is not on PyPI's file host")
        val size = wheel.optLong("size", -1)
        if (size > MAX_WHEEL_BYTES) throw Refused("$label's wheel is too large ($size bytes)")
        val sha256 = wheel.getJSONObject("digests").getString("sha256").lowercase()

        sets.dir.mkdirs()
        val engine = ENGINE_PACKAGES.first { it.name == name }
        val download = File(sets.dir, "$name-$version.whl.download")
        val compiled = File(sets.dir, "$name-$version.zip.tmp")
        val target = File(sets.dir, "$name-$version.zip")
        try {
            download(url, download, sha256, label)
            checkWheel(download, engine, version, label)
            try {
                compile(download, compiled)
            } catch (e: Exception) {
                throw IOException("could not compile $label: ${e.message}")
            }
            if (!compiled.isFile || !compiled.renameTo(target)) throw IOException("could not compile $label")
        } finally {
            download.delete()
            compiled.delete()
        }
        return target.name
    }

    private fun spec(requirement: PyRequirement) = requirement.specifiers.joinToString(",") { it.first + it.second }

    /** The release's `py3-none-any` wheel, not yanked. */
    private fun pureWheel(release: JSONObject): JSONObject? {
        val urls = release.optJSONArray("urls") ?: return null
        return (0 until urls.length()).map { urls.getJSONObject(it) }.firstOrNull { file ->
            val filename = file.optString("filename")
            file.optString("packagetype") == "bdist_wheel" && !file.optBoolean("yanked") &&
                filename.endsWith("-none-any.whl") &&
                filename.removeSuffix("-none-any.whl").substringAfterLast('-').split('.').any { it == "py3" }
        }
    }

    private fun download(url: HttpUrl, into: File, sha256: String, label: String) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("the wheel for $label answered ${response.code}")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            response.body!!.byteStream().use { input ->
                into.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_WHEEL_BYTES) throw IOException("the wheel for $label is too large")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != sha256) throw IOException("the wheel for $label does not match PyPI's SHA-256")
        }
    }

    /** A wheel is a zip holding the package, its metadata naming [version], and no compiled code. */
    private fun checkWheel(wheel: File, engine: EnginePackage, version: String, label: String) {
        val zip = try {
            ZipFile(wheel)
        } catch (e: IOException) {
            throw IOException("the wheel for $label is not a zip")
        }
        zip.use {
            val names = it.entries().asSequence().map { entry -> entry.name }.toList()
            if ("${engine.module}/__init__.py" !in names) throw IOException("the wheel for $label lacks ${engine.module}")
            if (names.any { n -> NATIVE.any { ext -> n.endsWith(ext) } }) throw Refused("$label's wheel holds native code")
            val metadata = names.firstOrNull { n -> n.endsWith(".dist-info/METADATA") && !n.substringBefore('/').contains('/') }
                ?: throw IOException("the wheel for $label has no metadata")
            val stated = it.getInputStream(it.getEntry(metadata)).bufferedReader().useLines { lines ->
                lines.takeWhile { line -> line.isNotEmpty() }.firstOrNull { line -> line.startsWith("Version:") }
            }?.substringAfter(':')?.trim()
            if (stated == null || !PyVersion.same(stated, version)) throw IOException("the wheel for $label says it is $stated")
        }
    }

    companion object {
        const val CHECK_EVERY_MILLIS = 24L * 60 * 60 * 1000
        const val RETRY_AFTER_MILLIS = 6L * 60 * 60 * 1000
        const val MAX_WHEEL_BYTES = 50L * 1024 * 1024
        private val NATIVE = listOf(".so", ".pyd", ".dll", ".dylib")

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
