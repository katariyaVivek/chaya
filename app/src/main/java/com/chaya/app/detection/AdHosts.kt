package com.chaya.app.detection

import java.util.Locale

/**
 * Recognizes ad-serving hosts and ad paths so their video creatives rank
 * below page content. Deliberately curated rather than a full blocklist:
 * a false positive hides a real video, so only dedicated ad networks belong here.
 */
object AdHosts {

    /** Registrable domains that serve video ads; subdomains match too. */
    private val hosts = setOf(
        "doubleclick.net",
        "2mdn.net",
        "googlesyndication.com",
        "googleadservices.com",
        "imasdk.googleapis.com",
        "adnxs.com",
        "adsrvr.org",
        "amazon-adsystem.com",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "casalemedia.com",
        "indexww.com",
        "spotxchange.com",
        "spotx.tv",
        "springserve.com",
        "fwmrm.net",
        "freewheel.tv",
        "stickyadstv.com",
        "teads.tv",
        "outbrain.com",
        "taboola.com",
        "criteo.com",
        "criteo.net",
        "moatads.com",
        "adsafeprotected.com",
        "doubleverify.com",
        "innovid.com",
        "tremorhub.com",
        "vidazoo.com",
        "connatix.com",
        "primis.tech",
        "aniview.com",
        "lkqd.net",
        "bfmio.com",
        "serving-sys.com",
        "flashtalking.com",
        "adform.net",
        "smartadserver.com",
        "yieldmo.com",
        "sharethrough.com",
        "unrulymedia.com",
        "triplelift.com",
        "3lift.com",
        "media.net",
        "inmobi.com",
        "vungle.com",
        "applovin.com",
        "adcolony.com",
        "unityads.unity3d.com",
    )

    /** Path segments that only ad delivery uses. */
    private val adPathSegments = setOf(
        "ad", "ads", "adserver", "adunit", "adunits", "vast", "vpaid", "vmap",
        "preroll", "midroll", "postroll", "adcreative", "adcreatives", "ad_creatives",
    )

    /** True when the URL comes from a known ad network or an ad-only path. */
    fun isAdUrl(url: String): Boolean {
        val host = MediaUrlClassifier.hostOf(url) ?: return false
        if (hosts.any { host == it || host.endsWith(".$it") }) return true
        return MediaUrlClassifier.pathOf(url)
            .lowercase(Locale.ROOT)
            .split('/')
            .any { it in adPathSegments }
    }
}
