package com.garathon.flatdroid.alpine

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import com.garathon.flatdroid.util.DebugLog
import com.garathon.flatdroid.util.appScope
import com.garathon.flatdroid.util.writeTextAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Keeps [files] (resolv.conf files bound into running proots) in step with Android's network while
 * between [start] and [stop]: after a switch between Wi-Fi and mobile data the old network's DNS
 * server is often unreachable, and a change of Private DNS changes the list too. glibc and musl read
 * resolv.conf again when it changes, so running apps pick it up without a restart (verify for apps
 * with their own resolver). Android calls back on its own thread; the files are written on an IO
 * thread, only when their text changes, and not while there is no network (the old list stays).
 */
class ResolvConfRefresher(private val context: Context, private val files: List<File>) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (cm == null || callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                appScope.launch(Dispatchers.IO) { refresh() }
            }
        }
        callback = runCatching { cm.registerDefaultNetworkCallback(cb); cb }.getOrNull()
    }

    fun stop() {
        callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        callback = null
    }

    @Synchronized
    private fun refresh() {
        val servers = NetworkDns.servers(context)
        if (servers.isEmpty()) return
        val text = GuestFiles.resolvConf(servers, NetworkDns.privateDnsServers(context))
        for (file in files) {
            if (!file.isFile || file.readText() == text) continue
            file.writeTextAtomically(text, durable = false)
            DebugLog.i("resolv.conf updated for the new network: ${text.lines().filter { it.isNotBlank() }.joinToString(", ")}")
        }
    }
}
