package com.garathon.flatdroid.flatpak

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What an app's install confirmation shows. Sizes are bytes, null when Flathub doesn't say. */
data class FlathubApp(
    val id: String,
    val name: String,
    val summary: String?,
    /** The architectures it is built for, as Flathub names them (`aarch64`, `x86_64`). */
    val arches: List<String>,
    val downloadSize: Long?,
    val installedSize: Long?,
    /** The runtime it needs, as Flathub names it (`GNOME Application Platform version 51`), what that takes once installed, and its ref (for Flathub's architecture, not ours). */
    val runtime: String?,
    val runtimeSize: Long?,
    val runtimeRef: FlatpakRef?,
    val permissions: Set<Permission>,
    /** Flathub verified that the app comes from who it says (its developer's website or account). */
    val verified: Boolean = false,
) {
    /** What an app asks for that Flatdroid shows, as it does for installed apps. */
    enum class Permission { NETWORK, SOUND, ALL_DEVICES, FILES }

    fun builtFor(arch: String) = arch in arches
}

/** Flathub has no app with this ID. */
class FlathubNotFoundException(id: String) : IOException("No app $id on Flathub")

/** Flathub answered, but with an error status other than not found. */
class FlathubHttpException(val code: Int) : IOException("Flathub answered $code")

/** Flathub's answer isn't what this client reads (its API changed, or the answer was cut short). */
class FlathubBadAnswerException(cause: Throwable) : IOException("Flathub's answer couldn't be read: ${cause.message}", cause)

/**
 * Asks Flathub's public API about one app (`/summary` for sizes, architectures and permissions,
 * `/appstream` for the name and summary), so a link can show what it would install. Network
 * work: call it off the main thread.
 */
object FlathubClient {
    private const val API = "https://flathub.org/api/v2"
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val VERIFIED = "flathub::verification::verified"

    /** [id] must be a valid app ID (it goes into the address). */
    fun fetch(id: String): FlathubApp {
        require(InstalledApps.isValidId(id)) { "Not an app ID: $id" }
        val summary = get("$API/summary/$id", id)
        // The name, summary and verification are nice to have; the summary's facts are what matter.
        val appstream = runCatching { get("$API/appstream/$id", id) }.getOrNull()
        return try {
            parse(id, summary, appstream)
        } catch (e: JSONException) {
            throw FlathubBadAnswerException(e)
        }
    }

    /** Builds the confirmation's data from the two JSON answers. */
    fun parse(id: String, summaryJson: String, appstreamJson: String?): FlathubApp {
        val summary = JSONObject(summaryJson)
        val appstream = appstreamJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val metadata = summary.optJSONObject("metadata")
        val permissions = metadata?.optJSONObject("permissions")
        val filesystems = permissions.strings("filesystems")
        return FlathubApp(
            id = id,
            name = appstream?.optString("name")?.takeIf { it.isNotBlank() } ?: id,
            summary = appstream?.optString("summary")?.takeIf { it.isNotBlank() },
            arches = summary.optJSONArray("arches").strings(),
            downloadSize = summary.longOrNull("download_size"),
            installedSize = summary.longOrNull("installed_size"),
            runtime = metadata?.optString("runtimeName")?.takeIf { it.isNotBlank() },
            runtimeSize = metadata.longOrNull("runtimeInstalledSize"),
            runtimeRef = metadata?.optString("runtime")?.takeIf { it.isNotBlank() }?.let(FlatpakRef::parse),
            permissions = buildSet {
                if ("network" in permissions.strings("shared")) add(FlathubApp.Permission.NETWORK)
                if ("pulseaudio" in permissions.strings("sockets")) add(FlathubApp.Permission.SOUND)
                if ("all" in permissions.strings("devices")) add(FlathubApp.Permission.ALL_DEVICES)
                // As InstalledApp.requestsFiles does: the home folder or all of the host's.
                if (filesystems.any { it.substringBefore(':') in setOf("home", "host", "host-os") }) add(FlathubApp.Permission.FILES)
            },
            // A JSON true, or "true" as AppStream's own custom values are strings.
            verified = appstream?.optJSONObject("metadata")?.optBoolean(VERIFIED) == true,
        )
    }

    private fun get(url: String, id: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Flatdroid")
            when (val code = connection.responseCode) {
                200 -> Unit
                404 -> throw FlathubNotFoundException(id)
                else -> throw FlathubHttpException(code)
            }
            return connection.inputStream.use { stream ->
                val bytes = stream.readNBytesLimited(MAX_BYTES)
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: FileNotFoundException) {
            throw FlathubNotFoundException(id)
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) throw IOException("Answer too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun JSONObject?.longOrNull(key: String): Long? = this?.takeIf { has(key) && !isNull(key) }?.optLong(key)?.takeIf { it >= 0 }

    private fun JSONObject?.strings(key: String): List<String> = this?.optJSONArray(key).strings()

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }
}
