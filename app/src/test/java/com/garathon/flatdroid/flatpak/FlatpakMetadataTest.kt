package com.garathon.flatdroid.flatpak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class FlatpakMetadataTest {
    @Test
    fun readsAnApp() {
        val m = FlatpakMetadata.parse(
            """
            [Application]
            name=org.audacityteam.Audacity
            runtime=org.gnome.Platform/aarch64/50
            sdk=org.gnome.Sdk/aarch64/50
            command=audacity

            [Context]
            shared=network;ipc;
            sockets=x11;pulseaudio;
            filesystems=host;xdg-run/pipewire-0;

            [Environment]
            LD_LIBRARY_PATH=/app/lib/audacity
            ALSA_CONFIG_PATH=

            [Extension org.audacityteam.Audacity.Plugin]
            directory=extensions/Plugins
            subdirectories=true
            no-autodownload=true
            """.trimIndent()
        )
        assertEquals("org.audacityteam.Audacity", m.name)
        assertEquals(FlatpakRef("org.gnome.Platform", "aarch64", "50"), m.runtime)
        assertEquals("audacity", m.command)
        assertEquals(mapOf("LD_LIBRARY_PATH" to "/app/lib/audacity", "ALSA_CONFIG_PATH" to ""), m.environment)
        assertEquals(listOf("network", "ipc"), m.context.shared)
        assertEquals(listOf("host", "xdg-run/pipewire-0"), m.context.filesystems)
        assertEquals(
            listOf(ExtensionPoint("org.audacityteam.Audacity.Plugin", "extensions/Plugins", emptyList(), true, null)),
            m.extensionPoints,
        )
    }

    @Test
    fun readsARuntimesGlExtensionPoint() {
        val m = FlatpakMetadata.parse(
            """
            [Runtime]
            name = org.freedesktop.Platform
            runtime = org.freedesktop.Platform/aarch64/26.08

            [Extension org.freedesktop.Platform.GL]
            versions = 26.08;26.08-extra;1.4
            version = 1.4
            directory = lib/aarch64-linux-gnu/GL
            subdirectories = true
            add-ld-path = lib

            [Extension org.freedesktop.Platform.Locale]
            directory = share/runtime/locale

            [Extension bad]
            directory = ../escape
            """.trimIndent()
        )
        assertEquals("org.freedesktop.Platform", m.name)
        assertNull(m.command)
        assertEquals(
            listOf(
                ExtensionPoint("org.freedesktop.Platform.GL", "lib/aarch64-linux-gnu/GL", listOf("26.08", "26.08-extra", "1.4"), true, "lib"),
                ExtensionPoint("org.freedesktop.Platform.Locale", "share/runtime/locale", emptyList(), false, null),
            ),
            m.extensionPoints,
        )
    }

    @Test
    fun waylandIsForcedOffOnlyForAppsThatAlsoTakeX11() {
        assertTrue(FlatpakContext(sockets = listOf("wayland")).wayland)
        assertFalse(FlatpakContext(sockets = listOf("wayland")).waylandAndX11)
        assertTrue(FlatpakContext(sockets = listOf("wayland", "fallback-x11")).waylandAndX11)
        assertTrue(FlatpakContext(sockets = listOf("wayland", "x11")).waylandAndX11)
        assertFalse(FlatpakContext(sockets = listOf("x11", "pulseaudio")).wayland)
    }

    @Test
    fun parsesRefs() {
        assertEquals(FlatpakRef("org.kde.Platform", "aarch64", "6.9"), FlatpakRef.parse("runtime/org.kde.Platform/aarch64/6.9"))
        assertEquals("a/b/c", FlatpakRef.parse("app/a/b/c").toString())
        assertNull(FlatpakRef.parse("a/b"))
        assertNull(FlatpakRef.parse("a/../c"))
    }

    @Test
    fun aCameraHintIsAllDevicesOrThePipewireSocket() {
        assertFalse(FlatpakContext(devices = listOf("all")).mayUseCamera) // To maybe be changed if we decide different behavior in the future
        assertTrue(FlatpakContext(filesystems = listOf("xdg-run/pipewire-0:ro")).mayUseCamera)
        assertTrue(FlatpakContext(filesystems = listOf("xdg-run/pipewire-0")).mayUseCamera)
        assertFalse(FlatpakContext(devices = listOf("dri"), filesystems = listOf("home", "xdg-run/pipewire-0-manager")).mayUseCamera)
        assertFalse(FlatpakContext(sockets = listOf("pulseaudio")).mayUseCamera)
    }

    @Test
    fun readsTheSessionBusPolicy() {
        val m = FlatpakMetadata.parse(
            """
            [Application]
            name=org.kde.kmines

            [Session Bus Policy]
            org.kde.StatusNotifierWatcher=talk
            org.freedesktop.Notifications = talk
            com.example.Own.*=own
            """.trimIndent()
        )
        assertEquals(
            mapOf("org.kde.StatusNotifierWatcher" to "talk", "org.freedesktop.Notifications" to "talk", "com.example.Own.*" to "own"),
            m.sessionBusPolicy,
        )
        assertEquals(emptyMap<String, String>(), FlatpakMetadata.parse("[Application]\nname=a.b\n").sessionBusPolicy)
    }

    @Test
    fun extraDataRunsWithTheAppsOrExtensionsRuntime() {
        val app = FlatpakMetadata.parse("[Application]\nname=com.visualstudio.code\nruntime=org.freedesktop.Sdk/aarch64/25.08\n\n[Extra Data]\nname=code.tgz\n")
        assertEquals(FlatpakRef("org.freedesktop.Sdk", "aarch64", "25.08"), app.extraDataRuntime)
        val ext = FlatpakMetadata.parse("[Runtime]\nname=org.freedesktop.Platform.openh264\nruntime=org.freedesktop.Platform/aarch64/26.08\n")
        assertEquals(FlatpakRef("org.freedesktop.Platform", "aarch64", "26.08"), ext.extraDataRuntime)
        assertNull(FlatpakMetadata.parse("[Runtime]\nname=a.b\nruntime=a.c/x/1\n\n[Extra Data]\nNoRuntime=true\n").extraDataRuntime)
        // An extension naming no runtime runs with the one it extends.
        val extension = FlatpakMetadata.parse("[Runtime]\nname=a.b.ext\n\n[ExtensionOf]\nref=runtime/a.b/aarch64/26.08\n")
        assertEquals(FlatpakRef("a.b", "aarch64", "26.08"), extension.extraDataRuntime)
    }
}
