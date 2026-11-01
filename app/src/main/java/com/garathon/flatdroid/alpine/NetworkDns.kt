package com.garathon.flatdroid.alpine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

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

    /**
     * The addresses of the provider Android's Private DNS names (its strict mode, API 28+) that
     * answer plain DNS, or empty. Flatpaks' resolvers can't speak DNS over TLS, so resolv.conf
     * lists only these and their lookups go to the chosen provider as plain DNS: unencrypted, but
     * to that resolver and its filtering rather than the network's. A provider that answers only
     * encrypted DNS is left out (each lookup would otherwise wait for it to time out). Looked up
     * through the active network and kept for [CACHE_MS] per network and provider, failures too;
     * empty on the main thread, where Android refuses network access.
     */
    fun privateDnsServers(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        // Android refuses network access there; not cached, so a later call off it still looks.
        if (Looper.myLooper() == Looper.getMainLooper()) return emptyList()
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return try {
            val network = cm.activeNetwork ?: return emptyList()
            val name = cm.getLinkProperties(network)?.privateDnsServerName ?: return emptyList()
            val key = "$network/$name"
            cached?.let { (k, at, servers) -> if (k == key && System.currentTimeMillis() - at < CACHE_MS) return servers }
            // A failed lookup is kept too, so every proot start doesn't wait for it again.
            val answering = runCatching { answering(network, name, network.getAllByName(name).toList()) }.getOrDefault(emptyList())
            cached = Triple(key, System.currentTimeMillis(), answering)
            answering
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Volatile private var cached: Triple<String, Long, List<String>>? = null

    /** Of [addresses], those answering a plain DNS query for [name] within [PROBE_MS], in their order. */
    private fun answering(network: Network, name: String, addresses: List<InetAddress>): List<String> {
        if (addresses.isEmpty()) return emptyList()
        val id = Random.nextInt(0x10000)
        val query = DnsProbe.query(id, name)
        val answered = mutableSetOf<InetAddress>()
        DatagramSocket().use { socket ->
            network.bindSocket(socket)
            for (address in addresses) {
                // An address the network has no route for (IPv6 on an IPv4-only network) just doesn't answer.
                runCatching { socket.send(DatagramPacket(query, query.size, InetSocketAddress(address, 53))) }
            }
            val reply = ByteArray(512)
            val deadline = System.currentTimeMillis() + PROBE_MS
            while (answered.size < addresses.size) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                socket.soTimeout = left.toInt()
                val packet = DatagramPacket(reply, reply.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                if (packet.address in addresses && DnsProbe.answers(reply, packet.length, id)) answered += packet.address
            }
        }
        return addresses.filter { it in answered }.mapNotNull { it.hostAddress }
    }

    private const val PROBE_MS = 1000L
    private const val CACHE_MS = 5 * 60 * 1000L
}
