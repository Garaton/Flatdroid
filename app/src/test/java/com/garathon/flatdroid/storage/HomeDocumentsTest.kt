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
}
