package com.garathon.flatdroid.storage

import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.util.copyWithProgress
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Flatdroid's own settings as a zip: `settings.properties` and each app's launcher settings
 * (`appsettings/<ID>.properties`). Not the apps' own settings, which each app's Storage section
 * exports.
 */
object SettingsBackup {
    const val SETTINGS = "settings.properties"
    const val APP_SETTINGS = "appsettings/"
    private const val SUFFIX = ".properties"
    private const val MAX_FILE_BYTES = 64 * 1024

    fun write(out: OutputStream, settings: File, appSettingsDir: File) {
        val zip = ZipOutputStream(out)
        fun add(name: String, file: File) {
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        if (settings.isFile) add(SETTINGS, settings)
        for (file in appSettingsDir.listFiles().orEmpty().sortedBy { it.name }) {
            if (file.isFile && appId(file.name) != null) add(APP_SETTINGS + file.name, file)
        }
        zip.finish()
        zip.flush()
    }

    /**
     * The files in a settings zip, by name: only `settings.properties` and `appsettings/<valid
     * app ID>.properties`, each at most [MAX_FILE_BYTES]; anything else is left out. Fails
     * without `settings.properties`.
     */
    fun read(input: InputStream): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                val wanted = name == SETTINGS || (name.startsWith(APP_SETTINGS) && appId(name.removePrefix(APP_SETTINGS)) != null)
                if (entry.isDirectory || !wanted) continue
                val bytes = ByteArrayOutputStream()
                zip.copyWithProgress(bytes) { if (it > MAX_FILE_BYTES) throw IOException("$name is too large for a settings file") }
                files[name] = bytes.toByteArray()
            }
        }
        if (SETTINGS !in files) throw IOException("Not a Flatdroid settings export: no $SETTINGS")
        return files
    }

    /** The app ID of an app-settings file name, or null for any other name. */
    fun appId(fileName: String): String? =
        fileName.takeIf { it.endsWith(SUFFIX) }?.removeSuffix(SUFFIX)?.takeIf(InstalledApps::isValidId)
}
