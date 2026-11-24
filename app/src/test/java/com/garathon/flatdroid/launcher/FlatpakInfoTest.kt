package com.garathon.flatdroid.launcher

import com.garathon.flatdroid.flatpak.FlatpakMetadata
import com.garathon.flatdroid.flatpak.FlatpakRef
import org.junit.Assert.assertEquals
import org.junit.Test

class FlatpakInfoTest {
    @Test
    fun onlyFoldersInTheHomeAreListed() {
        assertEquals(
            listOf("home", "xdg-config/kdeglobals:ro", "xdg-pictures/Camera:create", "~/.steam", "home:ro"),
            FlatpakInfo.filesystems(
                listOf("home", "xdg-config/kdeglobals:ro", "/run/media", "xdg-pictures/Camera:create", "!xdg-music", "~/.steam", "host:ro", "host-etc", "xdg-run/gvfsd"),
                sdcard = false,
            ),
        )
        assertEquals(listOf("/sdcard"), FlatpakInfo.filesystems(emptyList(), sdcard = true))
    }

    @Test
    fun valuesReadBackAsWritten() {
        val info = FlatpakInfo(
            appId = "org.example.App",
            runtime = FlatpakRef("org.example.Platform", "aarch64", "1"),
            filesystems = listOf("~/a;b"),
            environment = mapOf("A" to " lead\\tab\nline"),
        )
        val metadata = FlatpakMetadata.parse(info.text())
        assertEquals(listOf("~/a;b"), metadata.context.filesystems)
        assertEquals(" lead\\tab\nline", metadata.environment["A"])
        assertEquals("org.example.App.Devel", metadata.name)
    }
}
