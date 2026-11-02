package com.garathon.flatdroid.flatpak

import android.content.Context
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.launcher.Electron
import com.garathon.flatdroid.launcher.Executable
import com.garathon.flatdroid.proot.DevNodes
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.util.readTextUpTo
import java.io.File
import java.util.Locale

/** An installed app as the home list shows it. */
data class InstalledApp(
    val id: String,
    val name: String,
    val icon: File?,
    val lastOpened: Long?,
    /** When it was installed; null if unknown. */
    val installed: Long? = null,
    /** Its metadata asks for all devices (`devices=all`). */
    val requestsAllDevices: Boolean = false,
    /** Its metadata asks for sound (`sockets=pulseaudio`). */
    val requestsAudio: Boolean = false,
    /** Its metadata asks for the network (`shared=network`). */
    val requestsNetwork: Boolean = false,
)

/** Installed apps with their names and icons, read from what each app exports. */
class InstalledApps(private val context: Context) {
    private val env = AlpineEnvironment.get(context)
    private val installation = FlatpakInstallation(env.flatpakDir, env.arch)

    fun list(): List<InstalledApp> = installation.appIds().mapNotNull { id ->
        val app = installation.app(id) ?: return@mapNotNull null
        InstalledApp(
            id = id,
            name = desktopName(app, id) ?: id,
            icon = icon(app, id),
            lastOpened = AppData.settings(context, id).load().lastOpened,
            installed = installation.installed(id),
            requestsAllDevices = DevNodes.ALL in app.metadata.context.devices,
            requestsAudio = AppSettings.PULSEAUDIO_SOCKET in app.metadata.context.sockets,
            requestsNetwork = AppSettings.NETWORK in app.metadata.context.shared,
        )
    }

    /**
     * Whether the app's command starts Electron or a Chromium browser, as the launcher sees it
     * (through zypak or cobalt): it shows web content, without Chromium's sandbox here. Reads the
     * command's script, so it is asked for one app (its settings), not for the whole list.
     * WebKitGTK apps can't be told this way (a script may load it).
     */
    fun showsWebContent(id: String): Boolean {
        val app = installation.app(id) ?: return false
        val command = app.metadata.command?.takeIf { it.isNotBlank() && !it.startsWith("/") } ?: return false
        val script = (Executable.of(File(app.files, "bin/$command")) as? Executable.Script)?.text ?: return false
        return Electron.zypakTarget(script) != null || Electron.cobaltTarget(script, id) { true } != null
    }

    fun name(id: String): String? = installation.app(id)?.let { desktopName(it, id) }

    /** The `sockets=` the app's metadata asks for; empty if it isn't installed. */
    fun sockets(id: String): List<String> = installation.app(id)?.metadata?.context?.sockets.orEmpty()

    private fun desktopName(app: Deployment, id: String): String? {
        val file = File(app.export, "share/applications/$id.desktop")
        val desktop = try {
            Keyfile.parse(file.readTextUpTo() ?: return null)
        } catch (_: Exception) {
            return null
        }
        return localizedName(desktop, Locale.getDefault())
    }

    /** A PNG when the app exports one, else its scalable SVG. */
    private fun icon(app: Deployment, id: String): File? {
        val icons = File(app.export, "share/icons/hicolor")
        return (ICON_SIZES.map { File(icons, "$it/apps/$id.png") } + File(icons, "scalable/apps/$id.svg"))
            .firstOrNull { it.isFile }
    }

    companion object {
        /** PNG sizes to try, best first. */
        private val ICON_SIZES = listOf("128x128", "256x256", "96x96", "64x64", "512x512", "48x48")

        /** A Flatpak app ID: at least two dot-separated parts of letters, digits, `_` and `-`. */
        fun isValidId(id: String): Boolean =
            id.length <= 255 && Regex("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)+").matches(id)

        /** The app IDs in [text], separated by whitespace, each once; null if there are none or any isn't valid. */
        fun parseIds(text: String): List<String>? =
            text.split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct().takeIf { ids -> ids.isNotEmpty() && ids.all(::isValidId) }

        /** `Name[ll_CC]`, then `Name[ll]`, then `Name`, from a desktop file's `[Desktop Entry]`. */
        fun localizedName(desktop: Keyfile, locale: Locale): String? {
            val group = "Desktop Entry"
            val keys = listOfNotNull(
                locale.country.takeIf { it.isNotEmpty() }?.let { "Name[${locale.language}_$it]" },
                "Name[${locale.language}]",
                "Name",
            )
            return keys.firstNotNullOfOrNull { key -> desktop.string(group, key)?.takeIf { it.isNotBlank() } }
        }
    }
}
