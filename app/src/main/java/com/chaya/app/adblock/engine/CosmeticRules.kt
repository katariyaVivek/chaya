package com.chaya.app.adblock.engine

/**
 * Element-hiding rules (`example.com##.ad-box`, `##.ad-banner`, exceptions `example.com#@#.ad-banner`), turned
 * into CSS that sets `display: none` on what they select.
 */
internal class CosmeticRules private constructor(
    /** Rules for particular sites, by each site they name. */
    private val bySite: HashMap<String, MutableList<SiteRule>>,
    /** Rules for every site that start with a class or id, by that `.class` or `#id`. */
    private val genericByToken: HashMap<String, MutableList<String>>,
    /** Rules for every site that cannot be looked up by a class or id (`a[href^="https://ads."]`...). */
    private val genericAlways: List<String>,
    /** Rules for every site except some (`~example.com##.ad`). */
    private val genericExcept: List<SiteRule>,
    /** Exceptions for particular sites: selectors not hidden there, by site. */
    private val exceptionsBySite: HashMap<String, MutableSet<String>>,
) {
    class SiteRule(val selector: String, val except: Array<String>)

    fun pageCss(page: PageContext): String {
        val excepted = exceptionsFor(page)
        val out = StringBuilder()
        for (suffix in suffixesOf(page.host)) {
            bySite[suffix]?.forEach { rule ->
                if (rule.except.none { DomainConstraint.covers(it, page.host, page.site) }) out.appendHidden(rule.selector, excepted)
            }
        }
        // Rules for `example.*` are filed under the site's first label followed by ".*".
        bySite[page.site.substringBefore('.') + ".*"]?.forEach { rule ->
            if (rule.except.none { DomainConstraint.covers(it, page.host, page.site) }) out.appendHidden(rule.selector, excepted)
        }
        if (!page.noGenericHiding) {
            genericAlways.forEach { out.appendHidden(it, excepted) }
            genericExcept.forEach { rule ->
                if (rule.except.none { DomainConstraint.covers(it, page.host, page.site) }) out.appendHidden(rule.selector, excepted)
            }
        }
        return out.toString()
    }

    fun genericCss(page: PageContext, tokens: Collection<String>): String {
        val excepted = exceptionsFor(page)
        val out = StringBuilder()
        var looked = 0
        for (token in tokens) {
            if (++looked > MAX_TOKENS) break
            genericByToken[token]?.forEach { out.appendHidden(it, excepted) }
        }
        return out.toString()
    }

    private fun exceptionsFor(page: PageContext): Set<String> {
        val found = (suffixesOf(page.host) + (page.site.substringBefore('.') + ".*")).mapNotNull { exceptionsBySite[it] }
        return when (found.size) {
            0 -> emptySet()
            1 -> found[0]
            else -> found.flatMapTo(HashSet()) { it }
        }
    }

    private fun StringBuilder.appendHidden(selector: String, excepted: Set<String>) {
        if (selector in excepted) return
        // One rule per selector: a selector this browser cannot read then loses only its own rule.
        append(selector).append("{display:none!important}\n")
    }

    class Builder {
        private val bySite = HashMap<String, MutableList<SiteRule>>()
        private val genericByToken = HashMap<String, MutableList<String>>()
        private val genericAlways = ArrayList<String>()
        private val genericExcept = ArrayList<SiteRule>()
        private val exceptionsBySite = HashMap<String, MutableSet<String>>()
        private val genericExceptions = HashSet<String>()

        /** Adds a rule; false when it is one this engine does not apply. [separator] is `##` or `#@#`. */
        fun add(line: String, separator: String): Boolean {
            val at = line.indexOf(separator)
            val sites = line.substring(0, at)
            val selector = line.substring(at + separator.length).trim()
            if (!isPlainSelector(selector)) return false
            val constraint = if (sites.isBlank()) null else DomainConstraint.parse(sites, ',')
            val include = sites.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("~") }
            val except = sites.split(',').map { it.trim().lowercase() }.filter { it.startsWith("~") }
                .map { it.substring(1) }.toTypedArray()

            if (separator == "#@#") {
                if (include.isEmpty()) genericExceptions += selector
                else include.forEach { exceptionsBySite.getOrPut(it) { HashSet() } += selector }
                return true
            }
            when {
                include.isNotEmpty() -> include.forEach { site ->
                    bySite.getOrPut(site) { ArrayList(2) } += SiteRule(selector, except)
                }
                constraint != null -> genericExcept += SiteRule(selector, except)
                else -> {
                    val token = leadingToken(selector)
                    if (token == null) genericAlways += selector
                    else genericByToken.getOrPut(token) { ArrayList(1) } += selector
                }
            }
            return true
        }

        fun build(): CosmeticRules {
            if (genericExceptions.isNotEmpty()) {
                genericAlways.removeAll(genericExceptions)
                genericExcept.removeAll { it.selector in genericExceptions }
                genericByToken.values.forEach { it.removeAll(genericExceptions) }
            }
            return CosmeticRules(bySite, genericByToken, genericAlways, genericExcept, exceptionsBySite)
        }
    }

    companion object {
        /** A page asking about more names than this at once gets answers for the first ones only. */
        const val MAX_TOKENS = 4000

        /** The cosmetic separator in [line] (`##` or `#@#`), or null when it is not an element-hiding rule. */
        fun separatorIn(line: String): String? {
            val hash = line.indexOf('#')
            if (hash < 0) return null
            return when {
                line.startsWith("#@#", hash) -> "#@#"
                line.startsWith("##", hash) -> "##"
                else -> null
            }
        }

        /** Words that make a selector something CSS cannot apply alone (uBlock's procedural selectors). */
        private val PROCEDURAL = listOf(
            ":-abp-", ":has-text(", ":xpath(", ":matches-css", ":style(", ":remove(", ":upward(",
            ":min-text-length(", ":matches-path(", ":watch-attr(", ":others(", ":if(", ":if-not(",
            ":matches-attr(", ":matches-prop(", ":nth-ancestor(", ":remove-attr(", ":remove-class(",
            ":contains(", ":matches-media(", ":shadow(",
        )

        /**
         * A selector CSS can apply as it is. Scriptlets (`+js(...)`), HTML filters (`^...`) and procedural
         * selectors are not; neither is anything that could end the rule early and add CSS of its own.
         */
        fun isPlainSelector(selector: String): Boolean {
            if (selector.isEmpty() || selector.startsWith("+js(") || selector.startsWith("^")) return false
            if (selector.any { it == '{' || it == '}' || it == '<' || it == '\n' || it == '\r' }) return false
            if (selector.contains("/*") || selector.contains("\\")) return false
            return PROCEDURAL.none { selector.contains(it) }
        }

        /** `.ad-box` for `.ad-box > span`, `#banner` for `#banner.wide`; null when the selector starts otherwise. */
        fun leadingToken(selector: String): String? {
            if (selector.length < 2 || (selector[0] != '.' && selector[0] != '#')) return null
            var end = 1
            while (end < selector.length && (selector[end].isLetterOrDigit() || selector[end] == '-' || selector[end] == '_')) end++
            if (end == 1) return null
            return selector.substring(0, end)
        }

        /** The host and each name it is under: a.b.example.com, b.example.com, example.com, com. */
        fun suffixesOf(host: String): List<String> {
            val out = ArrayList<String>(4)
            var suffix = host
            while (suffix.isNotEmpty()) {
                out += suffix
                val dot = suffix.indexOf('.')
                if (dot < 0) break
                suffix = suffix.substring(dot + 1)
            }
            return out
        }
    }
}
