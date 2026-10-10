package com.chaya.app.adblock.engine

/**
 * One network rule from an ad list, such as `||ads.example.com/banner^$script,third-party`, past parsing.
 *
 * [pattern] is the address pattern without its anchors: `*` stands for anything, `^` for a separator (any
 * character that is not a letter, digit or one of `_-.%`, or the end of the address). It is lower case unless
 * the rule asked for `match-case`.
 */
internal class NetworkFilter(
    val pattern: String,
    /** `||`: the pattern starts at the beginning of the address's host or of one of its labels. */
    val hostAnchored: Boolean,
    /** `|` at the start: the pattern starts at the beginning of the address. */
    val startAnchored: Boolean,
    /** `|` at the end: the pattern ends at the end of the address. */
    val endAnchored: Boolean,
    val matchCase: Boolean,
    /** The kinds of request it applies to; [RequestType.ALL] when the rule names none. */
    val types: Int,
    /** Rule names no kind of request, so it also applies to requests of unknown kind. */
    val anyType: Boolean,
    /** True for third-party requests only, false for first-party only, null for both. */
    val thirdParty: Boolean?,
    val domains: DomainConstraint?,
    val important: Boolean,
) {
    fun matches(url: String, lowerUrl: String, type: Int, page: PageContext, requestSite: () -> String): Boolean {
        if (anyType) {
            if (type != RequestType.UNKNOWN && types and type == 0) return false
        } else if (type == RequestType.UNKNOWN || types and type == 0) {
            return false
        }
        if (domains != null && !domains.allows(page.host, page.site)) return false
        if (thirdParty != null && thirdParty != (requestSite() != page.site)) return false
        return matchesAddress(if (matchCase) url else lowerUrl)
    }

    /** Whether the address matches the pattern, honouring the anchors. */
    fun matchesAddress(url: String): Boolean {
        if (hostAnchored) {
            val hostStart = url.indexOf("://").let { if (it < 0) return false else it + 3 }
            val hostEnd = hostEndOf(url, hostStart)
            var start = hostStart
            while (start < hostEnd) {
                if (matchAt(url, start)) return true
                val dot = url.indexOf('.', start)
                if (dot < 0 || dot >= hostEnd) return false
                start = dot + 1
            }
            return false
        }
        if (startAnchored) return matchAt(url, 0)
        if (pattern.isEmpty()) return true
        // Unanchored: try every place the pattern's first plain character appears.
        val first = pattern[0]
        if (first == '*' || first == '^') {
            for (start in 0..url.length) if (matchAt(url, start)) return true
            return false
        }
        var start = url.indexOf(first)
        while (start >= 0) {
            if (matchAt(url, start)) return true
            start = url.indexOf(first, start + 1)
        }
        return false
    }

    private fun matchAt(url: String, start: Int): Boolean = matchFrom(url, 0, start)

    private fun matchFrom(url: String, patternIndex: Int, urlIndex: Int): Boolean {
        var i = patternIndex
        var j = urlIndex
        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '*' -> {
                    var next = i
                    while (next < pattern.length && pattern[next] == '*') next++
                    if (next == pattern.length) return true
                    for (from in j..url.length) if (matchFrom(url, next, from)) return true
                    return false
                }
                '^' -> {
                    if (j == url.length) {
                        i++ // a separator may also be the end of the address
                        continue
                    }
                    if (!isSeparator(url[j])) return false
                    i++
                    j++
                }
                else -> {
                    if (j >= url.length || url[j] != c) return false
                    i++
                    j++
                }
            }
        }
        return !endAnchored || j == url.length
    }

    companion object {
        fun isSeparator(c: Char): Boolean =
            !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

        /** Where the host part of [url] ends: at its path, query, fragment or port. */
        fun hostEndOf(url: String, hostStart: Int): Int {
            var i = hostStart
            while (i < url.length) {
                val c = url[i]
                if (c == '/' || c == '?' || c == '#' || c == ':') return i
                i++
            }
            return i
        }
    }
}

/**
 * Which pages a rule applies on (`domain=a.com|~b.a.com`). A name ending in `.*` stands for that name under any
 * ending (`example.*` for example.com, example.co.uk...).
 */
internal class DomainConstraint(private val include: Array<String>, private val exclude: Array<String>) {
    fun allows(host: String, site: String): Boolean {
        if (exclude.any { covers(it, host, site) }) return false
        return include.isEmpty() || include.any { covers(it, host, site) }
    }

    companion object {
        fun covers(domain: String, host: String, site: String): Boolean {
            if (domain.endsWith(".*")) return site.substringBefore('.') == domain.dropLast(2)
            return host == domain || host.endsWith(".$domain")
        }

        /** Parses `a.com|~b.a.com` (a `domain=` option) or `a.com,~b.a.com` (a cosmetic rule's sites). */
        fun parse(value: String, separator: Char): DomainConstraint? {
            val include = ArrayList<String>()
            val exclude = ArrayList<String>()
            for (raw in value.split(separator)) {
                val name = raw.trim().lowercase()
                if (name.isEmpty()) continue
                if (name.startsWith("~")) exclude += name.substring(1) else include += name
            }
            if (include.isEmpty() && exclude.isEmpty()) return null
            return DomainConstraint(include.toTypedArray(), exclude.toTypedArray())
        }
    }
}

/**
 * The page a request comes from, worked out once per page: its host, its site (the name registered for it,
 * such as example.co.uk), and what the lists' page-wide exceptions say about it.
 */
class PageContext internal constructor(
    val url: String,
    val host: String,
    val site: String,
    /** A `$document` exception: nothing on this page is blocked or hidden. */
    val allowAll: Boolean,
    /** An `$elemhide` exception: nothing on this page is hidden. */
    val noHiding: Boolean,
    /** A `$generichide` exception: only rules written for this site hide anything. */
    val noGenericHiding: Boolean,
)

/** The name a host is registered under (www.example.co.uk → example.co.uk); first and third party compare these. */
fun interface DomainResolver {
    fun siteOf(host: String): String
}
