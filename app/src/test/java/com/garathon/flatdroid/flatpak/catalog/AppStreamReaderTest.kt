package com.garathon.flatdroid.flatpak.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

class AppStreamReaderTest {
    @get:Rule val folder = TemporaryFolder()

    // Shortened from Flathub's aarch64 catalog (org.gnome.Maps), plus entries that must be left out.
    private val catalog = """
        <components version="0.8" origin="flatpak">
          <component type="desktop-application">
            <id>org.gnome.Maps</id>
            <name>Maps</name>
            <name xml:lang="de">Karten</name>
            <summary>Find places around the world</summary>
            <summary xml:lang="de">Orte auf der ganzen Welt finden</summary>
            <project_license>GPL-2.0-or-later</project_license>
            <description xml:lang="de"><p>Karten gibt Ihnen schnellen Zugang.</p></description>
            <description>
              <p>
              Maps gives you quick access to maps all across the world. It uses <em>OpenStreetMap</em>.
            </p>
              <ul><li>Search places</li><li>Routes</li></ul>
            </description>
            <developer id="org.gnome"><name>The GNOME Project</name></developer>
            <requires><display_length>360</display_length></requires>
            <supports><control>keyboard</control><control>touch</control></supports>
            <icon height="64" type="cached" width="64">org.gnome.Maps.png</icon>
            <icon height="64" scale="2" type="cached" width="64">org.gnome.Maps.png</icon>
            <icon height="128" type="cached" width="128">org.gnome.Maps.png</icon>
            <icon height="128" type="remote" width="128">https://dl.flathub.org/media/icons/128x128/org.gnome.Maps.png</icon>
            <url type="homepage">https://apps.gnome.org/Maps/</url>
            <url type="donation">javascript:alert(1)</url>
            <categories><category>Utility</category></categories>
            <screenshots>
              <screenshot type="default">
                <caption>Map view</caption>
                <caption xml:lang="de">Kartenansicht</caption>
                <image height="1186" type="source" width="1975">https://dl.flathub.org/s/image-1_orig.png</image>
                <image height="749" type="thumbnail" width="1248">https://dl.flathub.org/s/image-1_1248.png</image>
                <image height="374" type="thumbnail" width="624">https://dl.flathub.org/s/image-1_624.png</image>
                <image height="134" type="thumbnail" width="224">https://dl.flathub.org/s/image-1_224.png</image>
              </screenshot>
              <screenshot><image type="thumbnail" width="624">http://insecure.example/x.png</image></screenshot>
              <screenshot>
                <image type="thumbnail" width="624" xml:lang="ru">https://dl.flathub.org/s/image-2_624_ru.png</image>
                <image type="thumbnail" width="624">https://dl.flathub.org/s/image-2_624.png</image>
                <image type="thumbnail" width="624" xml:lang="de">https://dl.flathub.org/s/image-2_624_de.png</image>
              </screenshot>
            </screenshots>
            <keywords><keyword>maps</keyword><keyword>location finder</keyword></keywords>
            <keywords xml:lang="de"><keyword>Karte</keyword></keywords>
            <custom><value key="flathub::verification::verified">true</value></custom>
            <releases><release version="49.0" timestamp="1"><description><p>Not this one.</p></description></release></releases>
          </component>
          <component type="console-application"><id>org.example.Tool</id><name>Tool</name></component>
          <component type="runtime"><id>org.gnome.Platform</id><name>GNOME</name></component>
          <component type="desktop-application"><id>not an id</id><name>Broken</name></component>
          <component type="desktop"><id>org.example.Old.desktop</id><name>Old</name><developer_name>Someone</developer_name></component>
        </components>
    """.trimIndent()

    private fun read(languages: List<String>, namespaces: Boolean = false): List<CatalogEntry> {
        val reader = AppStreamReader(languages)
        val parser = KXmlParser()
        // Android's Xml.newPullParser() reads namespaces; both must work.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, namespaces)
        parser.setInput(catalog.byteInputStream(), "UTF-8")
        val entries = mutableListOf<CatalogEntry>()
        reader.read(parser) { entries += it }
        return entries
    }

    @Test
    fun readsWhatTheBrowserShows() {
        assertEquals(read(listOf("en-US")), read(listOf("en-US"), namespaces = true))
        val entries = read(listOf("en-US"))
        assertEquals(listOf("org.gnome.Maps", "org.example.Old"), entries.map { it.app.id })
        val maps = entries.first()
        assertEquals("Maps", maps.app.name)
        assertEquals("Find places around the world", maps.app.summary)
        assertEquals("The GNOME Project", maps.app.developer)
        assertTrue(maps.app.verified)
        assertTrue(maps.app.touch)
        assertEquals(listOf("Utility"), maps.app.categories)
        assertEquals("128x128/org.gnome.Maps.png", maps.app.icon)
        assertEquals(listOf("maps", "location finder"), maps.app.keywords)
        assertEquals(
            listOf(
                TextBlock.Paragraph("Maps gives you quick access to maps all across the world. It uses OpenStreetMap."),
                TextBlock.Items(false, listOf("Search places", "Routes")),
            ),
            maps.details.description,
        )
        assertEquals("GPL-2.0-or-later", maps.details.license)
        // Only web links.
        assertEquals(listOf("homepage" to "https://apps.gnome.org/Maps/"), maps.details.urls)
        // The smallest thumbnail that is still sharp on a phone; only https pictures.
        // Pictures in a language: the wanted one's, else the untagged ones.
        assertEquals(
            listOf(
                Screenshot("https://dl.flathub.org/s/image-1_624.png", "https://dl.flathub.org/s/image-1_orig.png", "Map view"),
                Screenshot("https://dl.flathub.org/s/image-2_624.png", null, null),
            ),
            maps.details.screenshots,
        )

        val old = entries[1].app
        assertEquals("Someone", old.developer)
        assertFalse(old.verified)
        assertNull(old.icon)
    }

    @Test
    fun takesTheWantedLanguage() {
        for (namespaces in listOf(false, true)) {
            val maps = read(listOf("de-DE", "en-US"), namespaces).first()
            assertEquals("Karten", maps.app.name)
            assertEquals("Orte auf der ganzen Welt finden", maps.app.summary)
            assertEquals(listOf(TextBlock.Paragraph("Karten gibt Ihnen schnellen Zugang.")), maps.details.description)
            assertEquals("Kartenansicht", maps.details.screenshots.first().caption)
            assertEquals("https://dl.flathub.org/s/image-2_624_de.png", maps.details.screenshots[1].image)
            // The untagged keywords stay searchable too.
            assertEquals(listOf("maps", "location finder", "Karte"), maps.app.keywords)
        }
        // English first: the untagged text, though German is also wanted.
        assertEquals("Maps", read(listOf("en-GB", "de")).first().app.name)
        // A language the catalog doesn't have: the untagged text.
        assertEquals("Maps", read(listOf("fr-FR")).first().app.name)
    }

    @Test
    fun theIndexKeepsWhatWasRead() {
        val entries = read(listOf("en-US"))
        val dir = folder.newFolder("catalog", "index")
        val written = CatalogIndex.write(dir, "abc|en-US") { add -> entries.forEach(add) }
        assertEquals(entries.map { it.app }, written.apps)
        val again = CatalogIndex.read(dir)!!
        assertEquals("abc|en-US", again.version)
        assertEquals(entries.map { it.app }, again.apps)
        assertEquals(entries.first().details, again.details("org.gnome.Maps"))
        assertEquals(entries[1].details, again.details("org.example.Old"))
        assertNull(again.details("org.example.Missing"))
        // Nothing half-written is left beside it.
        assertFalse(java.io.File(dir.parentFile, "index.new").exists())
    }
}
