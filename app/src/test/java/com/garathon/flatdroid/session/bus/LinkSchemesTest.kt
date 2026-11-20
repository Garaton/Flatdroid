package com.garathon.flatdroid.session.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSchemesTest {
    @Test
    fun anySchemeButAndroidsOwn() {
        for (uri in listOf("https://flathub.org/apps", "HTTP://example.org", "mailto:a@example.org", "tel:+15551234", "geo:0,0", "magnet:?xt=urn:btih:x")) {
            assertTrue(uri, LinkSchemes.allowed(uri))
        }
        for (uri in listOf(
            "file:///etc/passwd", "content://com.garathon.flatdroid.homes/x", "intent://x#Intent;end", "android-app://com.example",
            "android.resource://x/1", "JavaScript:alert(1)", "data:text/html,x",
        )) {
            assertFalse(uri, LinkSchemes.allowed(uri))
        }
    }

    @Test
    fun onlyWellFormedAddresses() {
        for (uri in listOf("", "no scheme", "/path", "1http://x", "https://a b", "https://a\nb", "https://a\u0000", "https://" + "a".repeat(LinkSchemes.MAX_LENGTH))) {
            assertFalse(uri, LinkSchemes.allowed(uri))
        }
        assertEquals("https", LinkSchemes.scheme("HTTPS://x"))
        assertTrue(LinkSchemes.allowedScheme("web+app"))
        assertFalse(LinkSchemes.allowedScheme("Content"))
        assertFalse(LinkSchemes.allowedScheme("bad scheme"))
    }
}
