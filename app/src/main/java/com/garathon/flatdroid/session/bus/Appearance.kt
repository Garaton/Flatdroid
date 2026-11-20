package com.garathon.flatdroid.session.bus

/**
 * What the settings portal tells apps about the look Android asks for: dark or light,
 * the accent color ([accent], `0xRRGGBB`, null for none), high contrast and reduced motion.
 */
data class Appearance(
    val dark: Boolean,
    val accent: Int? = null,
    val highContrast: Boolean = false,
    val reducedMotion: Boolean = false,
) {
    /** The `org.freedesktop.appearance` namespace's keys and values. */
    fun values(): Map<String, Variant> = buildMap {
        put(Portal.COLOR_SCHEME, Variant("u", if (dark) Portal.PREFER_DARK else Portal.PREFER_LIGHT))
        accent?.let { put(Portal.ACCENT_COLOR, Variant("(ddd)", Struct(channel(it, 16), channel(it, 8), channel(it, 0)))) }
        put(Portal.CONTRAST, Variant("u", if (highContrast) 1u else 0u))
        put(Portal.REDUCED_MOTION, Variant("u", if (reducedMotion) 1u else 0u))
    }

    /** Every namespace served, for [Portal]'s settings. */
    fun settings(): Map<String, Map<String, Variant>> = mapOf(Portal.APPEARANCE to values())

    /** The keys whose value differs in [next], with their new value: one `SettingChanged` each. */
    fun changes(next: Appearance): List<Pair<String, Variant>> {
        val before = values()
        return next.values().filter { (key, value) -> before[key] != value }.toList()
    }

    private fun channel(rgb: Int, shift: Int): Double = ((rgb shr shift) and 0xFF) / 255.0
}
