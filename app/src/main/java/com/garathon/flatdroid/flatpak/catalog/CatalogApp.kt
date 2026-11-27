package com.garathon.flatdroid.flatpak.catalog

/** An app of Flathub's catalog as the browser lists it: what a row, the search and the filters need. */
data class CatalogApp(
    val id: String,
    val name: String,
    val summary: String,
    val developer: String?,
    /** AppStream's main categories (`Game`, `Utility`, …). */
    val categories: List<String>,
    val keywords: List<String>,
    /** The icon's file name in the catalog's icon folders (`128x128/<name>`, `64x64/<name>`); null when it has none. */
    val icon: String?,
    /** The runtime it needs, as `<ID>/<branch>` (`org.gnome.Platform/49`); null when the catalog doesn't say. */
    val runtime: String? = null,
    /** Its license is proprietary ([isProprietary]): the browser can leave such apps out. */
    val proprietary: Boolean = false,
) {
    companion object {
        /**
         * Whether AppStream's `project_license` names a proprietary license: Flathub's convention is
         * `LicenseRef-proprietary` (alone, or with `=<terms URL>`, or in an expression). An app with no
         * license given isn't counted as proprietary.
         */
        fun isProprietary(license: String?): Boolean = license?.contains(PROPRIETARY, ignoreCase = true) == true

        private const val PROPRIETARY = "LicenseRef-proprietary"
    }
}

/** What an app's page adds to [CatalogApp]: read from the index when the page opens. */
data class CatalogDetails(
    val description: List<TextBlock>,
    val license: String?,
    /** The app's links by AppStream type (`homepage`, `bugtracker`, `donation`, …), in the catalog's order. */
    val urls: List<Pair<String, String>>,
    val screenshots: List<Screenshot>,
)

/** A screenshot: a picture sized for a phone ([image]), the original ([full]) and its caption. */
data class Screenshot(val image: String, val full: String?, val caption: String?)

/** A part of an AppStream description: a paragraph or a list. */
sealed interface TextBlock {
    data class Paragraph(val text: String) : TextBlock
    data class Items(val ordered: Boolean, val items: List<String>) : TextBlock
}
