package com.garathon.flatdroid.session.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor

class PortalTest {
    private var status = Portal.NetworkStatus(available = true, metered = false, connectivity = 4u)
    private val settings = mapOf(Portal.APPEARANCE to mapOf(Portal.COLOR_SCHEME to Variant("u", Portal.PREFER_DARK)))
    private val opened = mutableListOf<String>()
    private var shows = true
    private val links = object : Portal.Links {
        override fun openUri(uri: String) = shows.also { opened += uri }
        override fun openFile(fd: FileDescriptor, writable: Boolean) = shows.also { opened += "file writable=$writable" }
        override fun openDirectory(fd: FileDescriptor) = shows.also { opened += "folder" }
    }
    private val portal = Portal(Portal.Role.PORTAL, { settings }, { status }, links)
    private val backend = Portal(Portal.Role.BACKEND, { settings }, { status }, links)

    private fun call(iface: String, member: String, signature: String = "", vararg body: Any?, path: String = Portal.PATH) =
        Message.call(Portal.PORTAL_NAME, path, iface, member, signature, *body).copy(serial = 3u, sender = ":1.9")

    /** Everything [to] sends for [message], after a trip over the wire as the bus delivers it. */
    private fun all(message: Message, to: Portal = portal, fds: List<FileDescriptor> = emptyList()): List<Message> =
        to.handle(DBusWire.decode(DBusWire.encode(message, message.serial)), fds).map { DBusWire.decode(DBusWire.encode(it, 4u)) }

    /** [message]'s reply from [to]. */
    private fun ask(message: Message, to: Portal = portal): Message = all(message, to).first()

    @Test
    fun ownsThePortalOrItsBackendName() {
        assertEquals(listOf("org.freedesktop.portal.Desktop"), portal.names)
        assertEquals(listOf("org.freedesktop.impl.portal.desktop.flatdroid"), backend.names)
    }

    @Test
    fun repliesGoToTheCaller() {
        val reply = ask(call(Portal.SETTINGS, "ReadOne", "ss", Portal.APPEARANCE, Portal.COLOR_SCHEME))
        assertEquals(DBusWire.METHOD_RETURN, reply.type)
        assertEquals(3u, reply.replySerial)
        assertEquals(":1.9", reply.destination)
    }

    @Test
    fun readsTheColorScheme() {
        assertEquals(listOf(Variant("u", 1u)), ask(call(Portal.SETTINGS, "ReadOne", "ss", Portal.APPEARANCE, Portal.COLOR_SCHEME)).body)
        // The first version's Read wraps the value once more.
        assertEquals(listOf(Variant("v", Variant("u", 1u))), ask(call(Portal.SETTINGS, "Read", "ss", Portal.APPEARANCE, Portal.COLOR_SCHEME)).body)
        val missing = ask(call(Portal.SETTINGS, "ReadOne", "ss", Portal.APPEARANCE, "accent-color"))
        assertEquals("org.freedesktop.portal.Error.NotFound", missing.errorName)
    }

    @Test
    fun readAllByNamespace() {
        val all = mapOf(Portal.APPEARANCE to mapOf(Portal.COLOR_SCHEME to Variant("u", 1u)))
        assertEquals(all, ask(call(Portal.SETTINGS, "ReadAll", "as", emptyList<String>())).body[0])
        assertEquals(all, ask(call(Portal.SETTINGS, "ReadAll", "as", listOf("org.freedesktop.*"))).body[0])
        assertEquals(all, ask(call(Portal.SETTINGS, "ReadAll", "as", listOf("org.kde.kdeglobals.*", Portal.APPEARANCE))).body[0])
        assertEquals(emptyMap<String, Any>(), ask(call(Portal.SETTINGS, "ReadAll", "as", listOf("org.gnome.desktop.interface"))).body[0])
        assertTrue(Portal.matches(listOf(""), "anything"))
    }

    @Test
    fun theBackendServesTheSettingsOnly() {
        assertEquals(listOf(Variant("u", 1u)), ask(call(Portal.IMPL_SETTINGS, "Read", "ss", Portal.APPEARANCE, Portal.COLOR_SCHEME), backend).body)
        assertEquals("org.freedesktop.DBus.Error.UnknownInterface", ask(call(Portal.SETTINGS, "ReadOne", "ss", "a", "b"), backend).errorName)
        assertEquals("org.freedesktop.DBus.Error.UnknownInterface", ask(call(Portal.NETWORK_MONITOR, "GetAvailable"), backend).errorName)
        assertEquals("org.freedesktop.DBus.Error.UnknownInterface", ask(call(Portal.IMPL_SETTINGS, "ReadAll", "as", emptyList<String>())).errorName)
        assertEquals(Portal.IMPL_SETTINGS, backend.settingChanged(Portal.APPEARANCE, Portal.COLOR_SCHEME, Variant("u", 2u)).iface)
        assertNull(backend.networkChanged())
    }

    @Test
    fun versions() {
        assertEquals(listOf(Variant("u", 2u)), ask(call(Portal.PROPERTIES, "Get", "ss", Portal.SETTINGS, "version")).body)
        assertEquals(listOf(Variant("u", 3u)), ask(call(Portal.PROPERTIES, "Get", "ss", Portal.NETWORK_MONITOR, "version")).body)
        assertEquals(listOf(mapOf("version" to Variant("u", 1u))), ask(call(Portal.PROPERTIES, "GetAll", "s", Portal.PROXY_RESOLVER)).body)
        // An interface Flatdroid doesn't serve has no version: apps that check one (Chromium's file chooser) see it missing.
        assertEquals("org.freedesktop.DBus.Error.UnknownInterface", ask(call(Portal.PROPERTIES, "Get", "ss", "org.freedesktop.portal.FileChooser", "version")).errorName)
        assertEquals("org.freedesktop.DBus.Error.PropertyReadOnly", ask(call(Portal.PROPERTIES, "Set", "ssv", Portal.SETTINGS, "version", Variant("u", 9u))).errorName)
    }

    @Test
    fun theNetworkAsTheAppMayUseIt() {
        assertEquals(listOf(true), ask(call(Portal.NETWORK_MONITOR, "GetAvailable")).body)
        status = Portal.NetworkStatus(available = false, metered = true, connectivity = 1u)
        val reply = ask(call(Portal.NETWORK_MONITOR, "GetStatus")).body[0]
        assertEquals(mapOf("available" to Variant("b", false), "metered" to Variant("b", true), "connectivity" to Variant("u", 1u)), reply)
        assertEquals(listOf(false), ask(call(Portal.NETWORK_MONITOR, "CanReach", "su", "example.org", 443u)).body)
        assertEquals(listOf(1u), ask(call(Portal.NETWORK_MONITOR, "GetConnectivity")).body)
        assertEquals(Portal.NETWORK_MONITOR, portal.networkChanged()!!.iface)
    }

    @Test
    fun proxiesAreDirect() {
        assertEquals(listOf(listOf("direct://")), ask(call(Portal.PROXY_RESOLVER, "Lookup", "s", "https://flathub.org")).body)
    }

    @Test
    fun wrongCallsGetErrors() {
        assertEquals("org.freedesktop.DBus.Error.InvalidArgs", ask(call(Portal.SETTINGS, "ReadOne", "s", "x")).errorName)
        assertEquals("org.freedesktop.DBus.Error.UnknownMethod", ask(call(Portal.SETTINGS, "Write", "ss", "a", "b")).errorName)
        assertEquals("org.freedesktop.DBus.Error.UnknownObject", ask(call(Portal.SETTINGS, "ReadOne", "ss", "a", "b", path = "/other")).errorName)
        assertEquals("org.freedesktop.DBus.Error.UnknownInterface", ask(call("org.freedesktop.portal.FileChooser", "OpenFile")).errorName)
        assertEquals(DBusWire.METHOD_RETURN, ask(call(Portal.PEER, "Ping", path = "/anywhere")).type)
        assertTrue("org.freedesktop.portal.Settings" in ask(call(Portal.INTROSPECTABLE, "Introspect")).body[0] as String)
    }

    @Test
    fun noReplyWhenNoneIsExpected() {
        val quiet = call(Portal.SETTINGS, "ReadOne", "ss", Portal.APPEARANCE, Portal.COLOR_SCHEME).copy(flags = DBusWire.NO_REPLY_EXPECTED)
        assertEquals(emptyList<Message>(), portal.handle(quiet))
        assertEquals(emptyList<Message>(), portal.handle(Message.signal(Portal.PATH, Portal.SETTINGS, "SettingChanged")))
    }

    @Test
    fun linksAnswerWithARequestAndItsResponse() {
        val options = mapOf("handle_token" to Variant("s", "t1"))
        val (reply, response) = all(call(Portal.OPEN_URI, "OpenURI", "ssa{sv}", "", "https://flathub.org", options))
        val handle = "/org/freedesktop/portal/desktop/request/1_9/t1"
        assertEquals(listOf(handle), reply.body)
        assertEquals(handle, response.path)
        assertEquals("org.freedesktop.portal.Request", response.iface)
        assertEquals("Response", response.member)
        assertEquals(":1.9", response.destination)
        assertEquals(listOf(0u, emptyMap<String, Any>()), response.body)
        assertEquals(listOf("https://flathub.org"), opened)
    }

    @Test
    fun refusedOrUnshownLinksRespondTwo() {
        val refused = all(call(Portal.OPEN_URI, "OpenURI", "ssa{sv}", "", "file:///etc/passwd", emptyMap<String, Variant>()))
        assertEquals(2u, refused[1].body[0])
        assertTrue(opened.isEmpty())
        shows = false
        val hidden = all(call(Portal.OPEN_URI, "OpenURI", "ssa{sv}", "", "mailto:a@example.org", mapOf("handle_token" to Variant("s", "bad/token"))))
        assertEquals(2u, hidden[1].body[0])
        // A token that isn't a path element is replaced.
        assertTrue((hidden[0].body[0] as String).startsWith("/org/freedesktop/portal/desktop/request/1_9/flatdroid"))
    }

    @Test
    fun filesAndFoldersByDescriptor() {
        val fd = FileDescriptor()
        val file = call(Portal.OPEN_URI, "OpenFile", "sha{sv}", "", UnixFd(0), mapOf("writable" to Variant("b", true))).copy(unixFds = 1)
        assertEquals(0u, all(file, fds = listOf(fd))[1].body[0])
        val folder = call(Portal.OPEN_URI, "OpenDirectory", "sha{sv}", "", UnixFd(0), emptyMap<String, Variant>()).copy(unixFds = 1)
        assertEquals(0u, all(folder, fds = listOf(fd))[1].body[0])
        assertEquals(listOf("file writable=true", "folder"), opened)
        // A descriptor that didn't come with the call.
        assertEquals("org.freedesktop.DBus.Error.InvalidArgs", all(file)[0].errorName)
    }

    @Test
    fun schemesAndRequests() {
        assertEquals(listOf(true), ask(call(Portal.OPEN_URI, "SchemeSupported", "sa{sv}", "magnet", emptyMap<String, Variant>())).body)
        assertEquals(listOf(false), ask(call(Portal.OPEN_URI, "SchemeSupported", "sa{sv}", "intent", emptyMap<String, Variant>())).body)
        assertEquals(DBusWire.METHOD_RETURN, ask(call(Portal.REQUEST, "Close", path = "/org/freedesktop/portal/desktop/request/1_9/t1")).type)
        assertEquals(listOf(Variant("u", 5u)), ask(call(Portal.PROPERTIES, "Get", "ss", Portal.OPEN_URI, "version")).body)
    }

    @Test
    fun theCameraPortalsLinksComeThroughTheHandler() {
        val choose = call(Portal.IMPL_APP_CHOOSER, "ChooseApplication", "ossasa{sv}", "/r", "", "", listOf("other", Portal.LINK_HANDLER), emptyMap<String, Variant>())
        assertEquals(listOf(0u, mapOf("choice" to Variant("s", "flatdroid-links"))), ask(choose, backend).body)
        val without = call(Portal.IMPL_APP_CHOOSER, "ChooseApplication", "ossasa{sv}", "/r", "", "", listOf("other"), emptyMap<String, Variant>())
        assertEquals(1u, ask(without, backend).body[0])
        assertEquals(listOf(true), ask(call(Portal.LINKS, "Open", "s", "https://flathub.org"), backend).body)
        assertEquals(listOf(false), ask(call(Portal.LINKS, "Open", "s", "content://x/y"), backend).body)
        // The app-facing interfaces aren't the backend's, and the handler's isn't the app's.
        assertEquals("org.freedesktop.DBus.Error.UnknownInterface", ask(call(Portal.OPEN_URI, "OpenURI", "ssa{sv}", "", "https://a", emptyMap<String, Variant>()), backend).errorName)
        assertEquals("org.freedesktop.DBus.Error.UnknownInterface", ask(call(Portal.LINKS, "Open", "s", "https://a")).errorName)
        assertEquals(listOf("https://flathub.org"), opened)
    }
}
