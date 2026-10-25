package com.garathon.flatdroid.flatpak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyfileTest {
    @Test
    fun trimsSpacesAroundEqualsAndSkipsComments() {
        val kf = Keyfile.parse(
            """
            # comment
            [Runtime]
            name = org.gnome.Platform
              runtime=org.gnome.Platform/aarch64/50

            [Environment]
            A=1=2
            """.trimIndent()
        )
        assertEquals("org.gnome.Platform", kf.string("Runtime", "name"))
        assertEquals("org.gnome.Platform/aarch64/50", kf.string("Runtime", "runtime"))
        assertEquals("1=2", kf.string("Environment", "A"))
        assertNull(kf.string("Runtime", "missing"))
        assertNull(kf.string("Missing", "name"))
    }

    @Test
    fun listsSplitOnUnescapedSemicolonsAndDropEmptyItems() {
        val kf = Keyfile.parse("[Context]\nshared=network;ipc;\nodd=a\\;b;c\\sd;\n")
        assertEquals(listOf("network", "ipc"), kf.list("Context", "shared"))
        assertEquals(listOf("a;b", "c d"), kf.list("Context", "odd"))
        assertEquals(emptyList<String>(), kf.list("Context", "missing"))
    }

    @Test
    fun unescapesStringsAndReadsBooleans() {
        val kf = Keyfile.parse("[G]\ns=a\\sb\\nc\\\\d\nt=true\nf=0\nx=maybe\n")
        assertEquals("a b\nc\\d", kf.string("G", "s"))
        assertEquals(true, kf.boolean("G", "t"))
        assertEquals(false, kf.boolean("G", "f"))
        assertNull(kf.boolean("G", "x"))
    }

    @Test
    fun keysBeforeAnyGroupAndLinesWithoutEqualsAreIgnored() {
        val kf = Keyfile.parse("stray=1\n[G]\nnot a pair\nk=v\nk=second\n")
        assertEquals(setOf("G"), kf.groupNames)
        assertEquals(mapOf("k" to "v"), kf.group("G"))
    }
}
