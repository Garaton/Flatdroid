package com.garathon.flatdroid.flatpak

import java.net.URI

/**
 * Links that name a Flathub app to install: Flathub's own install page
 * (`https://flathub.org/en/apps/org.xfce.mousepad/install`, with or without the language part, or
 * the app's page) and the `appstream://` form software centers use. Only https links to
 * flathub.org count, and only an ID that is a valid Flatpak app ID comes out, so whatever opened
 * the link can't make Flatdroid install anything else than a Flathub app, and even that waits for
 * the user's confirmation.
 */
object FlathubLink {
    private val HOSTS = setOf("flathub.org", "www.flathub.org")
    private val LOCALE = Regex("[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8})?")

    /** The app ID [link] names, or null when it isn't a Flathub app link. */
    fun appId(link: String?): String? {
        val uri = link?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LINK } ?: return null
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
        val id = when (parsed.scheme?.lowercase()) {
            "https" -> fromWebLink(parsed)
            "appstream" -> fromAppstream(parsed)
            else -> null
        }
        return id?.takeIf(InstalledApps::isValidId)
    }

    private fun fromWebLink(uri: URI): String? {
        if (uri.host?.lowercase() !in HOSTS || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }.toMutableList()
        // An optional language part first: /en/apps/…
        if (segments.size >= 2 && segments[0] != "apps" && LOCALE.matches(segments[0])) segments.removeAt(0)
        if (segments.size !in 2..3 || segments[0] != "apps") return null
        if (segments.size == 3 && segments[2] != "install") return null
        return segments[1]
    }

    /** `appstream://org.xfce.mousepad`, `appstream:org.xfce.mousepad` and the same with `.desktop`. */
    private fun fromAppstream(uri: URI): String? {
        val raw = (uri.host ?: uri.schemeSpecificPart?.removePrefix("//")?.substringBefore('/')) ?: return null
        return raw.removeSuffix(".desktop").takeIf { it.isNotEmpty() }
    }

    private const val MAX_LINK = 512
}
