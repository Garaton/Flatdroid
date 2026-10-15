package com.garathon.flatdroid.alpine

import android.content.Context
import android.net.ConnectivityManager

/** DNS servers of the active network, as Android reports them. Empty when unknown. */
object NetworkDns {
    fun servers(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return try {
            val network = cm.activeNetwork ?: return emptyList()
            cm.getLinkProperties(network)?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}
