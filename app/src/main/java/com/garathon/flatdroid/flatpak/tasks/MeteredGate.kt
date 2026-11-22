package com.garathon.flatdroid.flatpak.tasks

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.garathon.flatdroid.proot.ProotProcess
import com.garathon.flatdroid.proot.processes.ProotFreeze
import com.garathon.flatdroid.settings.MeteredWait
import com.garathon.flatdroid.settings.MobileDataConsent
import com.garathon.flatdroid.settings.isMetered
import com.garathon.flatdroid.settings.meteredNow
import com.garathon.flatdroid.settings.meteredWait
import com.garathon.flatdroid.settings.savedMobileData
import com.garathon.flatdroid.util.debug.DebugLog

/**
 * Holds a task that downloads to the mobile data setting while it runs, between [open] and
 * [close]: on a metered network the task's commands are paused ([ProotFreeze]) and the next one
 * waits ([await]) while [meteredWait] says so; on an unmetered network they go on by themselves.
 * The network is checked when the task opens it (a task that waited in the queue) and whenever
 * Android's default network changes. [onWait] hears each change (null: running; [closed]: let go
 * because the task ends), on any thread.
 */
internal class MeteredGate(private val context: Context, private val onWait: (wait: MeteredWait?, closed: Boolean) -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Object()
    private var callback: ConnectivityManager.NetworkCallback? = null
    /** The task's commands running now. */
    private val processes = mutableSetOf<ProotProcess>()
    private var wait: MeteredWait? = null
    private var closed = false
    /** The default network as last seen, and whether it is metered. */
    private var network: Network? = null
    private var metered = false
    /** When the pause began ([SystemClock.elapsedRealtime]), and how long the earlier ones lasted. */
    private var pausedAt = 0L
    private var pausedBefore = 0L

    /** Checks the network now, then follows it. */
    fun open() {
        synchronized(lock) {
            network = cm?.activeNetwork
            metered = meteredNow(context)
            settle()
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                seen(network, isMetered(capabilities))
        }
        callback = runCatching { cm?.registerDefaultNetworkCallback(cb); cb }.getOrNull()
    }

    /** The task ends or is cancelled: its commands go on (a stopped one ignores what ends it), and nothing pauses them again. */
    fun close() {
        callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        callback = null
        synchronized(lock) {
            closed = true
            settle()
        }
    }

    /** The user lets the task download on this metered network ([MobileDataConsent]). */
    fun allowMobileData() = synchronized(lock) {
        MobileDataConsent.allow(network)
        settle()
    }

    /** The mobile data setting changed: it applies now. */
    fun recheck() = synchronized(lock) { settle() }

    /** Blocks while the task waits for the network; an interrupt (a Cancel) ends the wait. */
    fun await() = synchronized(lock) {
        while (wait != null && !closed) lock.wait()
    }

    /** How long the task has been paused in all, the pause going on now included. */
    fun pausedMillis(): Long = synchronized(lock) {
        pausedBefore + if (wait != null) SystemClock.elapsedRealtime() - pausedAt else 0L
    }

    /** A command of the task started: paused at once if the task waits. */
    fun started(process: ProotProcess) = synchronized(lock) {
        processes += process
        if (wait != null) freeze(process)
    }

    fun ended(process: ProotProcess) = synchronized(lock) { processes -= process }

    private fun seen(on: Network, isMetered: Boolean) = synchronized(lock) {
        network = on
        metered = isMetered
        settle()
    }

    /** Pauses or lets go as the network, the setting and the user's yes say now. */
    private fun settle() {
        if (!metered) MobileDataConsent.clear()
        val next = if (closed) null else meteredWait(metered, savedMobileData(context), MobileDataConsent.allowed(network))
        if (next == wait) return
        val was = wait
        wait = next
        when {
            was == null -> {
                pausedAt = SystemClock.elapsedRealtime()
                processes.forEach(::freeze)
            }
            next == null -> {
                pausedBefore += SystemClock.elapsedRealtime() - pausedAt
                processes.forEach { p -> p.pid?.let(ProotFreeze::go) }
                lock.notifyAll()
            }
        }
        DebugLog.i("Download on a metered network: ${next ?: "goes on"}")
        onWait(next, closed)
    }

    private fun freeze(process: ProotProcess) {
        process.pid?.let(ProotFreeze::stop)
    }
}
