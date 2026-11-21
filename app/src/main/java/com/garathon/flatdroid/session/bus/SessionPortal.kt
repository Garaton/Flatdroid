package com.garathon.flatdroid.session.bus

import android.app.UiModeManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import com.garathon.flatdroid.session.links.AndroidLinks
import com.garathon.flatdroid.session.links.OpenedFiles
import com.garathon.flatdroid.settings.LiveSettings
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.util.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File

/**
 * Flatdroid on one session's bus ([Portal] through a [BusClient]): the settings ([Appearance],
 * followed as Android's and Flatdroid's change), the network as the app may use it ([appNetwork]),
 * kept up to date with Android's, links ([AndroidLinks]), and inhibits: the screen kept on
 * through [keepScreenOn] (the viewer does it while visible), the processor through a partial wake
 * lock. In [Portal.Role.BACKEND] the real portal answers the app's network calls, so Android's
 * network isn't watched here.
 */
class SessionPortal private constructor(
    private val context: Context,
    private val role: Portal.Role,
    private val appNetwork: Boolean,
    @Volatile private var user: UserSettings?,
    hostPath: (String) -> String?,
    private val keepScreenOn: (Boolean) -> Unit,
    log: (String) -> Unit,
    /** The app asked for the camera portal, which this session doesn't run ([Portal]); null: not watched. */
    cameraAsked: (() -> Unit)? = null,
) : Closeable {
    @Volatile private var appearance = AndroidAppearance.of(context, user)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var configCallback: ComponentCallbacks? = null
    private var contrastListener: UiModeManager.ContrastChangeListener? = null
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var lastStatus: Portal.NetworkStatus? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Flatdroid:inhibit")?.apply { setReferenceCounted(false) }
    private val inhibitions = Inhibitions(::inhibited)
    private val portal = Portal(role, ::settings, ::networkStatus, AndroidLinks(context, log), inhibitions, hostPath, cameraAsked)
    private var client: BusClient? = null

    private fun settings(): Map<String, Map<String, Variant>> = appearance.settings()

    /**
     * Follows what the appearance comes from: Android's configuration (dark mode, and the accent
     * after a wallpaper change (verify)), its contrast (Android 14 and later), and Flatdroid's
     * settings (Theme for apps, Reduce motion).
     */
    private fun watchAppearance() {
        DebugLog.i("Bus: serving $appearance")
        val app = context.applicationContext
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = appearanceChanged(newConfig)
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        }
        app.registerComponentCallbacks(callbacks)
        configCallback = callbacks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val listener = UiModeManager.ContrastChangeListener { appearanceChanged() }
            runCatching { context.getSystemService(UiModeManager::class.java)?.addContrastChangeListener(app.mainExecutor, listener) }
                .onSuccess { contrastListener = listener }
        }
        scope.launch {
            LiveSettings.user.collect { saved ->
                if (saved != null) {
                    user = saved
                    appearanceChanged()
                }
            }
        }
    }

    /** Sends each setting that changed as `SettingChanged`. */
    @Synchronized
    private fun appearanceChanged(configuration: Configuration = context.resources.configuration) {
        val next = AndroidAppearance.of(context, user, configuration)
        val changes = appearance.changes(next)
        appearance = next
        for ((key, value) in changes) {
            DebugLog.i("Bus: SettingChanged $key $value")
            client?.send(portal.settingChanged(Portal.APPEARANCE, key, value))
        }
    }

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

    /** What the app holds off now: the screen's timeout, the processor's sleep (released at the session's end at the latest). */
    private fun inhibited(state: Inhibitions.State) {
        DebugLog.i("Bus: inhibits now $state")
        keepScreenOn(state.idle)
        wakeLock?.let { lock ->
            runCatching { if (state.suspend) lock.acquire() else if (lock.isHeld) lock.release() }
        }
    }

    override fun close() {
        callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        callback = null
        scope.cancel()
        configCallback?.let { context.applicationContext.unregisterComponentCallbacks(it) }
        configCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            contrastListener?.let { runCatching { context.getSystemService(UiModeManager::class.java)?.removeContrastChangeListener(it) } }
        }
        contrastListener = null
        client?.close()
        client = null
        inhibitions.clear()
        // The files the app opened stop being served with its session.
        OpenedFiles.clear()
    }

    companion object {
        private val OFFLINE = Portal.NetworkStatus(available = false, metered = false, connectivity = 1u)
        private const val LIMITED = 2u
        private const val CAPTIVE_PORTAL = 3u
        private const val FULL = 4u

        /**
         * Joins the bus whose daemon listens on [socket] in [role], with Flatdroid's settings as
         * loaded for the launch ([user]) and the app's paths mapped to the host's by [hostPath];
         * null, logged through [log], when it can't (the app then runs without these services).
         */
        fun open(
            context: Context, socket: File, role: Portal.Role, appNetwork: Boolean, user: UserSettings?,
            hostPath: (String) -> String?, keepScreenOn: (Boolean) -> Unit, log: (String) -> Unit,
            cameraAsked: (() -> Unit)? = null,
        ): SessionPortal? {
            val session = SessionPortal(context, role, appNetwork, user, hostPath, keepScreenOn, log, cameraAsked)
            session.client = BusClient.open(socket, session.portal.names, session.portal::handle, log) ?: return null
            session.watchNetwork()
            session.watchAppearance()
            log("[flatdroid] Flatdroid joined the session bus as ${session.portal.names.joinToString()}")
            return session
        }
    }
}
