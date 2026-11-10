package com.garathon.flatdroid.flatpak.catalog

import com.garathon.flatdroid.flatpak.InstalledApps
import org.xmlpull.v1.XmlPullParser

/** One app as [AppStreamReader] reads it: the list's part and the page's part. */
data class CatalogEntry(val app: CatalogApp, val details: CatalogDetails)

/**
 * Reads Flatpak's AppStream catalog (`appstream.xml`) one app at a time, so the whole file (tens of
 * MB) is never in memory. Only apps with a window (`desktop-application`) and a valid Flatpak ID
 * are kept. Names, summaries, descriptions, keywords and captions are taken in the first of
 * [languages] the app has (BCP 47 tags such as `de-DE`), else the catalog's own (English); other
 * translations are skipped unread.
 */
class AppStreamReader(languages: List<String>) {
    /** The wanted languages as AppStream writes them (`pt_BR`, `de`), best first; English is the untagged text. */
    private val wanted: List<String> = languages.flatMap { tag ->
        val normal = tag.replace('-', '_')
        listOf(normal, normal.substringBefore('_'))
    }.map { it.lowercase() }.distinct()

    /** Lower is better; the untagged text ranks where English is wanted (or last); [Int.MAX_VALUE] for a language not wanted. */
    fun rank(lang: String?): Int {
        if (lang == null) return wanted.indexOfFirst { it == "en" || it.startsWith("en_") }.takeIf { it >= 0 } ?: wanted.size
        val l = lang.replace('-', '_').lowercase()
        val exact = wanted.indexOf(l)
        if (exact >= 0) return exact
        val base = wanted.indexOf(l.substringBefore('_'))
        return if (base >= 0) base else Int.MAX_VALUE
    }

    /** A value in several languages: keeps the best ranked one. */
    private inner class Best<T> {
        var value: T? = null
        var rank = Int.MAX_VALUE

        /** Whether a value in [lang] would replace the one kept: when not, it isn't even read. */
        fun wants(lang: String?) = rank(lang) < rank

        fun offer(lang: String?, v: T) {
            val r = rank(lang)
            if (r < rank) {
                value = v
                rank = r
            }
        }
    }

    /** Calls [onEntry] for each app in the catalog [parser] reads (positioned at its start). */
    fun read(parser: XmlPullParser, onEntry: (CatalogEntry) -> Unit) {
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "component") {
                val type = parser.getAttributeValue(null, "type")
                if (type == "desktop-application" || type == "desktop") component(parser)?.let(onEntry) else skip(parser)
            }
        }
    }

    private fun component(parser: XmlPullParser): CatalogEntry? {
        var id: String? = null
        val name = Best<String>()
        val summary = Best<String>()
        val description = Best<List<TextBlock>>()
        var developer: String? = null
        var developerName: String? = null
        val categories = mutableListOf<String>()
        val defaultKeywords = mutableListOf<String>()
        val keywords = Best<List<String>>()
        var icon: String? = null
        var iconSize = 0
        var touch = false
        var verified = false
        var license: String? = null
        val urls = mutableListOf<Pair<String, String>>()
        val screenshots = mutableListOf<Screenshot>()
        val depth = parser.depth
        while (next(parser, depth)) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            val lang = lang(parser)
            when (parser.name) {
                "id" -> id = text(parser).trim()
                "name" -> if (name.wants(lang)) name.offer(lang, text(parser).trim()) else skip(parser)
                "summary" -> if (summary.wants(lang)) summary.offer(lang, text(parser).trim()) else skip(parser)
                "description" -> if (description.wants(lang)) description.offer(lang, description(parser)) else skip(parser)
                "developer" -> developer = developer(parser) ?: developer
                "developer_name" -> if (lang == null) developerName = text(parser).trim() else skip(parser)
                "project_license" -> license = text(parser).trim()
                "categories" -> children(parser, "category") { categories += text(it).trim() }
                "keywords" -> if (lang != null && !keywords.wants(lang)) skip(parser) else {
                    val list = mutableListOf<String>()
                    children(parser, "keyword") { list += text(it).trim() }
                    if (lang == null) defaultKeywords += list else keywords.offer(lang, list)
                }
                "icon" -> {
                    val width = parser.getAttributeValue(null, "width")?.toIntOrNull() ?: 0
                    val cached = parser.getAttributeValue(null, "type") == "cached" && parser.getAttributeValue(null, "scale") == null
                    val file = text(parser).trim()
                    // The largest cached one up to 128 px (the folders Flatpak keeps are 64x64 and 128x128).
                    if (cached && width in maxOf(iconSize + 1, 64)..128 && file.isNotEmpty() && '/' !in file) {
                        icon = "${width}x$width/$file"
                        iconSize = width
                    }
                }
                "url" -> {
                    val kind = parser.getAttributeValue(null, "type") ?: "homepage"
                    val link = text(parser).trim()
                    if (link.startsWith("https://") || link.startsWith("http://")) urls += kind to link
                }
                "screenshots" -> children(parser, "screenshot") { screenshot(it)?.let(screenshots::add) }
                "supports", "recommends", "requires" -> children(parser, "control") { if (text(it).trim() == "touch") touch = true }
                "custom" -> children(parser, "value") {
                    val key = it.getAttributeValue(null, "key")
                    val value = text(it).trim()
                    if (key == VERIFIED && value.equals("true", ignoreCase = true)) verified = true
                }
                else -> skip(parser)
            }
        }
        val appId = id?.removeSuffix(".desktop")?.takeIf(InstalledApps::isValidId) ?: return null
        val app = CatalogApp(
            id = appId,
            name = name.value?.takeIf { it.isNotEmpty() } ?: appId,
            summary = summary.value.orEmpty(),
            developer = developer ?: developerName,
            verified = verified,
            categories = categories.distinct(),
            keywords = (defaultKeywords + keywords.value.orEmpty()).filter { it.isNotEmpty() }.distinct(),
            icon = icon,
            touch = touch,
        )
        return CatalogEntry(app, CatalogDetails(description.value.orEmpty(), license, urls, screenshots))
    }

    /** `<developer><name>…</name></developer>`: the untagged name. */
    private fun developer(parser: XmlPullParser): String? {
        var name: String? = null
        children(parser, "name") { if (lang(it) == null) name = text(it).trim() else skip(it) }
        return name
    }

    private fun screenshot(parser: XmlPullParser): Screenshot? {
        val caption = Best<String>()
        // Pictures can be per language (a translated interface): by language rank, the source and the thumbnails by width.
        val sources = mutableMapOf<Int, String>()
        val thumbnails = mutableListOf<Triple<Int, Int, String>>()
        val depth = parser.depth
        while (next(parser, depth)) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "caption" -> lang(parser).let { lang -> if (caption.wants(lang)) caption.offer(lang, text(parser).trim()) else skip(parser) }
                "image" -> {
                    val rank = rank(lang(parser))
                    if (rank == Int.MAX_VALUE) {
                        skip(parser)
                        continue
                    }
                    val type = parser.getAttributeValue(null, "type")
                    val width = parser.getAttributeValue(null, "width")?.toIntOrNull() ?: 0
                    val link = text(parser).trim()
                    if (link.startsWith("https://")) {
                        if (type == "source") sources.putIfAbsent(rank, link) else thumbnails += Triple(rank, width, link)
                    }
                }
                else -> skip(parser)
            }
        }
        // The best language there are pictures in; then the smallest thumbnail that is still sharp on a phone.
        val best = (sources.keys + thumbnails.map { it.first }).minOrNull() ?: return null
        val sized = thumbnails.filter { it.first == best }.map { it.second to it.third }
        val source = sources[best]
        val image = sized.filter { it.first >= SCREENSHOT_WIDTH }.minByOrNull { it.first }?.second
            ?: sized.maxByOrNull { it.first }?.second ?: source ?: return null
        return Screenshot(image, source, caption.value?.takeIf { it.isNotEmpty() })
    }

    /** `<p>`, `<ul>` and `<ol>` of a description, with inline markup (`<em>`, `<code>`) as plain text. */
    private fun description(parser: XmlPullParser): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        val depth = parser.depth
        while (next(parser, depth)) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "p" -> text(parser).let(::collapse).takeIf { it.isNotEmpty() }?.let { blocks += TextBlock.Paragraph(it) }
                "ul", "ol" -> {
                    val ordered = parser.name == "ol"
                    val items = mutableListOf<String>()
                    children(parser, "li") { text(it).let(::collapse).takeIf(String::isNotEmpty)?.let(items::add) }
                    if (items.isNotEmpty()) blocks += TextBlock.Items(ordered, items)
                }
                else -> skip(parser)
            }
        }
        return blocks
    }

    private fun lang(parser: XmlPullParser): String? = parser.getAttributeValue(XML_NS, "lang") ?: parser.getAttributeValue(null, "xml:lang")

    /** Calls [each] for every child named [name] of the current element; skips the others. */
    private fun children(parser: XmlPullParser, name: String, each: (XmlPullParser) -> Unit) {
        val depth = parser.depth
        while (next(parser, depth)) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name == name) each(parser) else skip(parser)
        }
    }

    /** Advances inside the element at [depth]; false at its end tag. */
    private fun next(parser: XmlPullParser, depth: Int): Boolean {
        val event = parser.next()
        return !(event == XmlPullParser.END_DOCUMENT || (event == XmlPullParser.END_TAG && parser.depth == depth))
    }

    /** All text inside the current element, nested elements included; ends at its end tag. */
    private fun text(parser: XmlPullParser): String {
        val out = StringBuilder()
        val depth = parser.depth
        while (next(parser, depth)) {
            if (parser.eventType == XmlPullParser.TEXT) out.append(parser.text)
        }
        return out.toString()
    }

    /** Skips the current element and everything in it. */
    private fun skip(parser: XmlPullParser) {
        val depth = parser.depth
        while (next(parser, depth)) Unit
    }

    private fun collapse(text: String) = text.replace(WHITESPACE, " ").trim()

    companion object {
        private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
        private const val VERIFIED = "flathub::verification::verified"
        /** Screenshots are shown about a phone's width: the smallest thumbnail at least this wide. */
        private const val SCREENSHOT_WIDTH = 600
        private val WHITESPACE = Regex("\\s+")
    }
}
