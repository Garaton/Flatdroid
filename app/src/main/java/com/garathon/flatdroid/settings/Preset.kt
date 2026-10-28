package com.garathon.flatdroid.settings

/**
 * A named preset of a settings bundle [T]: a label and a hint for its dropdown, and the [settings]
 * it applies. Current settings matching no preset are shown as "Custom". [SecurityLevel] is one
 * such preset over [SecuritySettings]; another bundle (e.g. a display/audio quality preset) can be
 * a second by implementing this over its own type, with the same dropdown and "Custom" handling.
 */
interface Preset<T> {
    val labelRes: Int
    val hintRes: Int
    val settings: T
}

/** The preset in this list whose [Preset.settings] equal [settings], or null (Custom) when none do. */
fun <T, P : Preset<T>> Iterable<P>.presetFor(settings: T): P? = firstOrNull { it.settings == settings }
