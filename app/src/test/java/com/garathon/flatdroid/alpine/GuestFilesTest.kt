package com.garathon.flatdroid.alpine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestFilesTest {
    @Test
    fun resolvConfUsesNetworkServers() {
        assertEquals("nameserver 192.168.1.1\nnameserver 2001:db8::1\n", GuestFiles.resolvConf(listOf("192.168.1.1", "2001:db8::1")))
    }

    @Test
    fun resolvConfDropsScopesLinkLocalAndExtras() {
        val conf = GuestFiles.resolvConf(listOf("fe80::1%wlan0", "10.0.0.1", "2001:db8::2%rmnet0", "10.0.0.1", "10.0.0.2", "10.0.0.3"))
        assertEquals("nameserver 10.0.0.1\nnameserver 2001:db8::2\nnameserver 10.0.0.2\n", conf)
    }

    @Test
    fun resolvConfFallsBackWhenNothingUsable() {
        assertEquals("nameserver 1.1.1.1\nnameserver 9.9.9.9\n", GuestFiles.resolvConf(emptyList()))
        assertEquals("nameserver 1.1.1.1\nnameserver 9.9.9.9\n", GuestFiles.resolvConf(listOf("fe80::1%wlan0")))
    }

    @Test
    fun flatpakLanguagesAreLanguageCodesPlusEnglish() {
        assertEquals("de;fr;en", GuestFiles.flatpakLanguages(listOf("de-DE", "fr-CA", "de-AT")))
        assertEquals("en", GuestFiles.flatpakLanguages(listOf("en-US", "en-GB")))
        assertEquals("pt;en", GuestFiles.flatpakLanguages(listOf("pt_BR", "und")))
        assertEquals("en", GuestFiles.flatpakLanguages(emptyList()))
    }

    @Test
    fun openboxMaximisesNormalWindowsAndKeepsDecorations() {
        val rc = GuestFiles.openboxRc()
        assertTrue("<maximized>yes</maximized>" in rc)
        assertTrue("type=\"normal\"" in rc)
        assertTrue("<decor>" !in rc)
        // No minimise button (I); the empty desktop lists windows.
        assertTrue("<titleLayout>NLMC</titleLayout>" in rc)
        assertTrue("<context name=\"Root\"><mousebind button=\"Left\" action=\"Press\"><action name=\"ShowMenu\"><menu>client-list-combined-menu</menu>" in rc)
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rc.byteInputStream())
        assertEquals(8 + 6, doc.getElementsByTagName("context").length)
    }

    @Test
    fun fakeStatHasOneLinePerCpuAndConsistentTotals() {
        val proc = GuestFiles.fakeProc(cpuCount = 8, kernelRelease = "5.15.0", kernelVersion = "#1 SMP", uptimeSeconds = 100, bootEpochSeconds = 1_700_000_000)
        val stat = proc.getValue("/proc/stat").lines()
        val cpuLines = stat.filter { Regex("^cpu\\d+ ").containsMatchIn(it) }
        assertEquals(8, cpuLines.size)
        val total = stat.first().removePrefix("cpu").trim().split(' ').map { it.toLong() }
        val summed = cpuLines.map { l -> l.substringAfter(' ').split(' ').map { it.toLong() } }
            .reduce { a, b -> a.zip(b) { x, y -> x + y } }
        assertEquals(summed, total)
        assertTrue("btime 1700000000" in stat)
        assertTrue(proc.getValue("/proc/version").startsWith("Linux version 5.15.0 "))
        assertTrue(proc.keys.all { it.startsWith("/proc/") })
    }

    @Test
    fun machineIdIsRandomHex() {
        val a = GuestFiles.machineId()
        assertTrue(Regex("^[0-9a-f]{32}\n$").matches(a))
        assertTrue(a != GuestFiles.machineId())
    }
}
