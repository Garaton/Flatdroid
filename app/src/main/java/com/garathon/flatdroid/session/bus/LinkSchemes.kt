package com.garathon.flatdroid.session.bus

/**
 * Which addresses an app may hand to Android: any scheme but those that reach Android's own files
 * and apps, or run script ([REFUSED]). Files go through `OpenFile` instead, as a descriptor.
 */
object LinkSchemes {
    val REFUSED = setOf("file", "content", "intent", "android-app", "android.resource", "javascript", "data")
    const val MAX_LENGTH = 8192

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /** [uri]'s scheme in lower case, or null when it has none. */
    fun scheme(uri: String): String? = SCHEME.find(uri)?.groupValues?.get(1)?.lowercase()

    fun allowedScheme(scheme: String): Boolean = scheme.matches(Regex("[A-Za-z][A-Za-z0-9+.-]*")) && scheme.lowercase() !in REFUSED

    /** Whether [uri] may be opened: a short address with an allowed scheme and no spaces or control characters. */
    fun allowed(uri: String): Boolean =
        uri.length <= MAX_LENGTH && uri.none { it.isWhitespace() || it.isISOControl() } && scheme(uri)?.let(::allowedScheme) == true
}
