package com.garathon.flatdroid.session.bus

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable
import java.io.File

/**
 * Flatdroid on one session's bus ([Portal] through a [BusClient]): the settings, and the network
 * as the app may use it ([appNetwork]), kept up to date with Android's. In [Portal.Role.BACKEND]
 * the real portal answers the app's network calls, so Android's network isn't watched here.
 */
class SessionPortal private constructor(
    private val context: Context,
    private val role: Portal.Role,
    private val appNetwork: Boolean,
    darkTheme: Boolean,
) : Closeable {
    @Volatile private var colorScheme = if (darkTheme) Portal.PREFER_DARK else Portal.PREFER_LIGHT
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var lastStatus: Portal.NetworkStatus? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val portal = Portal(role, ::settings, ::networkStatus)
    private var client: BusClient? = null

    private fun settings(): Map<String, Map<String, Variant>> =
        mapOf(Portal.APPEARANCE to mapOf(Portal.COLOR_SCHEME to Variant("u", colorScheme)))

    private fun networkStatus(): Portal.NetworkStatus {
        if (!appNetwork || cm == null) return OFFLINE
        val capabilities = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return OFFLINE
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return OFFLINE
        val connectivity = when {
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> FULL
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) -> CAPTIVE_PORTAL
            else -> LIMITED
        }
        return Portal.NetworkStatus(available = true, metered = cm.isActiveNetworkMetered, connectivity = connectivity)
    }

    private fun watchNetwork() {
        if (role != Portal.Role.PORTAL || !appNetwork || cm == null) return
        lastStatus = networkStatus()
        // Android calls back on its own connectivity thread; a change is only announced, GLib reads it then.
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = changed()
            override fun onLost(network: Network) = changed()
        }
        callback = runCatching { cm.registerDefaultNetworkCallback(cb); cb }.getOrNull()
    }

    private fun changed() {
        val status = networkStatus()
        if (status == lastStatus) return
        lastStatus = status
        portal.networkChanged()?.let { client?.send(it) }
    }

    override fun close() {
        callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        callback = null
        client?.close()
        client = null
    }

    companion object {
        private val OFFLINE = Portal.NetworkStatus(available = false, metered = false, connectivity = 1u)
        private const val LIMITED = 2u
        private const val CAPTIVE_PORTAL = 3u
        private const val FULL = 4u

        /**
         * Joins the bus whose daemon listens on [socket] in [role]; null, logged through [log],
         * when it can't (the app then runs without these services).
         */
        fun open(
            context: Context, socket: File, role: Portal.Role, appNetwork: Boolean, darkTheme: Boolean, log: (String) -> Unit,
        ): SessionPortal? {
            val session = SessionPortal(context, role, appNetwork, darkTheme)
            session.client = BusClient.open(socket, session.portal.names, session.portal::handle, log) ?: return null
            session.watchNetwork()
            log("[flatdroid] Flatdroid joined the session bus as ${session.portal.names.joinToString()}")
            return session
        }
    }
}
