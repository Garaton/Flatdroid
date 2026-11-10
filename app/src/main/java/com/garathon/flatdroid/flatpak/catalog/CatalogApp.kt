package com.garathon.flatdroid.flatpak.catalog

/** An app of Flathub's catalog as the browser lists it: what a row, the search and the filters need. */
data class CatalogApp(
    val id: String,
    val name: String,
    val summary: String,
    val developer: String?,
    /** Flathub verified that the app comes from who it says. */
    val verified: Boolean,
    /** AppStream's main categories (`Game`, `Utility`, …). */
    val categories: List<String>,
    val keywords: List<String>,
    /** The icon's file name in the catalog's icon folders (`128x128/<name>`, `64x64/<name>`); null when it has none. */
    val icon: String?,
    /** It says it can be used by touch. */
    val touch: Boolean,
)

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
