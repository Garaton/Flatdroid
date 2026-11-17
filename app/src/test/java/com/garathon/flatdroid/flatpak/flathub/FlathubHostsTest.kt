package com.garathon.flatdroid.flatpak.flathub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlathubHostsTest {
    @Test
    fun flathubsOwnHostsAreAllowed() {
        assertTrue(FlathubHosts.allows("https://dl.flathub.org/media/org/xfce/mousepad/abc/screenshots/image-1_orig.png"))
        assertTrue(FlathubHosts.allows("https://flathub.org/img/x.png"))
        assertTrue(FlathubHosts.allows("https://DL.Flathub.org:443/a.png"))
    }

    @Test
    fun everythingElseIsRefused() {
        assertFalse(FlathubHosts.allows("http://dl.flathub.org/a.png"))
        assertFalse(FlathubHosts.allows("https://raw.githubusercontent.com/x/y/z.png"))
        assertFalse(FlathubHosts.allows("https://flathub.org.example.com/a.png"))
        assertFalse(FlathubHosts.allows("https://example.com/dl.flathub.org/a.png"))
        assertFalse(FlathubHosts.allows("https://dl.flathub.org@example.com/a.png"))
        assertFalse(FlathubHosts.allows("https://dl.flathub.org:8443/a.png"))
        assertFalse(FlathubHosts.allows("https://user@dl.flathub.org/a.png"))
        assertFalse(FlathubHosts.allows("https://sub.dl.flathub.org/a.png"))
        assertFalse(FlathubHosts.allows("https://dl.flathub.org./a.png"))
        assertFalse(FlathubHosts.allows("not a url"))
        assertFalse(FlathubHosts.allows(""))
    }
}
