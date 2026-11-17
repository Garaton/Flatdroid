package com.garathon.flatdroid.flatpak.flathub

import java.net.URI
import java.net.URISyntaxException

/**
 * The only hosts Flatdroid fetches catalog pictures from. The catalog is Flatpak's AppStream data,
 * deployed like every app, so an installed app can rewrite it: an address from it is untrusted, and
 * fetching one would send a request (with whatever an app put in it) to any server.
 */
object FlathubHosts {
    private val HOSTS = setOf("dl.flathub.org", "flathub.org")

    /** Whether [url] is https, on one of [HOSTS], on the default port and without a login in it. */
    fun allows(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) && uri.host?.lowercase() in HOSTS
    } catch (_: URISyntaxException) {
        false
    }
}
