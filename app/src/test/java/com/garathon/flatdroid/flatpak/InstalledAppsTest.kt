package com.garathon.flatdroid.flatpak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class InstalledAppsTest {
    @Test
    fun appIdsThatCouldBeOptionsOrPathsAreInvalid() {
        assertTrue(InstalledApps.isValidId("org.xfce.mousepad"))
        assertTrue(InstalledApps.isValidId("dev.bambosh.UnofficialHomestuckCollection"))
        assertTrue(InstalledApps.isValidId("com.github.johnfactotum.Foliate"))
        for (bad in listOf("", "mousepad", "-y.x", "--help.x", "a/b.c", "a..b", "a.b ", "../x.y", "a.b;rm")) {
            assertFalse(bad, InstalledApps.isValidId(bad))
        }
    }

    @Test
    fun severalIdsAreSplitOnWhitespace() {
        assertEquals(listOf("org.xfce.mousepad"), InstalledApps.parseIds(" org.xfce.mousepad "))
        assertEquals(listOf("org.xfce.mousepad", "org.kde.kcalc"), InstalledApps.parseIds("org.xfce.mousepad  org.kde.kcalc\torg.xfce.mousepad"))
        for (bad in listOf("", "   ", "org.xfce.mousepad --help.x", "org.xfce.mousepad mousepad")) {
            assertEquals(bad, null, InstalledApps.parseIds(bad))
        }
    }

    @Test
    fun commandsRefuseBadIds() {
        assertEquals(listOf(FlatpakCommands.FLATPAK, "--user", "install", "-y", "flathub", "org.kde.kcalc"), FlatpakCommands.install(listOf("org.kde.kcalc")))
        assertEquals(
            listOf(FlatpakCommands.FLATPAK, "--user", "install", "-y", "flathub", "org.kde.kcalc", "org.xfce.mousepad"),
            FlatpakCommands.install(listOf("org.kde.kcalc", "org.xfce.mousepad")),
        )
        try {
            FlatpakCommands.uninstall("--all")
            throw AssertionError("expected a refusal")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun repositoryCleanUpScriptParses() {
        val (sh, flag, script) = FlatpakCommands.cleanRepository
        assertEquals(listOf("/bin/sh", "-c"), listOf(sh, flag))
        val check = ProcessBuilder("/bin/sh", "-n", "-c", script).redirectErrorStream(true).start()
        assertEquals(check.inputStream.bufferedReader().readText(), 0, check.waitFor())
        assertTrue("grep -qxF \"deploy/${'$'}{ref#*:}\"" in script)
    }

    @Test
    fun desktopNamesPreferTheLocale() {
        val desktop = Keyfile.parse("[Desktop Entry]\nName=Text Editor\nName[de]=Texteditor\nName[pt_BR]=Editor de texto\n")
        assertEquals("Texteditor", InstalledApps.localizedName(desktop, Locale.GERMANY))
        assertEquals("Editor de texto", InstalledApps.localizedName(desktop, Locale.forLanguageTag("pt-BR")))
        assertEquals("Text Editor", InstalledApps.localizedName(desktop, Locale.JAPAN))
    }

    @Test
    fun terminalAppsAreToldFromTheirDesktopFile() {
        assertEquals(true, InstalledApps.runsInTerminal(Keyfile.parse("[Desktop Entry]\nName=A\nTerminal=true\n")))
        assertEquals(false, InstalledApps.runsInTerminal(Keyfile.parse("[Desktop Entry]\nName=A\nTerminal=false\n")))
        assertEquals(false, InstalledApps.runsInTerminal(Keyfile.parse("[Desktop Entry]\nName=A\n")))
    }
}
