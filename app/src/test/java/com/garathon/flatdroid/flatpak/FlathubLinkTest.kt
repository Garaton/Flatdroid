package com.garathon.flatdroid.flatpak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlathubLinkTest {
    @Test
    fun anInstallPageNamesItsApp() {
        assertEquals("org.xfce.mousepad", FlathubLink.appId("https://flathub.org/en/apps/org.xfce.mousepad/install"))
        assertEquals("org.xfce.mousepad", FlathubLink.appId("https://flathub.org/apps/org.xfce.mousepad/install"))
        assertEquals("org.xfce.mousepad", FlathubLink.appId("https://www.flathub.org/pt-BR/apps/org.xfce.mousepad/install?x=1#y"))
        assertEquals("org.xfce.mousepad", FlathubLink.appId("  https://flathub.org/en/apps/org.xfce.mousepad  "))
        assertEquals("md.obsidian.Obsidian", FlathubLink.appId("https://flathub.org/de/apps/md.obsidian.Obsidian/install"))
        assertEquals("io.github.some-app.App_1", FlathubLink.appId("https://flathub.org/apps/io.github.some-app.App_1/install"))
    }

    @Test
    fun anAppstreamLinkNamesItsApp() {
        assertEquals("org.xfce.mousepad", FlathubLink.appId("appstream://org.xfce.mousepad"))
        assertEquals("org.xfce.mousepad", FlathubLink.appId("appstream://org.xfce.mousepad.desktop"))
        assertEquals("org.xfce.mousepad", FlathubLink.appId("appstream:org.xfce.mousepad"))
    }

    @Test
    fun otherLinksAreIgnored() {
        // Not https, not Flathub, not an app page.
        assertNull(FlathubLink.appId("http://flathub.org/en/apps/org.xfce.mousepad/install"))
        assertNull(FlathubLink.appId("https://flathub.org.evil.example/en/apps/org.xfce.mousepad/install"))
        assertNull(FlathubLink.appId("https://evil.example/flathub.org/apps/org.xfce.mousepad/install"))
        assertNull(FlathubLink.appId("https://user@flathub.org/en/apps/org.xfce.mousepad/install"))
        assertNull(FlathubLink.appId("https://flathub.org:8443/en/apps/org.xfce.mousepad/install"))
        assertNull(FlathubLink.appId("https://flathub.org/en/apps"))
        assertNull(FlathubLink.appId("https://flathub.org/en/apps/org.xfce.mousepad/reviews"))
        assertNull(FlathubLink.appId("https://flathub.org/en/apps/org.xfce.mousepad/install/more"))
        assertNull(FlathubLink.appId("https://flathub.org/en/collection/popular"))
        assertNull(FlathubLink.appId("https://flathub.org/en/apps/category/Utility"))
        assertNull(FlathubLink.appId("ftp://flathub.org/en/apps/org.xfce.mousepad/install"))
        assertNull(FlathubLink.appId("file:///sdcard/org.xfce.mousepad.flatpakref"))
        assertNull(FlathubLink.appId(null))
        assertNull(FlathubLink.appId(""))
    }

    @Test
    fun onlyAValidAppIdComesOut() {
        // One part, a leading digit, characters an address could smuggle in, too long.
        assertNull(FlathubLink.appId("https://flathub.org/en/apps/mousepad/install"))
        assertNull(FlathubLink.appId("https://flathub.org/en/apps/1org.xfce/install"))
        assertNull(FlathubLink.appId("https://flathub.org/en/apps/org.xfce.mouse%20pad/install"))
        assertNull(FlathubLink.appId("https://flathub.org/en/apps/org.xfce.mousepad%3Brm/install"))
        assertNull(FlathubLink.appId("https://flathub.org/en/apps/" + "a.".repeat(300) + "b/install"))
        assertNull(FlathubLink.appId("appstream://"))
        assertNull(FlathubLink.appId("appstream://not-an-id"))
    }
}
