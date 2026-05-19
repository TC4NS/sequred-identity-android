package com.sequred.identity.autofill

import com.sequred.identity.data.VaultEntry

/**
 * Translates the requesting app's package name + WEB_DOMAIN into a candidate
 * vault entry. A small built-in lookup handles the well-known cases where
 * the package name doesn't trivially reveal the service domain (Spotify,
 * Slack, X/Twitter, etc.). For everything else we fall back to:
 *
 *   1. WEB_DOMAIN hint from AssistStructure (best signal).
 *   2. Reversed package name → host (com.example.app → example.com).
 */
object AutofillMatcher {

    /**
     * Common Android packages mapped to the canonical site name as the user
     * would have stored it in their vault. Kept small + curated so it
     * matches what people actually save. Add more as we ship.
     */
    private val PACKAGE_TO_SITE: Map<String, String> = mapOf(
        "com.spotify.music"            to "spotify.com",
        "com.spotify.lite"             to "spotify.com",
        "com.netflix.mediaclient"      to "netflix.com",
        "com.netflix.NGP.GamesIdentity" to "netflix.com",
        "com.amazon.mShop.android.shopping" to "amazon.com",
        "in.amazon.mShop.android.shopping"  to "amazon.in",
        "com.amazon.kindle"            to "amazon.com",
        "com.amazon.dee.app"           to "amazon.com",
        "com.discord"                  to "discord.com",
        "com.slack"                    to "slack.com",
        "com.Slack"                    to "slack.com",
        "com.twitter.android"          to "x.com",
        "com.x.android"                to "x.com",
        "com.facebook.katana"          to "facebook.com",
        "com.facebook.lite"            to "facebook.com",
        "com.instagram.android"        to "instagram.com",
        "com.linkedin.android"         to "linkedin.com",
        "com.snapchat.android"         to "snapchat.com",
        "com.tinder"                   to "tinder.com",
        "com.zhiliaoapp.musically"     to "tiktok.com",
        "com.ss.android.ugc.trill"     to "tiktok.com",
        "com.reddit.frontpage"         to "reddit.com",
        "com.pinterest"                to "pinterest.com",
        "com.dropbox.android"          to "dropbox.com",
        "com.google.android.apps.docs" to "google.com",
        "com.google.android.gm"        to "google.com",
        "com.microsoft.office.outlook" to "outlook.com",
        "com.microsoft.teams"          to "microsoft.com",
        "com.microsoft.office.officehubrow" to "microsoft.com",
        "us.zoom.videomeetings"        to "zoom.us",
        "com.github.android"           to "github.com",
        "com.atlassian.android.jira.core" to "atlassian.com",
        "org.thoughtcrime.securesms"   to "signal.org",
        "org.telegram.messenger"       to "telegram.org",
        "com.protonvpn.android"        to "protonvpn.com",
        "ch.protonmail.android"        to "proton.me",
        "me.proton.android.pass"       to "proton.me",
        "com.bitwarden.android"        to "bitwarden.com",
        "com.coinbase.android"         to "coinbase.com",
        "com.binance.dev"              to "binance.com",
        "com.robinhood.android"        to "robinhood.com",
        "com.paypal.android.p2pmobile" to "paypal.com",
        "com.venmo"                    to "venmo.com",
        "com.squareup.cash"            to "cash.app",
        "com.chase.sig.android"        to "chase.com",
        "com.zellepay.zelle"           to "zellepay.com",
        "com.airbnb.android"           to "airbnb.com",
        "com.ubercab"                  to "uber.com",
        "com.lyft.android"             to "lyft.com",
        "com.doordash.consumer"        to "doordash.com",
        "com.ubercab.eats"             to "ubereats.com",
        "com.application.zomato"       to "zomato.com",
        "com.starbucks.mobilecard"     to "starbucks.com",
    )

    data class Candidate(val site: String, val source: Source)
    enum class Source { PackageMap, WebDomain, PackageReverse }

    /** Best-guess canonical site for the request. May return multiple. */
    fun candidates(packageName: String?, webDomain: String?): List<Candidate> {
        val out = mutableListOf<Candidate>()
        webDomain?.let {
            val clean = stripWww(it).lowercase()
            if (clean.isNotBlank()) out += Candidate(clean, Source.WebDomain)
        }
        packageName?.let { pkg ->
            PACKAGE_TO_SITE[pkg]?.let { out += Candidate(it, Source.PackageMap) }
            // Reversed-package fallback. com.spotify.music → spotify.com
            // (drops leading com/org/net/io/me/etc and trailing app-style
            // suffixes like "android", "mobile", "app").
            packageNameToHost(pkg)?.let { out += Candidate(it, Source.PackageReverse) }
        }
        return out.distinctBy { it.site }
    }

    /** Filter the user's vault to entries whose site matches any candidate. */
    fun matchEntries(entries: List<VaultEntry>, candidates: List<Candidate>): List<VaultEntry> {
        if (candidates.isEmpty() || entries.isEmpty()) return emptyList()
        val sites = candidates.map { it.site.lowercase() }.toSet()
        return entries.filter { e ->
            val es = stripWww(e.site).lowercase()
            sites.any { c -> es == c || es.endsWith(".$c") || c.endsWith(".$es") }
        }
    }

    internal fun stripWww(host: String): String =
        host.removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").substringBefore('/').substringBefore('?').substringBefore('#')

    internal fun packageNameToHost(pkg: String): String? {
        val parts = pkg.split('.').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val tlds = setOf("com", "org", "net", "io", "me", "co", "dev", "app", "us", "uk", "in", "ai")
        // Strip a leading TLD-looking segment ("com.spotify.music" → "spotify.music").
        val body = if (parts[0].lowercase() in tlds) parts.drop(1) else parts
        if (body.isEmpty()) return null
        // Strip trailing app-style segments. ("spotify.music" → "spotify";
        // "github.android" → "github"; "com.bitwarden.android" → "bitwarden").
        val drop = setOf("android", "mobile", "app", "client", "lite", "free", "official")
        val core = body.takeWhile { it.lowercase() !in drop }.ifEmpty { listOf(body[0]) }
        if (core.size == 1) return "${core[0]}.com"
        return core.joinToString(".")
    }
}
