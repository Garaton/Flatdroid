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
        override fun showFolder(path: String) = shows.also { opened += "show $path" }
    }
    private val states = mutableListOf<Inhibitions.State>()
    private val inhibitions = Inhibitions { states += it }
    private val hostPath: (String) -> String? = { guest -> guest.takeIf { it.startsWith("/home/user") }?.replaceFirst("/home/user", "/host/home") }
    private val portal = Portal(Portal.Role.PORTAL, { settings }, { status }, links, inhibitions, hostPath)
    private val backend = Portal(Portal.Role.BACKEND, { settings }, { status }, links, inhibitions, hostPath)

    private fun call(iface: String, member: String, signature: String = "", vararg body: Any?, path: String = Portal.PATH) =
        Message.call(Portal.PORTAL_NAME, path, iface, member, signature, *body).copy(serial = 3u, sender = ":1.9")

    /** Everything [to] sends for [message], after a trip over the wire as the bus delivers it. */
    private fun all(message: Message, to: Portal = portal, fds: List<FileDescriptor> = emptyList()): List<Message> =
        to.handle(DBusWire.decode(DBusWire.encode(message, message.serial)), fds).map { DBusWire.decode(DBusWire.encode(it, 4u)) }

    /** [message]'s reply from [to]. */
    private fun ask(message: Message, to: Portal = portal): Message = all(message, to).first()

    @Test
    fun ownsThePortalOrItsBackendName() {
        assertEquals(listOf("org.freedesktop.portal.Desktop", "org.freedesktop.ScreenSaver", "org.freedesktop.FileManager1"), portal.names)
        assertEquals(listOf("org.freedesktop.impl.portal.desktop.flatdroid", "org.freedesktop.ScreenSaver", "org.freedesktop.FileManager1"), backend.names)
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

    private fun screenSaver(member: String, signature: String = "", vararg body: Any?, sender: String = ":1.9", path: String = "/org/freedesktop/ScreenSaver") =
        Message.call(Portal.SCREENSAVER, path, Portal.SCREENSAVER, member, signature, *body).copy(serial = 5u, sender = sender)

    private fun left(name: String, sender: String = "org.freedesktop.DBus") =
        Message.signal("/org/freedesktop/DBus", "org.freedesktop.DBus", "NameOwnerChanged", "sss", name, name, "").copy(serial = 6u, sender = sender)

    @Test
    fun screenSaverInhibitsUntilReleased() {
        val cookie = ask(screenSaver("Inhibit", "ss", "Videos", "Playing")).body[0] as UInt
        assertEquals(listOf(Inhibitions.State(idle = true)), states)
        // Another connection can't release it.
        ask(screenSaver("UnInhibit", "u", cookie, sender = ":1.10"))
        assertEquals(1, states.size)
        ask(screenSaver("UnInhibit", "u", cookie, path = "/ScreenSaver"))
        assertEquals(Inhibitions.State(), states.last())
        assertEquals(listOf(false), ask(screenSaver("GetActive")).body)
        assertEquals(DBusWire.METHOD_RETURN, ask(screenSaver("SimulateUserActivity")).type)
    }

    @Test
    fun leavingTheBusReleases() {
        ask(screenSaver("Inhibit", "ss", "a", "b"))
        // Only the bus itself says who left.
        portal.handle(left(":1.9", sender = ":1.10"))
        assertEquals(1, states.size)
        assertEquals(emptyList<Message>(), portal.handle(left(":1.9")))
        assertEquals(Inhibitions.State(), states.last())
    }

    @Test
    fun portalInhibitIsHeldUntilItsRequestCloses() {
        val handle = ask(call(Portal.INHIBIT, "Inhibit", "sua{sv}", "", 12u, mapOf("handle_token" to Variant("s", "i1")))).body[0]
        assertEquals("/org/freedesktop/portal/desktop/request/1_9/i1", handle)
        assertEquals(listOf(Inhibitions.State(idle = true, suspend = true)), states)
        ask(call(Portal.REQUEST, "Close", path = handle as String))
        assertEquals(Inhibitions.State(), states.last())
        assertEquals(listOf(Variant("u", 3u)), ask(call(Portal.PROPERTIES, "Get", "ss", Portal.INHIBIT, "version")).body)
    }

    @Test
    fun theCameraPortalIsAnsweredOnlyToLearnThatTheAppAsks() {
        // Unwatched: no camera interface.
        assertEquals("org.freedesktop.DBus.Error.UnknownInterface", ask(call(Portal.CAMERA, "AccessCamera", "a{sv}", emptyMap<String, Variant>())).errorName)
        var asked = 0
        val watching = Portal(Portal.Role.PORTAL, { settings }, { status }, links, inhibitions, hostPath, cameraAsked = { asked++ })
        assertEquals(listOf(Variant("b", true)), ask(call(Portal.PROPERTIES, "Get", "ss", Portal.CAMERA, "IsCameraPresent"), watching).body)
        assertEquals(0, asked)
        val sent = all(call(Portal.CAMERA, "AccessCamera", "a{sv}", mapOf("handle_token" to Variant("s", "c1"))), watching)
        assertEquals(listOf<Any?>("/org/freedesktop/portal/desktop/request/1_9/c1"), sent[0].body)
        // Refused: the camera comes from the next launch.
        assertEquals("Response", sent[1].member)
        assertEquals(Portal.RESPONSE_OTHER, sent[1].body[0])
        assertEquals(1, asked)
        assertEquals("org.freedesktop.portal.Error.NotAllowed", ask(call(Portal.CAMERA, "OpenPipeWireRemote", "a{sv}", emptyMap<String, Variant>()), watching).errorName)
        assertTrue("org.freedesktop.portal.Camera" in ask(call(Portal.INTROSPECTABLE, "Introspect"), watching).body[0] as String)
        // The backend never answers it.
        val backendWatching = Portal(Portal.Role.BACKEND, { settings }, { status }, links, inhibitions, hostPath, cameraAsked = { asked++ })
        assertEquals("org.freedesktop.DBus.Error.UnknownInterface", ask(call(Portal.CAMERA, "AccessCamera", "a{sv}", emptyMap<String, Variant>()), backendWatching).errorName)
    }

    @Test
    fun theBackendInhibitsForTheRealPortal() {
        val handle = "/org/freedesktop/portal/desktop/request/1_3/x"
        assertEquals(DBusWire.METHOD_RETURN, ask(call(Portal.IMPL_INHIBIT, "Inhibit", "ossua{sv}", handle, "app", "", 8u, emptyMap<String, Variant>()), backend).type)
        assertEquals(listOf(Inhibitions.State(idle = true)), states)
        ask(call(Portal.IMPL_REQUEST, "Close", path = handle), backend)
        assertEquals(Inhibitions.State(), states.last())
        val elsewhere = call(Portal.IMPL_INHIBIT, "Inhibit", "ossua{sv}", "/x", "app", "", 8u, emptyMap<String, Variant>())
        assertEquals("org.freedesktop.DBus.Error.InvalidArgs", ask(elsewhere, backend).errorName)
        assertEquals("org.freedesktop.DBus.Error.UnknownInterface", ask(call(Portal.IMPL_INHIBIT, "Inhibit")).errorName)
    }

    private fun fileManager(member: String, vararg uris: String) =
        Message.call(Portal.FILE_MANAGER, Portal.FILE_MANAGER_PATH, Portal.FILE_MANAGER, member, "ass", uris.toList(), "").copy(serial = 7u, sender = ":1.9")

    @Test
    fun showsFoldersInTheHome() {
        ask(fileManager("ShowFolders", "file:///home/user/Music"))
        ask(fileManager("ShowItems", "file:///usr/share/a", "file:///home/user/Pictures/a%20b.png"))
        ask(fileManager("ShowItems", "https://example.org/a", "file://otherhost/home/user/x"))
        assertEquals(listOf("show /host/home/Music", "show /host/home/Pictures"), opened)
        assertEquals("org.freedesktop.DBus.Error.InvalidArgs", ask(fileManager("ShowFolders").copy(signature = "as", body = listOf(emptyList<String>()))).errorName)
    }

    @Test
    fun fileAddresses() {
        assertEquals("/home/user/a b", Portal.guestPath("file:///home/user/a%20b"))
        assertEquals("/x", Portal.guestPath("file://localhost/x"))
        assertNull(Portal.guestPath("file://host/x"))
        assertNull(Portal.guestPath("file:relative"))
        assertNull(Portal.guestPath("not a uri"))
    }
}
