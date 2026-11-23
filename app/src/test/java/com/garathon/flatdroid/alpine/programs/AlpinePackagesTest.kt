package com.garathon.flatdroid.alpine.programs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpinePackagesTest {
    private val db = """
        C:Q1abc=
        P:mousepad
        V:0.6.3-r0
        A:aarch64
        o:mousepad
        D:so:libc.musl-aarch64.so.1 so:libgtk-3.so.0 !mousepad-old gtksourceview4>=4.8
        p:cmd:mousepad=0.6.3-r0
        F:usr/bin
        R:mousepad
        Z:Q1xyz=
        F:usr/share/applications
        R:org.xfce.mousepad.desktop

        P:mousepad-lang
        o:mousepad
        F:usr/share/locale/de/LC_MESSAGES
        R:mousepad.mo

        P:xeyes
        o:xeyes
        D:so:libX11.so.6 so:libXext.so.6
        F:usr/bin
        R:xeyes

        P:qtapp
        o:qtapp
        D:so:libQt6Gui.so.6 so:libwayland-client.so.0
        F:usr/bin
        R:qtapp
    """.trimIndent() + "\n"

    @Test
    fun theDatabaseGivesFilesDependenciesAndProvides() {
        val packages = AlpinePackages.parseInstalled(db)
        assertEquals(listOf("mousepad", "mousepad-lang", "xeyes", "qtapp"), packages.map { it.name })
        val mousepad = packages[0]
        assertEquals(listOf("usr/bin/mousepad", "usr/share/applications/org.xfce.mousepad.desktop"), mousepad.files)
        // Conflicts are left out, versions dropped.
        assertEquals(listOf("so:libc.musl-aarch64.so.1", "so:libgtk-3.so.0", "gtksourceview4"), mousepad.depends)
        assertEquals(listOf("cmd:mousepad"), mousepad.provides)
        assertEquals("mousepad", packages[1].origin)
        assertEquals("mousepad", AlpinePackages.owners(packages)["usr/share/applications/org.xfce.mousepad.desktop"])
    }

    @Test
    fun theWorldFileGivesNamesOnly() {
        assertEquals(setOf("flatpak", "mousepad", "foo", "bar"), AlpinePackages.parseWorld("flatpak\nmousepad>=0.6\nfoo@testing\nbar=1.0-r0\n!baz\n"))
    }

    @Test
    fun theDisplayComesFromTheSourcesLibraries() {
        val packages = AlpinePackages.parseInstalled(db)
        assertEquals(AlpinePackages.Display.WAYLAND_AND_X11, AlpinePackages.display("mousepad", packages))
        assertEquals(AlpinePackages.Display.X11, AlpinePackages.display("xeyes", packages))
        // Qt without its Wayland plugin takes X11, whatever else links Wayland.
        assertEquals(AlpinePackages.Display.X11, AlpinePackages.display("qtapp", packages))
        val withPlugin = packages + ApkPackage("qt6-qtwayland", "qt6-qtwayland", emptyList(), emptyList(), emptyList())
        assertEquals(AlpinePackages.Display.WAYLAND_AND_X11, AlpinePackages.display("qtapp", withPlugin))
        assertEquals(AlpinePackages.Display.WAYLAND_AND_X11, AlpinePackages.display("unknown", packages))
    }

    @Test
    fun packageNamesAreCheckedBeforeApk() {
        assertEquals(listOf("mousepad", "py3-gobject3", "gtk+3.0"), AlpinePackages.parseNames(" mousepad  py3-gobject3 gtk+3.0 mousepad "))
        for (bad in listOf("-X", "--allow-untrusted", "foo=1.0", "foo>1", "../x", "/bin/sh", "a b;c")) {
            assertFalse(bad, AlpinePackages.isPackageName(bad))
        }
        assertNull(AlpinePackages.parseNames("mousepad --force"))
        assertNull(AlpinePackages.parseNames("  "))
        assertTrue(AlpinePackages.isPackageName("font-dejavu"))
    }

    @Test
    fun apksChecksumsSayWhetherContentIsAsInstalled() {
        val packages = AlpinePackages.parseInstalled("P:a\nF:usr/bin\nR:a\na:0:0:755\nZ:Q1qvTGHdzF6KLavt4PO0gs2a6pQ00=\nR:b\nZ:Q1xxxx\n")
        assertEquals(mapOf("usr/bin/a" to "Q1qvTGHdzF6KLavt4PO0gs2a6pQ00=", "usr/bin/b" to "Q1xxxx"), packages[0].checksums)
        assertTrue(AlpinePackages.matches("Q1qvTGHdzF6KLavt4PO0gs2a6pQ00=", "hello".byteInputStream()))
        assertTrue(AlpinePackages.matches("Q2LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=", "hello".byteInputStream()))
        assertFalse(AlpinePackages.matches("Q1qvTGHdzF6KLavt4PO0gs2a6pQ00=", "hellO".byteInputStream()))
        assertFalse(AlpinePackages.matches("X1qvTGHdzF6KLavt4PO0gs2a6pQ00=", "hello".byteInputStream()))
        assertEquals("", AlpinePackages.base64(ByteArray(0)))
        assertEquals("YQ==", AlpinePackages.base64("a".toByteArray()))
        assertEquals("YWI=", AlpinePackages.base64("ab".toByteArray()))
        assertEquals("YWJj", AlpinePackages.base64("abc".toByteArray()))
    }
}
