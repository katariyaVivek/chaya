package com.chaya.app.adblock

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether ads are blocked, and the sites the person lets show ads. Kept on the phone only. */
class AdBlockSettings(private val prefs: SharedPreferences) {
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _allowedSites = MutableStateFlow(prefs.getStringSet(KEY_ALLOWED, null)?.toSet().orEmpty())
    /** Sites (example.com, never a full address) where ads are not blocked. */
    val allowedSites: StateFlow<Set<String>> = _allowedSites.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
    }

    fun setAllowed(site: String, allowed: Boolean) {
        if (site.isEmpty()) return
        val sites = if (allowed) _allowedSites.value + site else _allowedSites.value - site
        prefs.edit().putStringSet(KEY_ALLOWED, sites).apply()
        _allowedSites.value = sites
    }

    /** Whether ads are blocked on [site] right now. */
    fun blocksOn(site: String): Boolean = _enabled.value && site !in _allowedSites.value

    companion object {
        const val KEY_ENABLED = "adblock_enabled"
        const val KEY_ALLOWED = "adblock_allowed_sites"

        fun from(context: Context) =
            AdBlockSettings(context.getSharedPreferences("chaya_prefs", Context.MODE_PRIVATE))
    }
}
