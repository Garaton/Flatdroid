package com.garathon.flatdroid.launcher

import com.garathon.flatdroid.flatpak.FlatpakInstallation
import com.garathon.flatdroid.flatpak.TestInstallation
import com.garathon.flatdroid.setup.JvmTarFs
import com.garathon.flatdroid.util.sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ElectronSwapTest {
    @get:Rule val tmp = TemporaryFolder()

    private val id = "dev.bambosh.UnofficialHomestuckCollection"
    private val zipName = "electron-v9.4.4-linux-arm64.zip"
    private val l2sDir by lazy { File(tmp.root, "rootfs/.l2s") }

    /** Serves URLs from local files, recording what was fetched. */
    private val served = mutableMapOf<String, File>()
    private val fetched = mutableListOf<String>()
    private fun download(url: String, dest: File, maxBytes: Long, onProgress: (Long, Long) -> Unit) {
        fetched += url
        val src = served[url] ?: throw IOException("404 $url")
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
        onProgress(src.length(), src.length())
    }

    private fun zip(entries: Map<String, String>): File = File(tmp.root, "src.zip").also { f ->
        ZipOutputStream(f.outputStream()).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    private fun setUp(entries: Map<String, String>, sums: (File) -> String = { "${sha256(it)}  $zipName\n" }): Triple<ElectronSwap, PlanResult.ElectronSwapNeeded, com.garathon.flatdroid.flatpak.Deployment> {
        val inst = TestInstallation(File(tmp.root, "flatpak"))
        val files = inst.app(id, metadata = "[Application]\nname=$id\nruntime=org.freedesktop.Platform/aarch64/24.08\ncommand=uhc\n")
        // The app's resources: a file and a link standing in for one into .l2s.
        File(files, "uhc/resources").mkdirs()
        File(files, "uhc/resources/app.asar").writeText("APP")
        val l2s = File(l2sDir, ".l2s.linked0001.0002").apply { parentFile!!.mkdirs(); writeText("LINKED") }
        TestInstallation.link(File(files, "uhc/resources/linked.txt"), l2s.path)

        val zip = zip(entries)
        val sumsFile = File(tmp.root, "SHASUMS256.txt").apply { writeText(sums(zip)) }
        served[Electron.releaseUrl("9.4.4", zipName)] = zip
        served[Electron.releaseUrl("9.4.4", "SHASUMS256.txt")] = sumsFile

        val app = FlatpakInstallation(inst.root, "aarch64").app(id)!!
        val swap = ElectronSwap(
            File(tmp.root, "files/electron/$id"), File(tmp.root, "cache"), "aarch64",
            allowedSources = listOf(inst.root, l2sDir), fs = JvmTarFs, download = ::download, sha256 = ::sha256,
        )
        val needed = PlanResult.ElectronSwapNeeded("9.4.4", "x86_64", "/app/uhc", "uhc", File(files, "uhc"), hasNativeModules = false)
        return Triple(swap, needed, app)
    }

    private val electronZip = mapOf(
        "electron" to "ARM64-ELECTRON",
        "libffmpeg.so" to "SO",
        "resources/default_app.asar" to "DEFAULT",
        "locales/en-US.pak" to "PAK",
    )

    @Test
    fun buildsTheSwapFromElectronAndTheAppsResources() {
        val (swap, needed, app) = setUp(electronZip)
        assertNull(swap.preparedFor(app))
        swap.prepare(app, needed) { _, _, _ -> }

        val dir = swap.preparedFor(app)!!
        assertEquals("ARM64-ELECTRON", File(dir, "uhc").readText())
        assertTrue(File(dir, "uhc").canExecute())
        assertFalse(File(dir, "electron").exists())
        assertFalse(File(dir, "resources/default_app.asar").exists())
        assertEquals("APP", File(dir, "resources/app.asar").readText())
        assertEquals("LINKED", File(dir, "resources/linked.txt").readText())
        assertEquals("PAK", File(dir, "locales/en-US.pak").readText())
        // Downloads are gone once the swap is in place.
        assertFalse(File(tmp.root, "cache/electron-9.4.4").exists())
    }

    @Test
    fun resourcesLinkingOutsideTheAppAreRefused() {
        val (swap, needed, app) = setUp(electronZip)
        val secret = File(tmp.root, "files/appdata/other.App/secret").apply { parentFile!!.mkdirs(); writeText("private") }
        TestInstallation.link(File(needed.hostAppDir, "resources/stolen"), secret.path)
        try {
            swap.prepare(app, needed) { _, _, _ -> }
            fail("expected a refusal")
        } catch (e: IOException) {
            assertTrue(e.message!!, "links outside the app" in e.message!!)
        }
        assertNull(swap.preparedFor(app))
        assertFalse(File(tmp.root, "files/electron/$id/resources/stolen").exists())
        // The half-built swap is in the cache, not in private storage.
        assertFalse(File(tmp.root, "files/electron/$id").exists())
        assertTrue(File(tmp.root, "cache/${ElectronSwap.BUILD_DIR}/$id").isDirectory)
        swap.delete()
        assertFalse(File(tmp.root, "cache/${ElectronSwap.BUILD_DIR}/$id").exists())
    }

    @Test
    fun resetsFindTheBuildDirectoryAndOldDownloads() {
        val cache = tmp.newFolder("c")
        for (name in listOf("electron-build", "electron-9.4.4", "electron-30.0.0", "appcache")) java.io.File(cache, name).mkdirs()
        assertEquals(setOf("electron-build", "electron-9.4.4", "electron-30.0.0"), ElectronSwap.temporaryFiles(cache).map { it.name }.toSet())
        assertEquals(3, ElectronSwap.temporaryFiles(cache).size)
    }

    @Test
    fun leftoversKeepOnlySwapsAndCachesOfInstalledApps() {
        val (swap, needed, app) = setUp(electronZip)
        swap.prepare(app, needed) { _, _, _ -> }
        val electronDir = File(tmp.root, "files/electron")
        val stale = File(electronDir, "gone.App").apply { mkdirs() }
        val oldBuild = File(electronDir, "$id.new").apply { mkdirs() }
        val caches = File(tmp.root, "cache/appcache")
        File(caches, id).mkdirs()
        File(caches, "gone.App/x").apply { parentFile!!.mkdirs(); writeText("x") }
        val installation = FlatpakInstallation(File(tmp.root, "flatpak"), "aarch64")

        val removed = Leftovers(installation, electronDir, listOf(caches), JvmTarFs).clean()

        assertEquals(setOf(stale, oldBuild, File(caches, "gone.App")), removed.toSet())
        assertTrue(File(electronDir, id).isDirectory)
        assertTrue(File(caches, id).isDirectory)
        // A swap made for an older deployment goes too.
        File(electronDir, id).resolve(".flatdroid-electron").writeText("deploy=oldhash\n")
        assertEquals(listOf(File(electronDir, id)), Leftovers(installation, electronDir, emptyList(), JvmTarFs).clean())
    }

    @Test
    fun aSwapForAnOlderDeploymentIsNotUsed() {
        val (swap, needed, app) = setUp(electronZip)
        swap.prepare(app, needed) { _, _, _ -> }
        assertNull(swap.preparedFor(app.copy(dir = File(app.dir.parentFile, "newhash"))))
    }

    @Test
    fun checksumMismatchLeavesNothingPrepared() {
        val (swap, needed, app) = setUp(electronZip, sums = { "${"0".repeat(64)}  $zipName\n" })
        try {
            swap.prepare(app, needed) { _, _, _ -> }
            fail("expected a checksum mismatch")
        } catch (e: IOException) {
            assertTrue(e.message!!, "checksum mismatch" in e.message!!)
        }
        assertNull(swap.preparedFor(app))
    }

    @Test
    fun unsafeZipPathsAreRefused() {
        val (swap, needed, app) = setUp(electronZip + ("../escape" to "x"))
        try {
            swap.prepare(app, needed) { _, _, _ -> }
            fail("expected an unsafe path")
        } catch (e: IOException) {
            assertTrue(e.message!!, "Unsafe path" in e.message!!)
        }
        assertFalse(File(tmp.root, "files/electron/escape").exists())
        assertFalse(File(tmp.root, "cache/${ElectronSwap.BUILD_DIR}/escape").exists())
        assertNull(swap.preparedFor(app))
    }

    @Test
    fun nativeModulesOrNoVersionAreRefusedBeforeDownloading() {
        val (swap, needed, app) = setUp(electronZip)
        for (bad in listOf(needed.copy(hasNativeModules = true), needed.copy(version = null))) {
            try {
                swap.prepare(app, bad) { _, _, _ -> }
                fail("expected a refusal")
            } catch (_: IOException) {
            }
        }
        assertEquals(emptyList<String>(), fetched)
    }
}
