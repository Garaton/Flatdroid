package com.garathon.flatdroid.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeDocumentsTest {
    @Test
    fun idsNameAnAppAndAPathInsideItsHome() {
        assertEquals("org.gnome.TextEditor" to "", HomeDocuments.parse("org.gnome.TextEditor"))
        assertEquals("org.gnome.TextEditor" to "Documents/a.txt", HomeDocuments.parse("org.gnome.TextEditor/Documents/a.txt"))
        assertNull(HomeDocuments.parse("org.gnome.TextEditor/../x"))
        assertNull(HomeDocuments.parse("org.gnome.TextEditor//x"))
        assertNull(HomeDocuments.parse("../appdata"))
        assertNull(HomeDocuments.parse(HomeDocuments.ROOT))
    }

    @Test
    fun chainsFromTheRootOrAParent() {
        assertEquals(listOf("root", "org.xfce.mousepad", "org.xfce.mousepad/a", "org.xfce.mousepad/a/b"), HomeDocuments.chain(null, "org.xfce.mousepad/a/b"))
        assertEquals(listOf("org.xfce.mousepad/a", "org.xfce.mousepad/a/b"), HomeDocuments.chain("org.xfce.mousepad/a", "org.xfce.mousepad/a/b"))
        assertEquals(listOf("root", "org.xfce.mousepad"), HomeDocuments.chain("root", "org.xfce.mousepad"))
        assertEquals(null, HomeDocuments.chain("org.kde.kalk", "org.xfce.mousepad/a"))
        assertEquals(null, HomeDocuments.chain(null, "org.xfce.mousepad/../x"))
    }
}
