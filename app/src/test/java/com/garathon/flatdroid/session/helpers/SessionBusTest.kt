package com.garathon.flatdroid.session.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBusTest {
    @Test
    fun flatpaksRulesAndTheMetadataPolicy() {
        val args = SessionBus.proxyArgs(
            "org.kde.kmines",
            mapOf(
                "org.kde.StatusNotifierWatcher" to "talk", "org.example.Own.*" to "own", "org.example.Off" to "none",
                "org.example.Bad" to "call", "--own=*" to "own", "nodots" to "talk", "org.1bad" to "see",
            ),
            camera = false,
        )
        assertEquals(
            listOf(
                "unix:path=/run/flatdroid/dbus/bus", "/run/flatdroid/bus-proxy/bus", "--filter",
                "--own=org.kde.kmines.*", "--own=org.mpris.MediaPlayer2.org.kde.kmines.*",
                "--talk=org.kde.StatusNotifierWatcher", "--own=org.example.Own.*",
            ),
            args.filter { "portal" !in it },
        )
        assertTrue(args.none { "Camera" in it })
    }

    @Test
    fun flatdroidsPortalInterfacesForEveryApp() {
        val portal = SessionBus.proxyArgs("org.example.App", emptyMap(), camera = false).filter { "portal" in it }
        assertTrue(portal.all { it.startsWith("--call=org.freedesktop.portal.Desktop=") || it.startsWith("--broadcast=org.freedesktop.portal.Desktop=") })
        assertTrue(portal.all { it.endsWith("@/org/freedesktop/portal/desktop") || it.endsWith("@/org/freedesktop/portal/desktop/request/*") })
        assertFalse(portal.any { it.endsWith("=*") || "org.freedesktop.portal.*" in it })
        for (iface in listOf("Settings", "NetworkMonitor", "ProxyResolver")) {
            assertTrue("--call=org.freedesktop.portal.Desktop=org.freedesktop.portal.$iface.*@/org/freedesktop/portal/desktop" in portal)
        }
        assertTrue("--broadcast=org.freedesktop.portal.Desktop=org.freedesktop.portal.Settings.SettingChanged@/org/freedesktop/portal/desktop" in portal)
        assertTrue("--call=org.freedesktop.portal.Desktop=org.freedesktop.portal.OpenURI.*@/org/freedesktop/portal/desktop" in portal)
        assertTrue("--broadcast=org.freedesktop.portal.Desktop=org.freedesktop.portal.Request.Response@/org/freedesktop/portal/desktop/request/*" in portal)
    }

    @Test
    fun theRealPortalsLinksForAddressesOnly() {
        val portal = SessionBus.proxyArgs("org.example.App", emptyMap(), camera = true).filter { "OpenURI" in it }
        assertEquals(
            listOf(
                "--call=org.freedesktop.portal.Desktop=org.freedesktop.portal.OpenURI.OpenURI@/org/freedesktop/portal/desktop",
                "--call=org.freedesktop.portal.Desktop=org.freedesktop.portal.OpenURI.SchemeSupported@/org/freedesktop/portal/desktop",
            ),
            portal,
        )
    }

    @Test
    fun theMetadataCantReachFlatdroidsServices() {
        val args = SessionBus.proxyArgs(
            "org.example.App",
            mapOf(
                "org.freedesktop.portal.*" to "talk", "org.freedesktop.portal.Desktop" to "talk",
                "org.freedesktop.impl.portal.PermissionStore" to "talk", "org.freedesktop.impl.portal.desktop.flatdroid" to "own",
                "org.freedesktop.*" to "talk", "org.freedesktop.DBus" to "talk", "org.freedesktop.Notifications" to "talk",
                "org.freedesktop.portalish" to "talk",
            ),
            camera = true,
        )
        val policy = args.filter { it.startsWith("--talk=") || it.startsWith("--own=org.freedesktop") || it.startsWith("--see=") }
        assertEquals(listOf("--talk=org.freedesktop.Notifications", "--talk=org.freedesktop.portalish"), policy)
    }

    @Test
    fun theCameraPortalOnlyWithTheCamera() {
        val args = SessionBus.proxyArgs("org.gnome.Snapshot", emptyMap(), camera = true)
        val portal = args.filter { "portal" in it }
        assertTrue(portal.isNotEmpty())
        // Only the camera's interface and the portal object's properties and requests, never the whole portal.
        assertTrue(portal.all { it.startsWith("--call=org.freedesktop.portal.Desktop=") || it.startsWith("--broadcast=org.freedesktop.portal.Desktop=") })
        assertTrue(portal.all { "@/org/freedesktop/portal/desktop" in it })
        assertFalse(portal.any { it.endsWith("=*") || "org.freedesktop.portal.*" in it })
        assertTrue("--call=org.freedesktop.portal.Desktop=org.freedesktop.portal.Camera.*@/org/freedesktop/portal/desktop" in portal)
    }

    @Test
    fun argumentsEndInNul() {
        assertEquals("a\u0000--b=c d\u0000", SessionBus.argsFile(listOf("a", "--b=c d")).decodeToString())
    }

    @Test
    fun theDaemonListensOnlyOnAPathAndStartsNothing() {
        val config = SessionBus.CONFIG
        assertTrue("<listen>unix:path=/run/flatdroid/dbus/bus</listen>" in config)
        assertFalse("abstract" in config || "tmpdir" in config || "servicedir" in config)
        assertTrue("<auth>ANONYMOUS</auth>" in config)
        assertTrue("<allow receive_sender=\"*\"/>" in config)
        assertFalse("eavesdrop" in config)
    }
}
