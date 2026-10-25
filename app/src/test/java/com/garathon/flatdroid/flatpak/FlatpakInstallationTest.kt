package com.garathon.flatdroid.flatpak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FlatpakInstallationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val appMetadata = "[Application]\nname=org.xfce.mousepad\nruntime=org.freedesktop.Platform/aarch64/26.08\ncommand=mousepad\n"

    @Test
    fun findsTheCurrentBranchOfAnApp() {
        val inst = TestInstallation(tmp.root)
        inst.app("org.xfce.mousepad", "beta", appMetadata, current = false)
        inst.app("org.xfce.mousepad", "stable", appMetadata)
        val app = FlatpakInstallation(tmp.root, "aarch64").app("org.xfce.mousepad")!!
        assertEquals(FlatpakRef("org.xfce.mousepad", "aarch64", "stable"), app.ref)
        assertEquals("mousepad", app.metadata.command)
        assertEquals(File(tmp.root.canonicalFile, "app/org.xfce.mousepad/aarch64/stable/0123abcd/files"), app.files)
    }

    @Test
    fun withoutCurrentTheOnlyBranchIsUsed() {
        TestInstallation(tmp.root).app("a.b.C", "master", appMetadata, current = false)
        assertEquals("master", FlatpakInstallation(tmp.root, "aarch64").app("a.b.C")!!.ref.branch)
    }

    @Test
    fun listsAppsAndIgnoresOtherArchitectures() {
        val inst = TestInstallation(tmp.root)
        inst.app("b.App", metadata = appMetadata)
        inst.app("a.App", metadata = appMetadata)
        val installation = FlatpakInstallation(tmp.root, "aarch64")
        assertEquals(listOf("a.App", "b.App"), installation.appIds())
        assertNull(FlatpakInstallation(tmp.root, "x86_64").app("a.App"))
        assertNull(installation.app("../x"))
    }

    @Test
    fun deploymentsLinkingOutsideTheInstallationAreIgnored() {
        val outside = tmp.newFolder("outside")
        File(outside, "files").mkdirs()
        File(outside, "metadata").writeText(appMetadata)
        TestInstallation.link(File(tmp.root, "inst/app/evil.App/aarch64/stable/active"), outside.path)
        assertNull(FlatpakInstallation(File(tmp.root, "inst"), "aarch64").app("evil.App"))
    }

    @Test
    fun mountsTheFirstInstalledVersionOfEachExtension() {
        val inst = TestInstallation(tmp.root)
        inst.runtime("org.freedesktop.Platform.GL.default", "26.08-extra")
        inst.runtime("org.freedesktop.Platform.GL.default", "26.08")
        inst.runtime("org.freedesktop.Platform.Locale", "26.08")
        inst.runtime("org.freedesktop.Platform.GL32.default", "26.08")
        val points = listOf(
            ExtensionPoint("org.freedesktop.Platform.GL", "lib/aarch64-linux-gnu/GL", listOf("26.08", "26.08-extra"), true, "lib"),
            ExtensionPoint("org.freedesktop.Platform.Locale", "share/runtime/locale", emptyList(), false, null),
            ExtensionPoint("org.freedesktop.Platform.ffmpeg-full", "lib/ffmpeg", listOf("24.08"), false, null),
        )
        val mounted = FlatpakInstallation(tmp.root, "aarch64").extensions(points, "26.08")
        assertEquals(
            listOf(
                "org.freedesktop.Platform.GL.default/aarch64/26.08 -> lib/aarch64-linux-gnu/GL/default (lib)",
                "org.freedesktop.Platform.Locale/aarch64/26.08 -> share/runtime/locale (null)",
            ),
            mounted.map { "${it.deployment.ref} -> ${it.guestPath} (${it.addLdPath})" },
        )
    }
}
