package com.garathon.flatdroid.flatpak.catalog

import android.content.Context
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.memory.MemoryCache
import coil3.request.Options
import com.garathon.flatdroid.settings.MobileData
import com.garathon.flatdroid.settings.mobileDataPolicy
import kotlinx.coroutines.runInterruptible
import okio.Buffer
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Screenshots for the browser: Coil with Flatdroid's own fetcher (Android's HttpURLConnection, no
 * network library of its own, so no threads of its own besides Coil's use of the shared IO pool),
 * https only, a size limit, and nothing on mobile data when downloads there are off. Kept in the
 * catalog's cache folder for one run of Flatdroid ([clearAtStart]), and dropped when the index is
 * rebuilt; the loader (with the pictures it holds in memory) goes when the browser is left ([release]).
 */
object CatalogImages {
    private const val MAX_BYTES = 12 * 1024 * 1024
    private const val DISK_BYTES = 64L * 1024 * 1024
    private const val TIMEOUT_MS = 15_000

    @Volatile private var loader: ImageLoader? = null

    /**
     * Apps whose screenshots the user asked for on mobile data (downloads there set to Ask), one
     * page at a time: shown again without asking for the rest of this run, as they are on disk by
     * then. Forgotten with the pictures (at start, on a rebuild).
     */
    private val allowedOnMobileData = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun allowedOnMobileData(appId: String) = appId in allowedOnMobileData

    fun allowOnMobileData(appId: String) {
        allowedOnMobileData += appId
    }

    fun dir(context: Context) = File(context.cacheDir, "catalog/images")

    fun loader(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: build(context.applicationContext).also { loader = it }
    }

    /** The browser was left: its pictures leave memory (the disk copies stay for this run). */
    fun release() = synchronized(this) {
        loader?.let { it.memoryCache?.clear(); it.shutdown() }
        loader = null
    }

    /** At Flatdroid's start: pictures from an earlier run are dropped (with whatever else is in the catalog's cache folder). */
    fun clearAtStart(context: Context) = synchronized(this) {
        if (loader == null) dir(context).parentFile?.deleteRecursively()
    }

    private fun build(context: Context) = ImageLoader.Builder(context)
        .components { add(HttpsFetcher.Factory()) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.1).build() }
        .diskCache { DiskCache.Builder().directory(dir(context).toOkioPath()).maxSizeBytes(DISK_BYTES).build() }
        .build()

    /** Drops every saved picture, in memory and on disk. */
    fun clear(context: Context) = synchronized(this) {
        allowedOnMobileData.clear()
        loader?.let { it.memoryCache?.clear(); it.diskCache?.clear() } ?: dir(context).deleteRecursively()
    }

    /** Loads an https picture into Coil's disk cache, then decodes from there. */
    private class HttpsFetcher(private val url: String, private val options: Options, private val loader: ImageLoader) : Fetcher {
        override suspend fun fetch(): FetchResult {
            val cache = loader.diskCache
            cache?.openSnapshot(url)?.let { snapshot ->
                return SourceFetchResult(ImageSource(snapshot.data, cache.fileSystem, diskCacheKey = url, closeable = snapshot), null, DataSource.DISK)
            }
            if (mobileDataPolicy(options.context) == MobileData.NEVER) throw IOException("Downloads on mobile data are off")
            val bytes = runInterruptible { download(url) }
            if (cache != null) {
                cache.openEditor(url)?.let { editor ->
                    try {
                        cache.fileSystem.write(editor.data) { write(bytes) }
                        editor.commitAndOpenSnapshot()?.let { snapshot ->
                            return SourceFetchResult(ImageSource(snapshot.data, cache.fileSystem, diskCacheKey = url, closeable = snapshot), null, DataSource.NETWORK)
                        }
                    } catch (e: Exception) {
                        editor.abort()
                        if (e !is IOException) throw e
                    }
                }
            }
            return SourceFetchResult(ImageSource(Buffer().write(bytes), cache?.fileSystem ?: okio.FileSystem.SYSTEM), null, DataSource.NETWORK)
        }

        private fun download(url: String): ByteArray {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", "Flatdroid")
                // Only https all the way: a redirect to another scheme isn't followed by HttpURLConnection anyway.
                if (connection.responseCode != 200) throw IOException("Answered ${connection.responseCode}")
                if (connection.url.protocol != "https") throw IOException("Not https")
                return connection.inputStream.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (out.size() + read > MAX_BYTES) throw IOException("Picture too large")
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }

        class Factory : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
                if (data.scheme == "https") HttpsFetcher(data.toString(), options, imageLoader) else null
        }
    }
}
