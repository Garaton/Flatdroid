package com.garathon.flatdroid.flatpak

import com.garathon.flatdroid.ui.AppSort
import com.garathon.flatdroid.ui.forList
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
    fun commandsRefuseBadIds() {
        assertEquals(listOf(FlatpakCommands.FLATPAK, "--user", "install", "-y", "flathub", "org.kde.kcalc"), FlatpakCommands.install("org.kde.kcalc"))
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
    fun listSortsAndFiltersByName() {
        val apps = listOf(
            InstalledApp("b.B", "beta", null, null),
            InstalledApp("a.A", "Alpha", null, 100L),
            InstalledApp("c.C", "Gamma", null, 200L),
        )
        assertEquals(listOf("Alpha", "beta", "Gamma"), apps.forList("", AppSort.NAME).map { it.name })
        assertEquals(listOf("Gamma", "Alpha", "beta"), apps.forList("", AppSort.LAST_OPENED).map { it.name })
        assertEquals(listOf("Alpha", "beta"), apps.forList(" ALP", AppSort.NAME).map { it.name } + apps.forList("et", AppSort.NAME).map { it.name })
        assertEquals(listOf("Gamma"), apps.forList("GAM", AppSort.NAME).map { it.name })
        // The running app comes first.
        assertEquals(listOf("beta", "Alpha", "Gamma"), apps.forList("", AppSort.NAME, runningAppId = "b.B").map { it.name })
        // A newly installed app, never opened, comes before those opened earlier.
        val installed = apps + InstalledApp("d.D", "Delta", null, null, installed = 300L)
        assertEquals(listOf("Delta", "Gamma", "Alpha", "beta"), installed.forList("", AppSort.LAST_OPENED).map { it.name })
        // IDs aren't searched.
        assertEquals(emptyList<String>(), apps.forList("c.C", AppSort.NAME).map { it.name })
    }
}
