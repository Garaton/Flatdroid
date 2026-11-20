package com.garathon.flatdroid.session.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalTest {
    private var status = Portal.NetworkStatus(available = true, metered = false, connectivity = 4u)
    private val settings = mapOf(Portal.APPEARANCE to mapOf(Portal.COLOR_SCHEME to Variant("u", Portal.PREFER_DARK)))
    private val portal = Portal(Portal.Role.PORTAL, { settings }, { status })
    private val backend = Portal(Portal.Role.BACKEND, { settings }, { status })

    private fun call(iface: String, member: String, signature: String = "", vararg body: Any?, path: String = Portal.PATH) =
        Message.call(Portal.PORTAL_NAME, path, iface, member, signature, *body).copy(serial = 3u, sender = ":1.9")

    /** [message] answered by [to] after a trip over the wire, as the bus delivers it. */
    private fun ask(message: Message, to: Portal = portal): Message {
        val reply = to.handle(DBusWire.decode(DBusWire.encode(message, message.serial)))!!
        return DBusWire.decode(DBusWire.encode(reply, 4u))
    }

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
        assertNull(portal.handle(quiet))
        assertNull(portal.handle(Message.signal(Portal.PATH, Portal.SETTINGS, "SettingChanged")))
    }
}
