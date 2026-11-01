package com.garathon.flatdroid.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class AlpineResetTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var alpine: File
    private lateinit var cache: File
    private lateinit var installer: RootfsInstaller
    private lateinit var state: SetupStateStore
    private lateinit var temp: File
    private lateinit var appData: File
    private lateinit var appCache: File
    private lateinit var electron: File
    private lateinit var reset: AlpineReset

    private val tarGz: ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use {
            it.write(Tar().apply {
                entry("./etc/", '5', 0x1ed)
                entry("./etc/alpine-release", '0', data = "3.24.2\n".toByteArray())
            }.bytes())
        }
    }.toByteArray()

    private val source = object : RootfsSource {
        override val version = "3.24.2"
        override val sha256 = MessageDigest.getInstance("SHA-256").digest(tarGz).joinToString("") { "%02x".format(it) }
        override fun open(): InputStream = ByteArrayInputStream(tarGz)
    }

    @Before
    fun setUp() {
        alpine = tmp.newFolder("files", "alpine")
        cache = tmp.newFolder("cache")
        installer = RootfsInstaller(alpine, cache, JvmTarFs)
        state = SetupStateStore(File(tmp.root, "files/setup.properties"))
        temp = File(cache, "alpine/tmp").apply { mkdirs(); File(this, "junk").writeText("x") }
        appData = File(tmp.root, "files/appdata/org.xfce.mousepad/config").apply { mkdirs(); File(this, "mousepadrc").writeText("[State]") }.parentFile!!.parentFile!!
        appCache = File(cache, "appcache/org.xfce.mousepad").apply { mkdirs(); File(this, "thumb").writeText("x") }
        electron = File(tmp.root, "files/electron/dev.bambosh.UnofficialHomestuckCollection").apply { mkdirs() }
        reset = AlpineReset(
            File(alpine, "reset-pending"), installer, state,
            temporary = listOf(File(cache, "alpine"), appCache.parentFile!!),
            flatpakFiles = listOf(electron.parentFile!!),
            userData = listOf(appData),
            fs = JvmTarFs,
        )

        // A set-up environment: Alpine, a Flatpak file that is a link2symlink link into .l2s,
        // Flatpak config, something installed by apk, setup state and a VNC password.
        installer.install(source)
        val rootfs = installer.rootfs
        val backing = File(rootfs, ".l2s/.l2s.mousepad0001").apply { writeText("ELF mousepad") }
        val flatpak = File(rootfs, "root/.local/share/flatpak").apply { mkdirs() }
        File(flatpak, "repo").mkdirs()
        File(flatpak, "repo/config").writeText("[remote \"flathub\"]\n[core]\nxa.languages=de;en\n")
        File(flatpak, "app/org.xfce.mousepad/files/bin").mkdirs()
        Files.createSymbolicLink(File(flatpak, "app/org.xfce.mousepad/files/bin/mousepad").toPath(), backing.toPath())
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/Xvnc").writeText("old Xvnc")
        state.save(SetupState(SetupStep.entries.toSet(), settingsChosen = true))
    }

    private val mousepad get() = File(installer.rootfs, "root/.local/share/flatpak/app/org.xfce.mousepad/files/bin/mousepad")

    @Test
    fun keepFlatpaksBringsThemBackIntoTheNewRootfs() {
        reset.request(ResetMode.KEEP_FLATPAKS)
        assertTrue(reset.isPending())
        reset.finish()

        assertFalse(reset.isPending())
        assertNull(installer.installedVersion())
        assertFalse(installer.rootfs.exists())
        assertTrue(installer.hasKept())
        assertEquals(SetupState(), state.load())
        assertFalse(temp.exists())

        installer.install(source)
        assertEquals("3.24.2", installer.installedVersion())
        assertEquals("ELF mousepad", mousepad.readText()) // link into .l2s still resolves
        assertTrue(File(installer.rootfs, "root/.local/share/flatpak/repo/config").readText().contains("flathub"))
        assertFalse(File(installer.rootfs, "usr/bin/Xvnc").exists()) // Alpine itself is fresh
        assertFalse(installer.hasKept())
        assertFalse(File(alpine, "kept").exists())
        assertEquals("[State]", File(appData, "org.xfce.mousepad/config/mousepadrc").readText())
    }

    @Test
    fun removeFlatpaksKeepsAppDataButNotCaches() {
        reset.request(ResetMode.REMOVE_FLATPAKS)
        reset.finish()
        assertFalse(installer.hasKept())
        assertFalse(electron.exists())
        assertEquals("[State]", File(appData, "org.xfce.mousepad/config/mousepadrc").readText())
        assertFalse(appCache.exists())
        assertFalse(temp.exists())
        installer.install(source)
        assertFalse(File(installer.rootfs, "root/.local/share/flatpak").exists())
    }

    @Test
    fun wipeDeletesFlatpaksToo() {
        reset.request(ResetMode.WIPE)
        reset.finish()
        assertFalse(installer.hasKept())
        assertFalse(appData.exists())
        assertFalse(appCache.exists())
        assertFalse(electron.exists())
        installer.install(source)
        assertFalse(File(installer.rootfs, "root/.local/share/flatpak").exists())
        assertFalse(File(installer.rootfs, ".l2s/.l2s.mousepad0001").exists())
        assertTrue(File(installer.rootfs, ".l2s").isDirectory)
    }

    @Test
    fun interruptedResetFinishesLater() {
        reset.request(ResetMode.KEEP_FLATPAKS)
        installer.setAside() // the process died after this
        reset.finish()
        installer.install(source)
        assertEquals("ELF mousepad", mousepad.readText())
    }

    @Test
    fun installInterruptedAfterRestoringStillKeepsFlatpaks() {
        reset.request(ResetMode.KEEP_FLATPAKS)
        reset.finish()
        // As if a previous install died after moving the kept paths into the partial rootfs.
        val partial = File(cache, "rootfs.partial")
        for (rel in RootfsInstaller.CARRIED) {
            File(partial, rel).parentFile!!.mkdirs()
            assertTrue(File(alpine, "kept/$rel").renameTo(File(partial, rel)))
        }
        installer.install(source)
        assertEquals("ELF mousepad", mousepad.readText())
    }

    @Test
    fun finishWithoutRequestDoesNothing() {
        reset.finish()
        assertEquals("3.24.2", installer.installedVersion())
        assertTrue(state.load().isComplete)
    }

    @Test(expected = IllegalStateException::class)
    fun wipeRefusesACompleteRootfs() {
        installer.wipe()
    }
}
