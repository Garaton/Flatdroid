package com.garathon.flatdroid.proot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FdstatxTest {
    @Test
    fun kernelsBefore58NeedIt() {
        assertTrue(Fdstatx.needed("4.19.191-g1234abcd"))
        assertTrue(Fdstatx.needed("5.4.249-android11-2-00004-g212a7aaded26-ab10688362"))
        assertTrue(Fdstatx.needed("5.7.0"))
        assertTrue(Fdstatx.needed("3.10.0+"))
        assertFalse(Fdstatx.needed("5.8.0"))
        assertFalse(Fdstatx.needed("5.10.198-android12-9"))
        assertFalse(Fdstatx.needed("6.1.75-android14-11"))
        assertFalse(Fdstatx.needed("10.0.0"))
        // Unreadable counts as old: the library does nothing where the kernel gives the ID.
        assertTrue(Fdstatx.needed("unknown"))
    }
}
