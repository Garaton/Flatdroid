package com.garathon.flatdroid.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.garathon.flatdroid.setup.SetupController

/**
 * What a download does now: the mobile data setting on a metered connection, and always otherwise.
 * [setting] is the setting when the caller has it loaded already; else it is read from disk.
 */
fun mobileDataPolicy(context: Context, setting: MobileData? = null): MobileData =
    if (meteredNow(context)) setting ?: savedMobileData(context) else MobileData.ALWAYS

/** The saved mobile data setting. */
fun savedMobileData(context: Context): MobileData = SetupController.saved(context)?.mobileData ?: MobileData.ASK

/** Whether Android's default network costs money now (no network: false). */
fun meteredNow(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = cm.activeNetwork ?: return false
    return cm.getNetworkCapabilities(network)?.let(::isMetered) ?: cm.isActiveNetworkMetered
}

/** As Android tells apps to judge it: metered unless unmetered, for good or for now (a carrier's offer, Android 11 and later). */
fun isMetered(capabilities: NetworkCapabilities): Boolean =
    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
        !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))

/** Why a running download waits: the user is asked, or it waits for an unmetered network. */
enum class MeteredWait { ASK, WIFI }

/**
 * Whether a download waits on the network as it is now: on a metered one as [setting] says, unless
 * the user [allowed] mobile data for it, or chose to [waitForWifi] instead of being asked
 * ([MobileDataConsent]); never on an unmetered one.
 */
fun meteredWait(metered: Boolean, setting: MobileData, allowed: Boolean, waitForWifi: Boolean = false): MeteredWait? = when {
    !metered || setting == MobileData.ALWAYS -> null
    setting == MobileData.NEVER -> MeteredWait.WIFI
    allowed -> null
    waitForWifi -> MeteredWait.WIFI
    else -> MeteredWait.ASK
}

/**
 * The user said yes to downloads on mobile data (the question before a download, or the one a
 * paused download asks): it holds for the metered network it was given on, until a running
 * download sees an unmetered one. Android keeps the mobile network up beside Wi-Fi, so it is the
 * same network again after Wi-Fi drops; the yes is dropped on Wi-Fi so that the next drop asks.
 * "Wait for Wi-Fi" instead (either question) is kept the same way: downloads then wait on that
 * network without asking, until Wi-Fi. Kept for Flatdroid's process only.
 */
object MobileDataConsent {
    @Volatile private var network: Network? = null
    @Volatile private var waitNetwork: Network? = null

    /** Yes for the network that is the default now. */
    fun allowNow(context: Context) = allow(activeNetwork(context))

    fun allow(on: Network?) {
        network = on
        waitNetwork = null
    }

    fun allowed(on: Network?): Boolean = on != null && on == network

    /** Wait for Wi-Fi on the network that is the default now, without asking again there. */
    fun waitNow(context: Context) = waitOn(activeNetwork(context))

    fun waitOn(on: Network?) {
        waitNetwork = on
        network = null
    }

    fun waits(on: Network?): Boolean = on != null && on == waitNetwork

    fun clear() {
        network = null
        waitNetwork = null
    }

    private fun activeNetwork(context: Context) = context.getSystemService(ConnectivityManager::class.java)?.activeNetwork
}
