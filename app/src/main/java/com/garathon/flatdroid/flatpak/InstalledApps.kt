package com.garathon.flatdroid.flatpak

import android.content.Context
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.proot.DevNodes
import com.garathon.flatdroid.util.readTextUpTo
import java.io.File
import java.util.Locale

/** An installed app as the home list shows it. */
data class InstalledApp(
    val id: String,
    val name: String,
    val icon: File?,
    val lastOpened: Long?,
    /** Its metadata asks for all devices (`devices=all`). */
    val requestsAllDevices: Boolean = false,
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
            requestsAllDevices = DevNodes.ALL in app.metadata.context.devices,
        )
    }

    fun name(id: String): String? = installation.app(id)?.let { desktopName(it, id) }

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
