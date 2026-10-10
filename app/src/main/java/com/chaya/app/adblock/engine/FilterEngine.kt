package com.chaya.app.adblock.engine

/**
 * Decides what to block and hide, from ad lists in the Adblock Plus format that EasyList and EasyPrivacy use
 * (the lists uBlock Origin starts with).
 *
 * Network rules block requests: most are a bare host (`||ads.example.com^`), kept in a set and looked up by the
 * request's host and its parents; the rest are indexed by one word of their pattern and tried only on
 * addresses containing that word. Cosmetic rules hide page elements: rules for a site are handed over whole,
 * rules for every site (thousands of `.ad-banner`-like selectors) only for the classes and ids a page actually
 * has.
 *
 * Not supported, so skipped rather than half-applied: regular-expression rules, scriptlets (`##+js`),
 * procedural selectors (`:has-text`...), and options that rewrite rather than block (`csp`, `removeparam`...).
 */
class FilterEngine private constructor(
    private val resolver: DomainResolver,
    private val blockedHosts: HashSet<String>,
    private val allowedHosts: HashSet<String>,
    private val hostFilters: HashMap<String, MutableList<NetworkFilter>>,
    private val hostExceptions: HashMap<String, MutableList<NetworkFilter>>,
    private val tokenFilters: HashMap<String, MutableList<NetworkFilter>>,
    private val tokenExceptions: HashMap<String, MutableList<NetworkFilter>>,
    private val untokenizedFilters: List<NetworkFilter>,
    private val untokenizedExceptions: List<NetworkFilter>,
    private val popupFilters: List<NetworkFilter>,
    private val popupExceptions: List<NetworkFilter>,
    private val pageExceptions: List<Pair<NetworkFilter, Int>>,
    private val cosmetics: CosmeticRules,
    /** Rules in use, for the settings screen. */
    val ruleCount: Int,
) {

    /** What the lists say about the page at [url]. */
    fun page(url: String): PageContext {
        val lower = url.lowercase()
        val host = hostOf(lower)
        val site = if (host.isEmpty()) "" else resolver.siteOf(host)
        var flags = 0
        val placeholder = PageContext(url, host, site, allowAll = false, noHiding = false, noGenericHiding = false)
        for ((filter, flag) in pageExceptions) {
            if (flags and flag == flag) continue
            if (filter.domains != null && !filter.domains.allows(host, site)) continue
            if (filter.matchesAddress(if (filter.matchCase) url else lower)) flags = flags or flag
        }
        if (flags == 0) return placeholder
        return PageContext(
            url, host, site,
            allowAll = flags and PAGE_DOCUMENT != 0,
            noHiding = flags and (PAGE_DOCUMENT or PAGE_ELEMHIDE) != 0,
            noGenericHiding = flags and PAGE_GENERICHIDE != 0,
        )
    }

    /** Whether a request for [url], of kind [type] (see [RequestType]), made by [page] should be blocked. */
    fun shouldBlock(url: String, type: Int, page: PageContext): Boolean {
        if (page.allowAll) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val host = hostOf(lower)
        if (host.isEmpty()) return false
        var site: String? = null
        val requestSite = { site ?: resolver.siteOf(host).also { site = it } }

        val blocking = findMatch(url, lower, host, type, page, requestSite, blockedHosts, hostFilters,
            tokenFilters, untokenizedFilters) ?: return false
        if (blocking === IMPORTANT) return true
        val excepted = findMatch(url, lower, host, type, page, requestSite, allowedHosts, hostExceptions,
            tokenExceptions, untokenizedExceptions) != null
        return !excepted
    }

    /** Whether a new window opening [url] from [page] is an ad pop-up. */
    fun shouldBlockPopup(url: String, page: PageContext): Boolean {
        if (page.allowAll) return false
        val lower = url.lowercase()
        val host = hostOf(lower)
        if (host.isEmpty()) return false
        val requestSite = { resolver.siteOf(host) }
        val blocking = popupFilters.firstOrNull { it.matchesPopup(url, lower, page, requestSite) } ?: return false
        if (blocking.important) return true
        return popupExceptions.none { it.matchesPopup(url, lower, page, requestSite) }
    }

    private fun NetworkFilter.matchesPopup(url: String, lower: String, page: PageContext, site: () -> String) =
        (domains == null || domains.allows(page.host, page.site)) &&
            (thirdParty == null || thirdParty == (site() != page.site)) &&
            matchesAddress(if (matchCase) url else lower)

    /**
     * CSS hiding what the lists hide on [page] whatever it contains: rules for its site and the few rules for
     * every site that cannot be looked up by a class or id. Empty when nothing is hidden there.
     */
    fun pageCss(page: PageContext): String =
        if (page.allowAll || page.noHiding) "" else cosmetics.pageCss(page)

    /** CSS hiding the rules for every site that start with one of [tokens] (`.class` or `#id` names on the page). */
    fun genericCss(page: PageContext, tokens: Collection<String>): String =
        if (page.allowAll || page.noHiding || page.noGenericHiding) "" else cosmetics.genericCss(page, tokens)

    /** Whether rules for every site apply on [page], so the page script should report its classes and ids. */
    fun hidesGenerically(page: PageContext): Boolean = !(page.allowAll || page.noHiding || page.noGenericHiding)

    private fun findMatch(
        url: String,
        lower: String,
        host: String,
        type: Int,
        page: PageContext,
        requestSite: () -> String,
        hosts: HashSet<String>,
        byHost: HashMap<String, MutableList<NetworkFilter>>,
        byToken: HashMap<String, MutableList<NetworkFilter>>,
        untokenized: List<NetworkFilter>,
    ): Any? {
        var found: Any? = null
        // A bare `||host^` rule applies to the host and everything under it.
        var suffix = host
        while (true) {
            if (suffix in hosts) found = found ?: PLAIN
            byHost[suffix]?.let { list ->
                for (f in list) if (f.matchesKind(type, page, requestSite)) {
                    if (f.important) return IMPORTANT
                    found = found ?: PLAIN
                }
            }
            val dot = suffix.indexOf('.')
            if (dot < 0) break
            suffix = suffix.substring(dot + 1)
        }
        var start = -1
        for (i in 0..lower.length) {
            val word = i < lower.length && isTokenChar(lower[i])
            if (word && start < 0) start = i
            if (!word && start >= 0) {
                byToken[lower.substring(start, i)]?.let { list ->
                    for (f in list) if (f.matches(url, lower, type, page, requestSite)) {
                        if (f.important) return IMPORTANT
                        found = found ?: PLAIN
                    }
                }
                start = -1
            }
        }
        for (f in untokenized) if (f.matches(url, lower, type, page, requestSite)) {
            if (f.important) return IMPORTANT
            found = found ?: PLAIN
        }
        return found
    }

    /** For a `||host^` rule, whose address part is already known to match: do its options allow it here? */
    private fun NetworkFilter.matchesKind(type: Int, page: PageContext, requestSite: () -> String): Boolean {
        if (anyType) {
            if (type != RequestType.UNKNOWN && types and type == 0) return false
        } else if (type == RequestType.UNKNOWN || types and type == 0) {
            return false
        }
        if (domains != null && !domains.allows(page.host, page.site)) return false
        if (thirdParty != null && thirdParty != (requestSite() != page.site)) return false
        return true
    }

    /** Collects rules line by line, then builds the engine. Not thread-safe; build once, then share the engine. */
    class Builder(private val resolver: DomainResolver) {
        private val blockedHosts = HashSet<String>()
        private val allowedHosts = HashSet<String>()
        private val hostFilters = HashMap<String, MutableList<NetworkFilter>>()
        private val hostExceptions = HashMap<String, MutableList<NetworkFilter>>()
        private val tokenFilters = HashMap<String, MutableList<NetworkFilter>>()
        private val tokenExceptions = HashMap<String, MutableList<NetworkFilter>>()
        private val untokenizedFilters = ArrayList<NetworkFilter>()
        private val untokenizedExceptions = ArrayList<NetworkFilter>()
        private val popupFilters = ArrayList<NetworkFilter>()
        private val popupExceptions = ArrayList<NetworkFilter>()
        private val pageExceptions = ArrayList<Pair<NetworkFilter, Int>>()
        private val cosmetics = CosmeticRules.Builder()
        private var count = 0

        /** Adds one line of a list; comments, blank lines and rules this engine does not support are skipped. */
        fun add(rawLine: String) {
            val line = rawLine.trim()
            if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
            val cosmetic = CosmeticRules.separatorIn(line)
            if (cosmetic != null) {
                if (cosmetics.add(line, cosmetic)) count++
                return
            }
            // Procedural, scriptlet and CSS-injecting rules (`#?#`, `#$#`, `#%#`, `#@?#`...) are not applied.
            if (line.contains('#') && OTHER_COSMETIC.containsMatchIn(line)) return
            if (addNetwork(line)) count++
        }

        fun addAll(lines: Sequence<String>) = lines.forEach(::add)

        fun build(): FilterEngine = FilterEngine(
            resolver, blockedHosts, allowedHosts, hostFilters, hostExceptions, tokenFilters, tokenExceptions,
            untokenizedFilters, untokenizedExceptions, popupFilters, popupExceptions, pageExceptions,
            cosmetics.build(), count,
        )

        private fun addNetwork(line: String): Boolean {
            val exception = line.startsWith("@@")
            var body = if (exception) line.substring(2) else line
            var options = ""
            val dollar = body.lastIndexOf('$')
            if (dollar >= 0 && !(body.startsWith("/") && body.endsWith("/"))) {
                options = body.substring(dollar + 1)
                body = body.substring(0, dollar)
            }
            if (body.length >= 2 && body.startsWith("/") && body.endsWith("/")) return false // regular expression

            var positive = 0
            var negative = 0
            var thirdParty: Boolean? = null
            var domains: DomainConstraint? = null
            var important = false
            var matchCase = false
            var popup = false
            var document = false
            var pageFlags = 0
            if (options.isNotEmpty()) {
                for (rawOption in options.split(',')) {
                    val option = rawOption.trim().lowercase()
                    if (option.isEmpty()) continue
                    val negated = option.startsWith("~")
                    val name = (if (negated) option.substring(1) else option).substringBefore('=')
                    val value = option.substringAfter('=', "")
                    val kind = RequestType.ofOption(name)
                    when {
                        kind != null -> if (negated) negative = negative or kind else positive = positive or kind
                        name == "third-party" || name == "3p" -> thirdParty = !negated
                        name == "first-party" || name == "1p" -> thirdParty = negated
                        name == "domain" || name == "from" -> domains = DomainConstraint.parse(value, '|')
                        name == "important" -> important = true
                        name == "match-case" -> matchCase = true
                        name == "popup" -> popup = true
                        name == "document" || name == "doc" -> if (exception) pageFlags = pageFlags or PAGE_DOCUMENT else document = true
                        name == "elemhide" || name == "ehide" -> if (exception) pageFlags = pageFlags or PAGE_ELEMHIDE else return false
                        name == "generichide" || name == "ghide" -> if (exception) pageFlags = pageFlags or PAGE_GENERICHIDE else return false
                        // A blocking rule that also hands the page a harmless stand-in: blocking alone is close enough.
                        name == "redirect" || name == "empty" || name == "mp4" -> if (exception) return false
                        name == "all" -> Unit
                        else -> return false // rewrites, redirect-rule, csp, removeparam, denyallow, badfilter...
                    }
                }
            }

            var pattern = body
            var hostAnchored = false
            var startAnchored = false
            var endAnchored = false
            if (pattern.startsWith("||")) {
                hostAnchored = true
                pattern = pattern.substring(2)
            } else if (pattern.startsWith("|")) {
                startAnchored = true
                pattern = pattern.substring(1)
            }
            if (pattern.endsWith("|")) {
                endAnchored = true
                pattern = pattern.dropLast(1)
            }
            while (pattern.startsWith("*") && !hostAnchored && !startAnchored) pattern = pattern.substring(1)
            while (pattern.endsWith("*") && !endAnchored) pattern = pattern.dropLast(1)
            if (!matchCase) pattern = pattern.lowercase()
            // A rule matching everything with nothing to narrow it would block the web; lists never mean that.
            if (pattern.isEmpty() && domains == null && !popup && pageFlags == 0) return false

            val anyType = positive == 0 && negative == 0
            val types = when {
                positive != 0 -> positive
                negative != 0 -> RequestType.ALL and negative.inv()
                else -> RequestType.ALL
            }
            val filter = NetworkFilter(
                pattern, hostAnchored, startAnchored, endAnchored, matchCase, types, anyType, thirdParty, domains,
                important,
            )

            if (pageFlags != 0) {
                pageExceptions += filter to pageFlags
                return true
            }
            if (popup) {
                (if (exception) popupExceptions else popupFilters) += filter
                if (anyType) return true // only for pop-ups
            }
            if (document && anyType) return true // whole pages are never blocked, only what they load

            val bareHost = hostAnchored && !endAnchored && pattern.endsWith("^") &&
                pattern.dropLast(1).let { h -> h.isNotEmpty() && h.all { it.isLetterOrDigit() || it == '.' || it == '-' } }
            if (bareHost) {
                val host = pattern.dropLast(1)
                val plain = anyType && thirdParty == null && domains == null && !important
                if (plain) {
                    (if (exception) allowedHosts else blockedHosts) += host
                } else {
                    (if (exception) hostExceptions else hostFilters).getOrPut(host) { ArrayList(1) } += filter
                }
                return true
            }
            val token = bestToken(pattern, hostAnchored || startAnchored, endAnchored)
            if (token == null) {
                (if (exception) untokenizedExceptions else untokenizedFilters) += filter
            } else {
                (if (exception) tokenExceptions else tokenFilters).getOrPut(token) { ArrayList(1) } += filter
            }
            return true
        }
    }

    internal companion object {
        const val PAGE_DOCUMENT = 1
        const val PAGE_ELEMHIDE = 2
        const val PAGE_GENERICHIDE = 4
        private val OTHER_COSMETIC = Regex("#@?[?$%]#")
        private val PLAIN = Any()
        private val IMPORTANT = Any()

        /** Words in nearly every address; a rule indexed by one of them would be tried on almost every request. */
        private val COMMON_WORDS = setOf("http", "https", "www", "com", "net", "org", "js", "html", "php", "cdn")

        fun isTokenChar(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9' || c == '%'

        /**
         * The word of [pattern] to index the rule by: a run of letters and digits that is a whole word in any
         * address the rule matches, so it is bounded by separators or anchors and never by a `*`. The longest
         * uncommon one, or null when there is none and the rule must be tried on every request.
         */
        fun bestToken(pattern: String, anchoredStart: Boolean, anchoredEnd: Boolean): String? {
            val lower = pattern.lowercase()
            var best: String? = null
            var bestScore = 0
            var start = -1
            for (i in 0..lower.length) {
                val word = i < lower.length && isTokenChar(lower[i])
                if (word && start < 0) start = i
                if (!word && start >= 0) {
                    val leftBounded = if (start == 0) anchoredStart else lower[start - 1] != '*'
                    val rightBounded = if (i == lower.length) anchoredEnd else lower[i] != '*'
                    if (leftBounded && rightBounded && i - start >= 2) {
                        val candidate = lower.substring(start, i)
                        val score = (i - start) + if (candidate in COMMON_WORDS) 0 else 100
                        if (score > bestScore) {
                            best = candidate
                            bestScore = score
                        }
                    }
                    start = -1
                }
            }
            return best
        }

        /** The host of an address, lower case, without user, port or brackets; empty when there is none. */
        fun hostOf(url: String): String {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return ""
            val authority = schemeEnd + 3
            var pathStart = authority
            while (pathStart < url.length && url[pathStart] != '/' && url[pathStart] != '?' && url[pathStart] != '#') {
                pathStart++
            }
            // user:password@host — the host starts after the last @ before the path.
            val at = url.lastIndexOf('@', pathStart - 1)
            val start = if (at >= authority) at + 1 else authority
            var end = start
            while (end < pathStart && url[end] != ':') end++
            return url.substring(start, end).lowercase().trimEnd('.')
        }
    }
}
