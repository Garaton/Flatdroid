package com.garathon.flatdroid.viewer

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Build
import android.os.Looper
import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.session.HiddenPause
import com.garathon.flatdroid.util.debug.DebugLog
import com.garathon.flatdroid.viewer.frames.FrameDrawer
import com.garathon.flatdroid.viewer.frames.NativeFrames
import com.garathon.flatdroid.viewer.frames.TileFrames
import com.garathon.flatdroid.viewer.input.Keysyms
import com.garathon.flatdroid.viewer.input.ScrollSpeed
import com.garathon.flatdroid.viewer.protocol.Framebuffer
import com.garathon.flatdroid.viewer.protocol.RfbClient
import com.garathon.flatdroid.viewer.protocol.RfbListener
import com.garathon.flatdroid.viewer.protocol.WaylandClient
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Where the connection's news goes: the viewer on screen. Called on the main thread. */
interface ViewerSink {
    /** An update of [client]'s is complete: show it through [frames] (hold [RfbClient.frameLock] meanwhile). */
    fun frame(client: RfbClient, frames: FrameDrawer)
    /** The app copied [text]: for Android's clipboard. */
    fun clipboard(text: String)
    /** Android's clipboard text for an app pasting, or null (none, or Android won't give it now). */
    fun clipboardText(): String?
    /** Zooms in by [zoom] (1 = fitted), around the screen's centre. */
    fun zoomTo(zoom: Float) {}
    /** The connection ended; [error] says why when it wasn't closed on purpose. */
    fun closed(error: String?)
}

/**
 * The one connection to the session's display, kept for the whole session: it outlives the
 * viewer screen (rotation, leaving and coming back) so no other process can take the server's
 * one connection meanwhile ([SessionScript] starts it never shared, keeping the first client).
 * While no viewer is shown it asks for no updates. It also keeps the count of the app's windows
 * ([WaylandClient]) that [HiddenPause] acts on, with the viewer closed too.
 *
 * Updates are paced by the screen: the next is asked for only after the last was copied out, so
 * they never queue up behind a slow draw. While zoomed in it asks only for the part on screen
 * ([visibleRegion]).
 */
object ViewerConnection {
    private val main = Handler(Looper.getMainLooper())
    private var sender: ExecutorService? = null

    private var path: String? = null
    private var socket: LocalSocket? = null
    @Volatile private var client: RfbClient? = null
    @Volatile private var frames: FrameDrawer? = null

    // Main thread only.
    private var sink: ViewerSink? = null
    private var shown = false
    /** An update request is out and not yet answered. */
    private var awaiting = false
    /** A frame was posted to the main thread and not yet copied out (unpaced: drop extra posts). */
    @Volatile private var framePosted = false
    /** The part of the screen to ask for (x, y, width, height); null for all of it. */
    @Volatile private var region: IntArray? = null

    /**
     * Connects to [socketPath] unless already connected there. In a Wayland session (a
     * [waylandSocket]) a [WaylandClient] there also carries Android's text, if [shareClipboard],
     * and keeps the output at [outputRefresh] (per second; null to leave it). [appId]'s touch
     * scroll speed as last measured ([ScrollSpeed]) is read from [cacheDir]. Main thread.
     */
    fun open(
        socketPath: String, cacheDir: File, waylandSocket: String? = null,
        shareClipboard: Boolean = false, outputRefresh: Int? = null, appId: String? = null,
    ) {
        if (path == socketPath && client != null) return
        close()
        path = socketPath
        wayland = waylandSocket != null
        speedsFile = File(cacheDir, SCROLL_SPEEDS)
        speedKey = appId?.let { "$it.${if (wayland) "wayland" else "x11"}" }
        val saved = speedKey?.let { speeds() }
        speed = ScrollSpeed(speedKey?.let { saved?.getProperty(it) })
        speedAcross = ScrollSpeed(speedKey?.let { saved?.getProperty("$it$ACROSS") })
        waylandClient = waylandSocket?.let { socket ->
            // The windows are counted for an app that HiddenPause was attached to (not "Continue while hidden").
            WaylandClient(
                socket, ::androidText.takeIf { shareClipboard }, outputRefresh?.let { it * 1000 }, cacheDir,
                onWindows = HiddenPause::windows.takeIf { HiddenPause.attached },
            ).apply { start() }
        }
        sender = Executors.newSingleThreadExecutor()
        val socket = LocalSocket().also { socket = it }
        Thread({ read(socket, socketPath, cacheDir) }, "viewer-reader").start()
    }

    private fun read(socket: LocalSocket, socketPath: String, cacheDir: File) {
        var error: String? = null
        var native: NativeFrames? = null
        try {
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            val tileSize = ViewerDebug.tileSize(cacheDir)
            native = if (tileSize == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) NativeFrames.open(socket) else null
            frames = native ?: TileFrames(tileSize ?: TileFrames.SIZE)
            DebugLog.i("Viewer: drawing through ${frames?.javaClass?.simpleName}")
            val client = RfbClient(
                socket.inputStream, socket.outputStream, rawMeter = ViewerDebug.rawMeter(),
                copyRectMeter = ViewerDebug.copyRectMeter(), pixelSink = native,
            )
            client.connect()
            DebugLog.i("Viewer: connected to ${client.name} (${client.framebuffer.width}x${client.framebuffer.height})")
            this.client = client
            if (clipboardOffered) client.announceClipboard()
            ViewerDebug.connected(client, cacheDir) { zoom -> main.postDelayed({ sink?.zoomTo(zoom) }, 1_000) }
            // TigerVNC draws the pointer into the screen for a client whose last pointer position
            // isn't the server's: start where X puts it (the centre) and follow its own moves.
            pointerX = client.framebuffer.width / 2
            pointerY = client.framebuffer.height / 2
            client.pointer(0, pointerX, pointerY)
            main.post { if (this.client === client) awaiting = true } // connect() asked for the first, full update
            val listener = object : RfbListener {
                override fun updateDone() {
                    ViewerDebug.updateDone()
                    if (!framePosted) {
                        framePosted = true
                        main.post { frameArrived(client) }
                    }
                }

                override fun pointerMoved(x: Int, y: Int) {
                    pointerX = x
                    pointerY = y
                    send { client.pointer(buttons, x, y) }
                }

                override fun resized(width: Int, height: Int) {
                    DebugLog.i("Viewer: screen ${width}x$height")
                    SessionController.onDisplayResized?.invoke(width, height)
                    waylandClient?.outputResized(width, height)
                    // X keeps the pointer inside a smaller screen: follow it there, as above.
                    pointerX = pointerX.coerceAtMost(width - 1)
                    pointerY = pointerY.coerceAtMost(height - 1)
                    val x = pointerX
                    val y = pointerY
                    send { client.pointer(buttons, x, y) }
                }

                override fun clipboard(text: String) {
                    main.post { if (this@ViewerConnection.client === client) sink?.clipboard(text) }
                }

                override fun clipboardRequested() {
                    // Android's clipboard is read on the main thread, and only now that an app pastes.
                    main.post {
                        if (this@ViewerConnection.client !== client) return@post
                        val text = sink?.clipboardText().orEmpty()
                        send { client.provideClipboard(text) }
                    }
                }
            }
            while (true) client.readMessage(listener)
        } catch (_: EOFException) {
            // The server closed the connection: the session is ending.
        } catch (e: IOException) {
            if (this.socket === socket) error = e.message ?: e.javaClass.simpleName
        } catch (e: RuntimeException) {
            // The server's frames couldn't be taken (a size the GPU refuses, or none at all): the
            // viewer closes, the session stays.
            if (this.socket === socket) error = e.message ?: e.javaClass.simpleName
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) native?.close()
        main.post {
            if (this.socket === socket) {
                DebugLog.i("Viewer: disconnected${error?.let { ": $it" }.orEmpty()}")
                val current = sink
                close()
                current?.closed(error)
            }
        }
    }

    private fun frameArrived(client: RfbClient) {
        framePosted = false
        if (this.client !== client) return
        awaiting = false
        frames?.let { sink?.frame(client, it) }
        requestIfWanted()
    }

    /** Asks for the next update if none is out and someone is looking. Main thread. */
    private fun requestIfWanted() {
        val client = client ?: return
        if (awaiting || !shown) return
        awaiting = true
        send { requestNow(client) }
    }

    private fun requestNow(client: RfbClient) {
        val r = region
        if (r == null) client.requestUpdate() else client.requestUpdate(x = r[0], y = r[1], width = r[2], height = r[3])
    }

    /** The viewer that gets frames (shown or not: see [shown]). Main thread. */
    fun attach(sink: ViewerSink) {
        this.sink = sink
    }

    /** [sink] is gone; a newer one may already be attached (rotation, reopening). Main thread. */
    fun detach(sink: ViewerSink) {
        if (this.sink !== sink) return
        this.sink = null
        shown = false
        SessionController.viewerVisible(false)
        HiddenPause.viewerShown(false)
    }

    /** [sink] became visible or hidden; a newer viewer may already be the one attached. Main thread. */
    fun shown(sink: ViewerSink, visible: Boolean) {
        if (this.sink !== sink) return
        shown = visible
        SessionController.viewerVisible(visible)
        HiddenPause.viewerShown(visible)
        // Nobody sees the output while hidden: the apps needn't draw more than about once a second.
        client?.framebuffer?.let { fb -> waylandClient?.hidden(!visible, fb.width, fb.height) }
        if (visible) {
            val frames = frames
            if (frames != null) client?.let { sink.frame(it, frames) } // what arrived while hidden
            requestIfWanted()
        }
    }

    /**
     * The part of the framebuffer on screen ([whole]: all of it). Call when a zoom or pan
     * settles. Main thread.
     */
    fun visibleRegion(x: Int, y: Int, width: Int, height: Int, whole: Boolean) {
        // wayvnc sends every change whatever part is asked for: nothing to narrow or catch up.
        if (wayland) return
        val old = region
        val new = if (whole) null else intArrayOf(x, y, width, height)
        if (old.contentEquals(new)) return
        region = new
        DebugLog.i("Viewer: asking for ${new?.joinToString(",") ?: "all"}")
        val grew = new == null || old != null && !contains(old, new)
        val client = client
        if (!grew || !shown || client == null) return
        // At once, even with a request out: that one is for the old part, which may stay still and
        // never be answered. Xvnc kept what it didn't send, so this brings just what changed there.
        awaiting = true
        send { requestNow(client) }
    }

    private fun contains(outer: IntArray, inner: IntArray) =
        inner[0] >= outer[0] && inner[1] >= outer[1] &&
            inner[0] + inner[2] <= outer[0] + outer[2] && inner[1] + inner[3] <= outer[1] + outer[3]

    /** The last buttons sent: echoing a pointer move the server made keeps them as they are. */
    @Volatile private var buttons = 0
    /** Where the pointer is as far as the server knows. */
    @Volatile private var pointerX = 0
    @Volatile private var pointerY = 0

    fun pointer(buttons: Int, x: Int, y: Int) = client?.let { c ->
        this.buttons = buttons
        pointerX = x
        pointerY = y
        send { c.pointer(buttons, x, y) }
    }

    fun key(down: Boolean, keysym: Int) {
        if (Keysyms.outsideKeymap(keysym)) typed(down, keysym) else client?.let { c -> send { c.key(down, keysym) } }
    }

    /**
     * [keysym] as typed text: on Wayland, on [WaylandClient]'s keyboard, which wayvnc's would drop
     * if it isn't in its keymap. Text mixing the two all goes this way: the two paths could overtake each other.
     */
    fun typed(down: Boolean, keysym: Int) {
        val wayland = waylandClient?.takeIf { it.canType }
        if (wayland != null) wayland.key(down, keysym) else client?.let { c -> send { c.key(down, keysym) } }
    }

    fun resize(width: Int, height: Int) = client?.let { c -> send { c.resize(width, height) } }

    /** Touch scrolls go in small steps ([scrollSteps]): on Wayland, where the compositor offers a virtual pointer. */
    val scrollsInSteps: Boolean get() = waylandClient?.canScroll == true

    /**
     * What a touch scroll unit typically moves, in framebuffer pixels: a step on Wayland
     * ([WaylandClient.stepPixels]), else a wheel notch ([WHEEL_NOTCH]).
     */
    val typicalUnit: Float get() = waylandClient?.takeIf { it.canScroll }?.stepPixels ?: WHEEL_NOTCH

    /**
     * Framebuffer pixels of finger travel per touch scroll unit, down or [across]: the typical one
     * at the app's measured speed. Sideways, until measured, the speed down: most apps scroll as
     * far either way, while some scroll by a share of what they show, so less across a narrow window.
     */
    fun scrollUnit(across: Boolean): Float {
        val share = if (across && speedAcross.known) speedAcross.share else speed.share
        return typicalUnit * share
    }

    /** The app's speed down or [across] is still being learnt: the viewer measures drags ([scrollMeasured]). */
    fun measuringScroll(across: Boolean): Boolean {
        val speed = if (across) speedAcross else speed
        // On Wayland only once the virtual pointer is there: until then the typical unit is a notch's.
        if (!wayland || scrollsInSteps) client?.framebuffer?.let { speed.setup(if (across) it.width else it.height, typicalUnit) }
        return speed.measuring
    }

    // The app's speed down and across, and where speeds are kept: by app and display.
    private var speed = ScrollSpeed()
    private var speedAcross = ScrollSpeed()
    private var speedsFile: File? = null
    private var speedKey: String? = null

    /**
     * A drag's measurement, down or [across]: one unit moved the content [pixels] (framebuffer
     * pixels), or null when it gave no value. The speed is kept for the app's next session. What
     * came of it, for the log.
     */
    fun scrollMeasured(across: Boolean, pixels: Float?): String {
        val speed = if (across) speedAcross else speed
        val result = speed.measured(pixels?.div(typicalUnit))
        val file = speedsFile
        val key = speedKey
        if (pixels != null && file != null && key != null) {
            val all = speeds()
            all.setProperty(if (across) "$key$ACROSS" else key, speed.saved())
            runCatching { file.outputStream().use { all.store(it, null) } }.onFailure { DebugLog.w("Viewer: scroll speeds not saved: $it") }
        }
        return result
    }

    private fun speeds() = java.util.Properties().apply {
        runCatching { speedsFile?.takeIf { it.isFile }?.inputStream()?.use(::load) }
    }

    /** The file in the cache that keeps each app's scroll speed. */
    private const val SCROLL_SPEEDS = "scroll-speeds.properties"
    /** The key's ending for an app's speed across. */
    private const val ACROSS = ".across"

    /**
     * What a wheel notch typically scrolls, in the app's pixels. Measured: a terminal about 45
     * (three lines), GTK 3 about 100 in a 1000 pixel tall window (it grows with the window),
     * browsers 115 to 120.
     */
    const val WHEEL_NOTCH = 100f

    /** Scrolls [down] and [right] steps at ([x], [y]) ([WaylandClient.scroll]). */
    fun scrollSteps(x: Int, y: Int, down: Int, right: Int) {
        val fb = client?.framebuffer ?: return
        waylandClient?.scroll(x, y, fb.width, fb.height, down, right)
    }

    /** A touch scroll in steps stopped. */
    fun scrollEnd() {
        waylandClient?.scrollEnd()
    }

    /** Android's clipboard has new text: announced (also to a connection still being made); read only when an app pastes. */
    @Volatile private var clipboardOffered = false
    private var wayland = false
    @Volatile private var waylandClient: WaylandClient? = null
    private const val CLIPBOARD_WAIT_MS = 2_000L

    fun announceClipboard() {
        // wayvnc would take the text at once, not when an app pastes.
        if (wayland) return waylandClient?.offer() ?: Unit
        clipboardOffered = true
        client?.let { c -> send { c.announceClipboard() } }
    }

    /** Android's text for an app pasting, from [WaylandClient]'s thread (Android gives it on the main one). */
    private fun androidText(): String? {
        val done = CountDownLatch(1)
        var text: String? = null
        main.post {
            text = sink?.clipboardText()
            done.countDown()
        }
        done.await(CLIPBOARD_WAIT_MS, TimeUnit.MILLISECONDS)
        return text
    }

    /** [close] from any thread. */
    fun closeOnMain() {
        if (Looper.myLooper() == Looper.getMainLooper()) close() else main.post(::close)
    }

    /** Ends the connection (the session ended, or a new one starts). Main thread. */
    fun close() {
        val socket = socket
        this.socket = null
        client = null
        frames = null
        path = null
        region = null
        clipboardOffered = false
        waylandClient?.close()
        waylandClient = null
        wayland = false
        awaiting = false
        framePosted = false
        sender?.shutdown()
        sender = null
        runCatching { socket?.shutdownInput() }
        runCatching { socket?.close() }
    }

    private fun send(action: () -> Unit) {
        val executor = sender ?: return
        runCatching { executor.execute { runCatching(action) } }
    }
}
