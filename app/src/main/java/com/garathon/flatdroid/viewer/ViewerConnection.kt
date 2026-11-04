package com.garathon.flatdroid.viewer

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import com.garathon.flatdroid.util.DebugLog
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
 * While no viewer is shown it asks for no updates.
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
    /** The region grew since the last request: ask for it whole once (parts never asked for may be stale). */
    private var regionGrew = false

    /**
     * Connects to [socketPath] unless already connected there. In a [wayland] session Android's
     * text goes through [WaylandClipboard] on [clipboardSocket] (null: not shared). Main thread.
     */
    fun open(socketPath: String, cacheDir: File, wayland: Boolean = false, clipboardSocket: String? = null) {
        if (path == socketPath && client != null) return
        close()
        path = socketPath
        this.wayland = wayland
        waylandClipboard = clipboardSocket?.let { WaylandClipboard(it, ::androidText).apply { start() } }
        val executor = Executors.newSingleThreadExecutor().also { sender = it }
        val socket = LocalSocket().also { socket = it }
        Thread({ read(socket, socketPath, executor, cacheDir) }, "viewer-reader").start()
    }

    private fun read(socket: LocalSocket, socketPath: String, executor: ExecutorService, cacheDir: File) {
        var error: String? = null
        var native: NativeFrames? = null
        try {
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            val tileSize = ViewerDebug.tileSize(cacheDir)
            native = if (tileSize == null) NativeFrames.open(socket) else null
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
                    executor.execute { runCatching { client.pointer(buttons, x, y) } }
                }

                override fun resized(width: Int, height: Int) {
                    // X keeps the pointer inside a smaller screen: follow it there, as above.
                    pointerX = pointerX.coerceAtMost(width - 1)
                    pointerY = pointerY.coerceAtMost(height - 1)
                    val x = pointerX
                    val y = pointerY
                    executor.execute { runCatching { client.pointer(buttons, x, y) } }
                }

                override fun clipboard(text: String) {
                    main.post { if (this@ViewerConnection.client === client) sink?.clipboard(text) }
                }

                override fun clipboardRequested() {
                    // Android's clipboard is read on the main thread, and only now that an app pastes.
                    main.post {
                        if (this@ViewerConnection.client !== client) return@post
                        val text = sink?.clipboardText().orEmpty()
                        executor.execute { runCatching { client.provideClipboard(text) } }
                    }
                }
            }
            while (true) client.readMessage(listener)
        } catch (_: EOFException) {
            // The server closed the connection: the session is ending.
        } catch (e: IOException) {
            if (this.socket === socket) error = e.message ?: e.javaClass.simpleName
        }
        native?.close()
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
        val full = regionGrew
        regionGrew = false
        send { requestNow(client, incremental = !full) }
    }

    private fun requestNow(client: RfbClient, incremental: Boolean = true) {
        val r = region
        if (r == null) client.requestUpdate(incremental) else client.requestUpdate(incremental, r[0], r[1], r[2], r[3])
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
    }

    /** The viewer became visible or hidden. Main thread. */
    fun shown(visible: Boolean) {
        shown = visible
        if (visible) {
            val frames = frames
            if (frames != null) client?.let { sink?.frame(it, frames) } // what arrived while hidden
            requestIfWanted()
        }
    }

    /**
     * The part of the framebuffer on screen ([whole]: all of it). Call when a zoom or pan
     * settles. Main thread.
     */
    fun visibleRegion(x: Int, y: Int, width: Int, height: Int, whole: Boolean) {
        val old = region
        val new = if (whole) null else intArrayOf(x, y, width, height)
        if (old.contentEquals(new)) return
        region = new
        DebugLog.i("Viewer: asking for ${new?.joinToString(",") ?: "all"}")
        regionGrew = new == null || old != null && !contains(old, new)
        // A region never asked for while paced may wait on a request that won't come: ask now.
        requestIfWanted()
    }

    private fun contains(outer: IntArray, inner: IntArray) =
        inner[0] >= outer[0] && inner[1] >= outer[1] &&
            inner[0] + inner[2] <= outer[0] + outer[2] && inner[1] + inner[3] <= outer[1] + outer[3]

    /** How many pixels of the framebuffer one wheel notch scrolls the app, as last measured ([ScrollMeter]). */
    var scrollNotch = ScrollMeter.DEFAULT_NOTCH
    /** [scrollNotch] was measured in this session (else a drag first measures one notch). */
    var scrollNotchMeasured = false

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

    fun key(down: Boolean, keysym: Int) = client?.let { c -> send { c.key(down, keysym) } }

    fun resize(width: Int, height: Int) = client?.let { c -> send { c.resize(width, height) } }

    /** Android's clipboard has new text: announced (also to a connection still being made); read only when an app pastes. */
    @Volatile private var clipboardOffered = false
    private var wayland = false
    private var waylandClipboard: WaylandClipboard? = null
    private const val CLIPBOARD_WAIT_MS = 2_000L

    fun announceClipboard() {
        // wayvnc would take the text at once, not when an app pastes.
        if (wayland) return waylandClipboard?.offer() ?: Unit
        clipboardOffered = true
        client?.let { c -> send { c.announceClipboard() } }
    }

    /** Android's text for an app pasting, from [WaylandClipboard]'s thread (Android gives it on the main one). */
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
        scrollNotch = ScrollMeter.DEFAULT_NOTCH
        scrollNotchMeasured = false
        clipboardOffered = false
        waylandClipboard?.close()
        waylandClipboard = null
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
