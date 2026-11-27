package com.garathon.flatdroid.session.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor

class HostCommandBusTest {
    private val started = mutableListOf<HostCommandBus.Command>()
    private val exits = mutableListOf<(Int) -> Unit>()
    private val signalled = mutableListOf<Triple<Int, Int, Boolean>>()
    private var exitAtOnce: Int? = null
    private var nextPid = 100
    private val commands = object : HostCommandBus.Commands {
        override fun start(command: HostCommandBus.Command, exited: (Int) -> Unit): Int {
            started += command
            exits += exited
            exitAtOnce?.let(exited)
            return nextPid++
        }

        override fun signal(pid: Int, signal: Int, group: Boolean) = signalled.add(Triple(pid, signal, group))
    }
    private val pty = FileDescriptor()
    private val otherPty = FileDescriptor()
    private val pipe = FileDescriptor()
    private val paths = mapOf(pty to "/dev/pts/3", otherPty to "/dev/pts/4", pipe to "pipe:[77]")
    private val bus = HostCommandBus(commands) { paths[it] }
    private val sent = mutableListOf<Message>().also { list -> bus.send = { list += it } }
    private val portal = Portal(Portal.Role.PORTAL, { emptyMap() }, { Portal.NetworkStatus(true, false, 4u) }, NoLinks, hostCommands = bus)

    private object NoLinks : Portal.Links {
        override fun openUri(uri: String) = false
        override fun openFile(fd: FileDescriptor, writable: Boolean) = false
        override fun openDirectory(fd: FileDescriptor) = false
        override fun showFolder(path: String) = false
    }

    private fun bytes(text: String) = (text + "\u0000").toByteArray().map { it.toUByte() }

    private fun hostCommand(
        argv: List<String>, fds: Map<Int, Int>, cwd: String = "/home/user", env: Map<String, String> = emptyMap(), flags: UInt = 0u, sender: String = ":1.9",
    ) = Message.call(
        HostCommandBus.NAME, HostCommandBus.PATH, HostCommandBus.IFACE, "HostCommand", "ayaaya{uh}a{ss}u",
        bytes(cwd), argv.map(::bytes), fds.entries.associate { (k, v) -> k.toUInt() to UnixFd(v) }, env, flags,
    ).copy(serial = 3u, sender = sender)

    private fun signal(pid: Int, sender: String = ":1.9") =
        Message.call(HostCommandBus.NAME, HostCommandBus.PATH, HostCommandBus.IFACE, "HostCommandSignal", "uub", pid.toUInt(), 15u, true)
            .copy(serial = 4u, sender = sender)

    @Test
    fun aTerminalOnAllThreeBecomesTheCommandsTerminal() {
        val replies = portal.handle(hostCommand(listOf("/bin/sh", "-l"), mapOf(0 to 0, 1 to 0, 2 to 0), env = mapOf("TERM" to "xterm")), listOf(pty))
        assertEquals(listOf(100u), replies.single().body)
        val command = started.single()
        assertEquals("/dev/pts/3", command.tty)
        assertTrue(command.stdio.isEmpty())
        assertEquals(listOf("/bin/sh", "-l"), command.argv)
        assertEquals("/home/user", command.cwd)
        assertEquals(mapOf("TERM" to "xterm"), command.env)
    }

    @Test
    fun pipesAreTheCommandsOwn() {
        portal.handle(hostCommand(listOf("timeout", "3", "true"), mapOf(1 to 0, 2 to 1), flags = HostCommandBus.CLEAR_ENV), listOf(pipe, pipe))
        val command = started.single()
        assertNull(command.tty)
        assertEquals(setOf(1, 2), command.stdio.keys)
        assertTrue(command.clearEnv)
        // A terminal on only some of them, or two terminals, isn't taken as the controlling one.
        portal.handle(hostCommand(listOf("sh"), mapOf(0 to 0, 1 to 1, 2 to 1)), listOf(pty, otherPty))
        assertNull(started.last().tty)
    }

    @Test
    fun refusals() {
        fun error(call: Message, fds: List<FileDescriptor>) = portal.handle(call, fds).single().errorName
        assertEquals("org.freedesktop.DBus.Error.NotSupported", error(hostCommand(listOf("sh"), mapOf(0 to 0, 3 to 0)), listOf(pty)))
        assertEquals("org.freedesktop.DBus.Error.InvalidArgs", error(hostCommand(emptyList(), emptyMap()), emptyList()))
        assertEquals("org.freedesktop.DBus.Error.InvalidArgs", error(hostCommand(listOf("A=b"), emptyMap()), emptyList()))
        assertEquals("org.freedesktop.DBus.Error.InvalidArgs", error(hostCommand(listOf("sh"), emptyMap(), env = mapOf("1X" to "y")), emptyList()))
        assertEquals("org.freedesktop.DBus.Error.InvalidArgs", error(hostCommand(listOf("sh"), mapOf(0 to 5)), listOf(pty)))
        assertTrue(started.isEmpty())
        repeat(HostCommandBus.MAX_RUNNING) { portal.handle(hostCommand(listOf("sh"), emptyMap()), emptyList()) }
        assertEquals("org.freedesktop.DBus.Error.LimitsExceeded", error(hostCommand(listOf("sh"), emptyMap()), emptyList()))
    }

    @Test
    fun exitsGoToTheCallerAfterTheReply() {
        exitAtOnce = 256
        val replies = portal.handle(hostCommand(listOf("false"), emptyMap()), emptyList())
        assertEquals(DBusWire.METHOD_RETURN, replies[0].type)
        assertEquals("HostCommandExited", replies[1].member)
        assertEquals(listOf(100u, 256u), replies[1].body)
        assertEquals(":1.9", replies[1].destination)
        exitAtOnce = null
        portal.handle(hostCommand(listOf("sleep", "9"), emptyMap()), emptyList())
        exits.last()(9)
        assertEquals(listOf(101u, 9u), sent.single().body)
        assertTrue(bus.runningPids().isEmpty())
    }

    @Test
    fun onlyTheCallerSignalsItsCommands() {
        portal.handle(hostCommand(listOf("sh"), emptyMap()), emptyList())
        assertEquals("org.freedesktop.DBus.Error.InvalidArgs", portal.handle(signal(100, sender = ":1.10"), emptyList()).single().errorName)
        assertEquals("org.freedesktop.DBus.Error.InvalidArgs", portal.handle(signal(555), emptyList()).single().errorName)
        assertEquals(DBusWire.METHOD_RETURN, portal.handle(signal(100), emptyList()).single().type)
        assertEquals(listOf(Triple(100, 15, true)), signalled)
    }

    @Test
    fun watchedCommandsEndWithTheirCaller() {
        portal.handle(hostCommand(listOf("sh"), emptyMap(), flags = HostCommandBus.WATCH_BUS), emptyList())
        portal.handle(hostCommand(listOf("sh"), emptyMap()), emptyList())
        portal.handle(Message.signal("/org/freedesktop/DBus", "org.freedesktop.DBus", "NameOwnerChanged", "sss", ":1.9", ":1.9", "").copy(sender = "org.freedesktop.DBus"))
        assertEquals(listOf(Triple(100, 9, true)), signalled)
    }

    @Test
    fun servedUnderFlatpaksNameWithItsVersion() {
        assertTrue(HostCommandBus.NAME in portal.names)
        val version = Message.call(HostCommandBus.NAME, HostCommandBus.PATH, Portal.PROPERTIES, "Get", "ss", HostCommandBus.IFACE, "version")
            .copy(serial = 5u, sender = ":1.9")
        assertEquals(listOf(Variant("u", 1u)), portal.handle(version).single().body)
        val plain = Portal(Portal.Role.PORTAL, { emptyMap() }, { Portal.NetworkStatus(true, false, 4u) }, NoLinks)
        assertTrue(HostCommandBus.NAME !in plain.names)
    }
}
