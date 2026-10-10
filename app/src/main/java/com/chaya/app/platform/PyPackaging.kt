package com.chaya.app.platform

/**
 * Just enough of Python's packaging rules (PEP 440 versions, PEP 508 requirements and markers) for
 * [EngineUpdater] to read what PyPI says about a release. Plain Kotlin, so it can be tested anywhere.
 */
object PyVersion {
    private val pattern = Regex(
        """^v?(\d+(?:\.\d+)*)(?:[-_.]?(a|b|c|rc|alpha|beta|pre|preview)[-_.]?(\d*))?""" +
            """(?:[-_.]?(?:post|rev|r)[-_.]?(\d*))?(?:[-_.]?dev[-_.]?(\d*))?$""",
        RegexOption.IGNORE_CASE,
    )

    private class Key(
        val release: List<Int>,
        val pre: Int, // -4 dev only, -3 alpha, -2 beta, -1 release candidate, 0 none
        val preNumber: Int,
        val post: Int,
        val dev: Int,
    )

    private fun key(version: String): Key? {
        val match = pattern.matchEntire(version.trim()) ?: return null
        val (release, preLabel, preNumber, post, dev) = match.destructured
        val hasDev = match.groups[5] != null
        val pre = when (preLabel.lowercase()) {
            "a", "alpha" -> -3
            "b", "beta" -> -2
            "c", "rc", "pre", "preview" -> -1
            else -> if (hasDev && match.groups[4] == null) -4 else 0
        }
        return Key(
            release = release.split('.').map { it.toIntOrNull() ?: return null }.dropLastWhile { it == 0 },
            pre = pre,
            preNumber = preNumber.toIntOrNull() ?: 0,
            post = if (match.groups[4] != null) post.toIntOrNull() ?: 0 else -1,
            dev = if (hasDev) dev.toIntOrNull() ?: 0 else Int.MAX_VALUE,
        )
    }

    /** Whether [version] is one Python's packaging tools would read. */
    fun isValid(version: String): Boolean = key(version) != null

    /** An alpha, beta, release candidate or development release. */
    fun isPrerelease(version: String): Boolean = key(version)?.let { it.pre != 0 || it.dev != Int.MAX_VALUE } ?: false

    /**
     * Orders two versions: 2026.8.19 < 2026.10.1, 2026.08.19 = 2026.8.19, 1.0rc1 < 1.0 < 1.0.post1.
     * A version that cannot be read sorts below every one that can.
     */
    fun compare(a: String, b: String): Int {
        val ka = key(a)
        val kb = key(b)
        if (ka == null || kb == null) return compareValues(ka != null, kb != null)
        val length = maxOf(ka.release.size, kb.release.size)
        for (i in 0 until length) {
            val c = (ka.release.getOrElse(i) { 0 }).compareTo(kb.release.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return compareValuesBy(ka, kb, { it.pre }, { it.preNumber }, { it.post }, { it.dev })
    }

    fun same(a: String, b: String) = compare(a, b) == 0

    /** The release numbers alone: 2026.08.19 → [2026, 8, 19]. */
    internal fun release(version: String): List<Int> =
        Regex("""^v?(\d+(?:\.\d+)*)""").find(version.trim())?.groupValues?.get(1)?.split('.')?.map { it.toInt() }.orEmpty()
}

/**
 * Where the engine runs, for deciding which requirements apply: Python 3.13 on Android, as Chaquopy
 * provides it.
 */
data class PyEnvironment(
    val pythonVersion: String = "3.13",
    val pythonFullVersion: String = "3.13.0",
    val values: Map<String, String> = mapOf(
        "sys_platform" to "android",
        "platform_system" to "Android",
        "os_name" to "posix",
        "implementation_name" to "cpython",
        "platform_python_implementation" to "CPython",
    ),
) {
    companion object {
        val ANDROID = PyEnvironment()
    }
}

/** One line of a package's `Requires-Dist`, such as `requests<3,>=2.32.2; extra == 'default'`. */
data class PyRequirement(
    /** The normalised name: lower case, runs of `-`, `_` and `.` as one `-`. */
    val name: String,
    val specifiers: List<Pair<String, String>>,
    val marker: String?,
) {
    /** Whether [version] meets every specifier (none means any version). */
    fun allows(version: String): Boolean = specifiers.all { (op, wanted) -> PySpecifier.matches(op, wanted, version) }

    /** Whether this requirement applies on [environment] when the given [extras] are asked for. */
    fun appliesTo(environment: PyEnvironment, extras: Set<String> = emptySet()): Boolean =
        marker == null || PyMarker.evaluate(marker, environment, extras)

    /** True when the requirement only applies with an extra (`extra == '…'` in its marker). */
    val isOptional: Boolean get() = marker != null && Regex("""\bextra\b""").containsMatchIn(marker)

    companion object {
        fun normalise(name: String) = name.trim().lowercase().replace(Regex("[-_.]+"), "-")

        /** Reads one requirement line; null when it cannot be read. */
        fun parse(line: String): PyRequirement? {
            val marker = line.substringAfter(';', "").trim().ifEmpty { null }
            var spec = line.substringBefore(';').trim()
            // Old style: "name (>=1.0)".
            spec = spec.replace("(", " ").replace(")", " ")
            val name = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]*""").find(spec)?.value ?: return null
            var rest = spec.substring(name.length).trim()
            if (rest.startsWith("[")) rest = rest.substringAfter(']').trim()
            if (rest.startsWith("@")) return null // a direct address, not something PyPI resolves
            val specifiers = rest.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
                val op = Regex("""^(===|~=|==|!=|<=|>=|<|>)""").find(part)?.value ?: return null
                op to part.substring(op.length).trim()
            }
            return PyRequirement(normalise(name), specifiers, marker)
        }
    }
}

internal object PySpecifier {
    fun matches(op: String, wanted: String, version: String): Boolean = when (op) {
        "===" -> version == wanted
        "==" -> if (wanted.endsWith(".*")) prefixMatches(wanted, version) else PyVersion.same(version, wanted)
        "!=" -> if (wanted.endsWith(".*")) !prefixMatches(wanted, version) else !PyVersion.same(version, wanted)
        "<=" -> PyVersion.compare(version, wanted) <= 0
        ">=" -> PyVersion.compare(version, wanted) >= 0
        // "<1.0" leaves out 1.0's own pre-releases, unless it names one.
        "<" -> PyVersion.compare(version, wanted) < 0 &&
            !(PyVersion.isPrerelease(version) && !PyVersion.isPrerelease(wanted) && PyVersion.same(PyVersion.release(version).joinToString("."), wanted))
        ">" -> PyVersion.compare(version, wanted) > 0
        "~=" -> {
            val release = PyVersion.release(wanted)
            PyVersion.compare(version, wanted) >= 0 && release.size >= 2 &&
                PyVersion.release(version).take(release.size - 1) == release.dropLast(1)
        }
        else -> false
    }

    private fun prefixMatches(wanted: String, version: String): Boolean {
        val prefix = PyVersion.release(wanted.removeSuffix(".*"))
        val release = PyVersion.release(version)
        return (release + List(maxOf(0, prefix.size - release.size)) { 0 }).take(prefix.size) == prefix
    }
}

/**
 * Evaluates an environment marker (`python_version >= "3.11" and extra == 'default'`). A name it does not
 * know counts as matching, so a requirement it cannot judge is treated as one that applies.
 */
internal object PyMarker {
    fun evaluate(marker: String, environment: PyEnvironment, extras: Set<String>): Boolean =
        runCatching { Parser(tokens(marker), environment, extras).parse() }.getOrDefault(true)

    private val tokenPattern = Regex(
        """\s*(\(|\)|===|==|!=|<=|>=|~=|<|>|'[^']*'|"[^"]*"|not\s+in\b|in\b|and\b|or\b|[A-Za-z_][A-Za-z0-9_.]*)""",
    )

    private fun tokens(marker: String): List<String> {
        val out = ArrayList<String>()
        var at = 0
        while (at < marker.length) {
            if (marker[at].isWhitespace()) { at++; continue }
            val match = tokenPattern.matchAt(marker, at) ?: error("unreadable marker")
            out += match.groupValues[1].replace(Regex("""\s+"""), " ")
            at = match.range.last + 1
        }
        return out
    }

    private class Parser(val tokens: List<String>, val environment: PyEnvironment, val extras: Set<String>) {
        var at = 0

        fun parse(): Boolean = or().also { check(at == tokens.size) }

        private fun or(): Boolean {
            var value = and()
            while (peek() == "or") { at++; value = and() || value }
            return value
        }

        private fun and(): Boolean {
            var value = atom()
            while (peek() == "and") { at++; value = atom() && value }
            return value
        }

        private fun atom(): Boolean {
            if (peek() == "(") {
                at++
                val value = or()
                check(next() == ")")
                return value
            }
            val left = next()
            val op = next()
            val right = next()
            return compare(left, op, right)
        }

        private fun peek() = tokens.getOrNull(at)
        private fun next() = tokens.getOrNull(at++) ?: error("marker ended early")

        private fun isLiteral(token: String) = token.startsWith("'") || token.startsWith("\"")

        private fun compare(left: String, op: String, right: String): Boolean {
            // Written either way round: extra == 'x' or 'x' == extra.
            val (name, literal) = when {
                isLiteral(right) && !isLiteral(left) -> left to right.trim('\'', '"')
                isLiteral(left) && !isLiteral(right) -> right to left.trim('\'', '"')
                else -> return true
            }
            val flipped = isLiteral(left)
            if (name == "extra") {
                val normalised = PyRequirement.normalise(literal)
                val has = extras.any { PyRequirement.normalise(it) == normalised }
                return when (op) { "==" -> has; "!=" -> !has; else -> true }
            }
            val value = when (name) {
                "python_version" -> environment.pythonVersion
                "python_full_version" -> environment.pythonFullVersion
                else -> environment.values[name] ?: return true
            }
            val versionLike = name == "python_version" || name == "python_full_version"
            val (l, r) = if (flipped) literal to value else value to literal
            return when (op) {
                "in" -> r.contains(l)
                "not in" -> !r.contains(l)
                else -> if (versionLike && op != "===") PySpecifier.matches(op, r, l) else when (op) {
                    "==", "===" -> l == r
                    "!=" -> l != r
                    else -> true
                }
            }
        }
    }
}
