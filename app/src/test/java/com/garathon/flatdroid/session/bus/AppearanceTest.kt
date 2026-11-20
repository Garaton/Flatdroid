package com.garathon.flatdroid.session.bus

import com.garathon.flatdroid.settings.FlatpakTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test
    fun valuesAsThePortalNamesThem() {
        val values = Appearance(dark = true, accent = 0x3584E4, highContrast = true, reducedMotion = true).values()
        assertEquals(Variant("u", Portal.PREFER_DARK), values[Portal.COLOR_SCHEME])
        assertEquals(Variant("(ddd)", Struct(0x35 / 255.0, 0x84 / 255.0, 0xE4 / 255.0)), values[Portal.ACCENT_COLOR])
        assertEquals(Variant("u", 1u), values[Portal.CONTRAST])
        assertEquals(Variant("u", 1u), values[Portal.REDUCED_MOTION])

        val light = Appearance(dark = false).values()
        assertEquals(Variant("u", Portal.PREFER_LIGHT), light[Portal.COLOR_SCHEME])
        assertFalse(Portal.ACCENT_COLOR in light)
        assertEquals(Variant("u", 0u), light[Portal.CONTRAST])
        assertEquals(Variant("u", 0u), light[Portal.REDUCED_MOTION])
    }

    @Test
    fun theAccentSurvivesTheWire() {
        val settings = Appearance(dark = false, accent = 0xFF0000).settings()
        val reply = Message.call("a.b", "/", "a.b", "R").copy(serial = 1u, sender = ":1.1").reply("a{sa{sv}}", settings)
        val back = DBusWire.decode(DBusWire.encode(reply, 2u)).body[0]
        assertEquals(settings, back)
    }

    @Test
    fun onlyChangedKeysAreSent() {
        val before = Appearance(dark = false, accent = 0x112233)
        assertEquals(emptyList<Pair<String, Variant>>(), before.changes(before.copy()))
        assertEquals(listOf(Portal.COLOR_SCHEME to Variant("u", Portal.PREFER_DARK)), before.changes(before.copy(dark = true)))
        val many = before.changes(before.copy(accent = 0x445566, reducedMotion = true)).map { it.first }
        assertEquals(listOf(Portal.ACCENT_COLOR, Portal.REDUCED_MOTION), many)
    }

    @Test
    fun themeForApps() {
        assertTrue(FlatpakTheme.SYSTEM.isDark(androidDark = true))
        assertFalse(FlatpakTheme.SYSTEM.isDark(androidDark = false))
        assertTrue(FlatpakTheme.DARK.isDark(androidDark = false))
        assertFalse(FlatpakTheme.LIGHT.isDark(androidDark = true))
    }
}
