package com.chaya.app.adblock.engine

/**
 * Scriptlet rules (`example.com##+js(set-constant, ads.enabled, false)`), the part of uBlock Origin's lists that runs a
 * small script at the start of a page: it turns off an ad's property, stops a pop-under's window.open, or takes the
 * ads out of a video player's data before the player reads it. Only scriptlets Chaya's own script carries
 * (`assets/adblock/chaya_scriptlets.js`) are kept, by their full names; the rest, and uBlock's `trusted-` ones, are
 * skipped.
 *
 * Exceptions: `example.com#@#+js(name, args)` turns that one off on the site, `example.com#@#+js()` all of them.
 */
internal class ScriptletRules private constructor(
    private val bySite: HashMap<String, MutableList<SiteScriptlet>>,
    private val generic: List<SiteScriptlet>,
    private val exceptionsBySite: HashMap<String, MutableSet<String>>,
    private val offBySite: Set<String>,
) {
    /** One scriptlet, the sites it is not for, and its key (name and arguments) for exceptions. */
    class SiteScriptlet(val call: List<String>, val except: Array<String>) {
        val key: String = call.joinToString("\u0000")
    }

    /** The scriptlets for [page], each its name then its arguments, once each, in the lists' order. */
    fun forPage(page: PageContext): List<List<String>> {
        val names = CosmeticRules.suffixesOf(page.host) + (page.site.substringBefore('.') + ".*")
        if (names.any { it in offBySite }) return emptyList()
        val excepted = names.mapNotNull { exceptionsBySite[it] }.flatMapTo(HashSet()) { it }
        val seen = HashSet<String>()
        val out = ArrayList<List<String>>()
        fun take(rule: SiteScriptlet) {
            if (rule.key in excepted || !seen.add(rule.key)) return
            if (rule.except.any { DomainConstraint.covers(it, page.host, page.site) }) return
            out += rule.call
        }
        generic.forEach(::take)
        for (name in names) bySite[name]?.forEach(::take)
        return out
    }

    class Builder {
        private val bySite = HashMap<String, MutableList<SiteScriptlet>>()
        private val generic = ArrayList<SiteScriptlet>()
        private val exceptionsBySite = HashMap<String, MutableSet<String>>()
        private val offBySite = HashSet<String>()

        /**
         * Adds a `##+js(...)` or `#@#+js(...)` rule ([separator] is `##` or `#@#`); false when it is not one, or names
         * a scriptlet Chaya does not carry.
         */
        fun add(line: String, separator: String): Boolean {
            val at = line.indexOf(separator)
            val body = line.substring(at + separator.length).trim()
            if (!body.startsWith("+js(") || !body.endsWith(")")) return false
            val sites = line.substring(0, at).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val include = sites.filter { !it.startsWith("~") }
            val except = sites.filter { it.startsWith("~") }.map { it.substring(1) }.toTypedArray()
            val args = parseArguments(body.substring(4, body.length - 1))

            if (separator == "#@#") {
                if (include.isEmpty()) return false // an exception everywhere is not something the lists rely on
                if (args.isEmpty()) {
                    offBySite += include
                    return true
                }
                val call = canonical(args) ?: return false
                include.forEach { exceptionsBySite.getOrPut(it) { HashSet() } += SiteScriptlet(call, emptyArray()).key }
                return true
            }
            val call = canonical(args) ?: return false
            val rule = SiteScriptlet(call, except)
            if (include.isEmpty()) generic += rule else include.forEach { bySite.getOrPut(it) { ArrayList(2) } += rule }
            return true
        }

        fun build() = ScriptletRules(bySite, generic, exceptionsBySite, offBySite)
    }

    companion object {
        /** Every scriptlet chaya_scriptlets.js carries, by each name the lists use for it. */
        val NAMES: Map<String, String> = buildMap {
            fun names(full: String, vararg aliases: String) {
                put(full, full)
                aliases.forEach { put(it, full) }
            }
            names("set-constant", "set")
            names("abort-on-property-read", "aopr")
            names("abort-on-property-write", "aopw")
            names("abort-current-script", "acs", "abort-current-inline-script", "acis")
            names("addEventListener-defuser", "aeld", "prevent-addEventListener")
            names("no-setTimeout-if", "nostif", "prevent-setTimeout", "setTimeout-defuser")
            names("no-setInterval-if", "nosiif", "prevent-setInterval", "setInterval-defuser")
            names("no-window-open-if", "nowoif", "prevent-window-open", "window.open-defuser")
            names("json-prune")
            names("json-prune-fetch-response", "jpfr")
            names("json-prune-xhr-response", "jpxr")
            names("no-fetch-if", "prevent-fetch")
            names("no-xhr-if", "prevent-xhr")
            names("nano-setTimeout-booster", "nano-stb")
            names("nano-setInterval-booster", "nano-sib")
            names("remove-attr", "ra")
            names("remove-class", "rc")
            names("remove-node-text", "rmnt")
            names("noeval-if", "prevent-eval-if", "noeval")
        }

        /** [args] with its first item, the scriptlet's name, made the full name; null when Chaya does not carry it. */
        private fun canonical(args: List<String>): List<String>? {
            val name = args.firstOrNull()?.removeSuffix(".js") ?: return null
            val full = NAMES[name] ?: return null
            return listOf(full) + args.drop(1)
        }

        /**
         * The arguments of `+js(...)`: split at commas (a `\,` is a comma inside one), trimmed, and unquoted when one
         * is wrapped whole in matching quotes.
         */
        fun parseArguments(text: String): List<String> {
            val out = ArrayList<String>()
            val current = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '\\' && i + 1 < text.length && text[i + 1] == ',' -> {
                        current.append(',')
                        i++
                    }
                    c == ',' -> {
                        out += unquote(current.toString().trim())
                        current.setLength(0)
                    }
                    else -> current.append(c)
                }
                i++
            }
            val last = current.toString().trim()
            if (last.isNotEmpty() || out.isNotEmpty()) out += unquote(last)
            return out
        }

        private fun unquote(arg: String): String {
            if (arg.length >= 2 && arg[0] == arg[arg.length - 1] && arg[0] in "'\"`") return arg.substring(1, arg.length - 1)
            return arg
        }
    }
}
