package com.garathon.flatdroid.viewer

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.garathon.flatdroid.settings.Resolution
import com.garathon.flatdroid.util.DebugLog
import com.garathon.flatdroid.util.DebugMeter
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The session's screen: the framebuffer ([FrameDrawer]) drawn fitted to the view (black bars around it), with
 * pinch to zoom and two-finger pan (let go at speed, it goes on). While the keyboard or the extra
 * keys are up, the frame can also be zoomed out and moved above them; it goes back when they close.
 * One finger drives the pointer through [TouchGestures]; a mouse drives it directly (Android draws
 * its pointer; the server draws none, see [Rfb.ENCODINGS]).
 */
@SuppressLint("ViewConstructor")
class ViewerView(context: Context, private val connection: ViewerConnection) : View(context), ViewerSink, PointerOutput {
    var onClosed: ((String?) -> Unit)? = null
    /** Key presses, with the extra keys row's latched Ctrl and Alt. */
    val keys = ViewerKeys(connection::key, connection::typed)
    /** Keysyms sent for keys held down, by key code: the release sends the same one. */
    private val heldKeys = HashMap<Int, Int>()
    /** Modifiers pressed for a key whose event only said they were on, by key code: released with it. */
    private val addedModifiers = HashMap<Int, List<Int>>()
    var onClipboard: ((String) -> Unit)? = null
    var onClipboardRequested: (() -> String?)? = null
    /** A keyboard committed more text than is typed as keys ([MAX_TYPED]). */
    var onLongText: ((String) -> Unit)? = null
    /** Three fingers tapped (the soft keyboard opens or closes). */
    var onThreeFingerTap: (() -> Unit)? = null

    /** The framebuffer's size as drawn; 0 before the first frame. */
    private var frameWidth = 0
    private var frameHeight = 0
    /** How frames reach the screen, from the connection; null before the first frame. */
    private var frames: FrameDrawer? = null
    /** A surface behind this view that frames can go to straight (see [FrameDrawer.useSurface]); null while there is none. */
    private var surface: SurfaceView? = null
    /** [frames] go to [surface]: this view draws nothing. */
    private var onSurface = false
    private val destination = RectF()
    /** The part of the framebuffer on screen, in its pixels (from [visiblePart]). */
    private val visible = Rect()
    private val visibleRect = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val placement = Matrix()
    private val inverse = Matrix()
    private val point = FloatArray(2)

    /** The screen is this share of the view's size and follows it ([Resolution]); null for a fixed size. */
    var followPercent: Int? = null
    /** The size last asked of the server, so a refused one isn't asked again. */
    private var requestedSize: Pair<Int, Int>? = null
    /** Asks the server for the screen size that fits the view, once it has settled (rotation, first frame). */
    private val resizeCheck = Runnable {
        val percent = followPercent ?: return@Runnable
        if (frameWidth == 0 || width == 0 || height == 0) return@Runnable
        val wanted = Resolution.entries.first { it.percent == percent }.scaled(width, height)
        if (wanted == frameWidth to frameHeight || wanted == requestedSize) return@Runnable
        requestedSize = wanted
        connection.resize(wanted.first, wanted.second)
    }

    /** Pixels at the bottom of the view under the keyboard and the extra keys now: the frame can be moved above them. */
    private var coverHeight = 0
    /**
     * The same, as far as asking and copying go: what lies there isn't asked for or copied until
     * uncovered. Follows [coverHeight] once it has settled, as the keyboard slides in over several frames.
     */
    private var covered = 0
    private val coverSettled = Runnable {
        covered = coverHeight
        reportVisible()
    }

    /** How the frame lay before the keyboard came up. */
    private class Placement(val zoom: Float, val x: Float, val y: Float, val moved: Boolean)
    private var beforeCover: Placement? = null
    private var placing: ValueAnimator? = null

    /**
     * The keyboard and the extra keys cover [px] at the bottom of the view. The moment they start
     * closing all of the view counts again; once closed, the frame goes back to how it lay.
     */
    fun setCovered(px: Int) {
        val value = px.coerceAtLeast(0)
        if (value == coverHeight) return
        val closing = value < coverHeight
        if (coverHeight == 0) beforeCover = Placement(zoom, offsetX, offsetY, moved)
        coverHeight = value
        removeCallbacks(coverSettled)
        postDelayed(coverSettled, COVER_SETTLE_MS)
        if (closing && covered > 0) {
            covered = 0
            reportVisible()
            copyHidden()
        }
        zoom = zoom.coerceAtLeast(minZoom())
        layoutFrame()
        if (value == 0) {
            beforeCover?.let(::placeBack)
            beforeCover = null
        }
    }

    /** Moves the frame back to [to], smoothly. */
    private fun placeBack(to: Placement) {
        if (zoom == to.zoom && offsetX == to.x && offsetY == to.y) return
        val fromZoom = zoom
        val fromX = offsetX
        val fromY = offsetY
        moved = to.moved
        placing = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PLACE_BACK_MS
            addUpdateListener {
                val t = it.animatedFraction
                zoom = fromZoom + (to.zoom - fromZoom) * t
                offsetX = fromX + (to.x - fromX) * t
                offsetY = fromY + (to.y - fromY) * t
                layoutFrame()
                if (t == 1f) reportVisible()
            }
            start()
        }
    }

    private fun stopPlacing() {
        placing?.cancel()
        placing = null
    }

    /** Two fingers moved the frame: no longer kept centred in the black bars ([layoutFrame]). */
    private var moved = false

    /** Zoom relative to fitting the view; 1 = fitted. */
    private var zoom = 1f
    /** The framebuffer's top-left corner on the view. */
    private var offsetX = 0f
    private var offsetY = 0f

    private val copyMeter = DebugMeter("Viewer", "frames", listOf("scroll", "copy"))

    private val config = ViewConfiguration.get(context)
    private val gestures = TouchGestures(this, config.scaledTouchSlop.toFloat(), ScrollMeter.DEFAULT_NOTCH)

    /** The connection's client, from the last frame: a scroll measurement reads its framebuffer. */
    private var client: RfbClient? = null
    private val scrollMeter = ScrollMeter()
    /** A scroll is being measured until the content settles, [SCROLL_SETTLE_MS] after the last notch. */
    private var measuring = false
    private var measureUntil = 0L
    /** The row hashes of the frame being measured ([ScrollMeter.hashRows]). */
    private var rowHashes = IntArray(0)
    /**
     * Until a notch is measured in the session, a drag sends one notch and holds the rest back
     * until the content has stopped moving ([PROBE_QUIET_MS], at most [PROBE_MAX_MS]), then
     * catches up at the measured distance. At most [MAX_PROBES] times a drag.
     */
    private var probing = false
    private var probesThisDrag = 0
    private val endProbe = Runnable { finishProbe() }
    private val hold = Runnable { gestures.holdRecognised() }

    /** A drag let go with speed goes on, slowing down as Android's lists do. */
    private var velocity: VelocityTracker? = null
    private val flinger = OverScroller(context)
    private var flingFromX = 0f
    private var flingFromY = 0f
    private val flingStep = object : Runnable {
        override fun run() {
            if (!flinger.computeScrollOffset()) return
            gestures.flingTo(flingFromX + flinger.currX, flingFromY + flinger.currY)
            if (!flinger.isFinished) postOnAnimation(this)
        }
    }
    /** Two fingers let go at speed: the frame goes on moving, slowing down. */
    private val panFlinger = OverScroller(context)
    private var panFlungX = 0
    private var panFlungY = 0
    private val panFlingStep = object : Runnable {
        override fun run() {
            if (!panFlinger.computeScrollOffset()) return reportVisible()
            val dx = panFlinger.currX - panFlungX
            val dy = panFlinger.currY - panFlungY
            panFlungX = panFlinger.currX
            panFlungY = panFlinger.currY
            val x = offsetX
            val y = offsetY
            offsetX += dx
            offsetY += dy
            layoutFrame()
            // Against the edges: nothing left to move.
            val stuck = (dx != 0 || dy != 0) && offsetX == x && offsetY == y
            when {
                stuck -> stopPanFling()
                panFlinger.isFinished -> reportVisible()
                else -> postOnAnimation(this)
            }
        }
    }
    private var mouseButtons = 0
    private var wheelUp = 0f
    private var wheelRight = 0f
    private var pinching = false

    /** A three-finger tap may be under way: three fingers down, none moved, not too long ago. */
    private var threeFingerTap = false
    private var threeFingerTime = 0L
    private val fingerStarts = FloatArray(6)

    /** Two or more fingers are down: they pan (by their midpoint) and pinch, never the pointer. */
    private var twoFingers = false
    private var panX = 0f
    private var panY = 0f

    // Pans first ([pan]), so the scaling is around where the fingers are now.
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinching = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = scale()
            zoom = (zoom * detector.scaleFactor).coerceIn(minZoom(), MAX_ZOOM)
            val ratio = scale() / old
            // The point under the fingers stays under them.
            offsetX = detector.focusX - (detector.focusX - offsetX) * ratio
            offsetY = detector.focusY - (detector.focusY - offsetY) * ratio
            layoutFrame()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            pinching = false
            reportVisible()
        }
    })

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ---- Frames

    override fun frame(client: RfbClient, frames: FrameDrawer) {
        this.client = client
        if (frames !== this.frames) {
            this.frames?.useSurface(null)
            this.frames = frames
            useSurface()
        }
        copyMeter.start()
        var copied: Boolean
        synchronized(client.frameLock) {
            val fb = client.framebuffer
            if (measuring) measureFrame(fb, frames)
            copyMeter.step(0)
            if (fb.width != frameWidth || fb.height != frameHeight) {
                moved = false
                beforeCover = null
                frameWidth = fb.width
                frameHeight = fb.height
                layoutFrame()
                reportVisible()
                // The server's first frame says whether it can be resized: check once it is here.
                removeCallbacks(resizeCheck)
                post(resizeCheck)
            }
            visiblePart()
            copied = frames.frame(fb, visible)
            if (copied && onSurface) showFrame()
        }
        copyMeter.step(1)
        copyMeter.round()
        if (copied && !onSurface) invalidate()
    }

    /** A surface behind this view is ready for frames, or gone (null). Main thread. */
    fun surfaceChanged(surface: SurfaceView?) {
        if (surface === this.surface) return
        frames?.useSurface(null)
        this.surface = surface
        useSurface()
    }

    private fun useSurface() {
        val frames = frames ?: return
        val client = client ?: return
        synchronized(client.frameLock) {
            onSurface = frames.useSurface(surface)
            if (onSurface) showFrame()
        }
        setBackgroundColor(if (onSurface) Color.TRANSPARENT else Color.BLACK)
        if (!onSurface) invalidate()
    }

    /** Places the frame on the surface as [matrix] says. Under [RfbClient.frameLock]. */
    private fun showFrame() {
        if (frameWidth == 0) return
        destination.set(0f, 0f, frameWidth.toFloat(), frameHeight.toFloat())
        matrix.mapRect(destination)
        frames?.show(destination)
    }

    /** Starts measuring how far a notch scrolls the content, from the frame as it is now. */
    private fun startMeasuring() {
        if (measuring && SystemClock.uptimeMillis() > measureUntil) finishMeasuring()
        if (measuring) return
        val client = client ?: return
        synchronized(client.frameLock) {
            val fb = client.framebuffer
            if (fb.width != frameWidth || fb.height != frameHeight) return
            val size = fb.height * ScrollMeter.BANDS
            if (rowHashes.size != size) rowHashes = IntArray(size)
            if (frames?.hashRows(fb, rowHashes) != true) return
            scrollMeter.start(rowHashes, fb.height)
        }
        measuring = true
    }

    /** A new frame while measuring (under the frame lock): how far the content moved since the last. */
    private fun measureFrame(fb: Framebuffer, frames: FrameDrawer) {
        if (fb.width != frameWidth || fb.height * ScrollMeter.BANDS != rowHashes.size) {
            measuring = false // resized meanwhile
            scrollMeter.finish()
            return
        }
        if (!frames.hashRows(fb, rowHashes)) return // the next update is being read: measured from the next frame
        val moved = scrollMeter.shift(rowHashes)
        scrollMeter.movedBy(moved)
        if (probing && moved != 0) {
            removeCallbacks(endProbe)
            postDelayed(endProbe, PROBE_QUIET_MS)
        }
        if (SystemClock.uptimeMillis() > measureUntil) finishMeasuring()
    }

    private fun finishMeasuring() {
        measuring = false
        if (probing) finishProbe()
        scrollMeter.finish()?.let(::useNotch)
    }

    private fun finishProbe() {
        removeCallbacks(endProbe)
        probing = false
        val notch = scrollMeter.estimate(minNotches = 1)
        if (notch != null) {
            useNotch(notch)
        } else {
            // Nothing recognisable moved (an app can redraw more than the scroll the first time,
            // or there's nothing left to scroll): measured afresh from the next notch on.
            measuring = false
            scrollMeter.finish()
        }
        gestures.resume()
    }

    private fun useNotch(notch: Float) {
        DebugLog.i("Viewer: a wheel notch scrolls %.1f px".format(java.util.Locale.ROOT, notch))
        ViewerConnection.scrollNotch = notch
        ViewerConnection.scrollNotchMeasured = true
        gestures.scrollStep = notch * scale()
    }

    /** The part of the framebuffer on screen now, above the keyboard and the extra keys, into [visible]. */
    private fun visiblePart() {
        visibleRect.set(0f, 0f, width.toFloat(), (height - covered).coerceAtLeast(1).toFloat())
        inverse.mapRect(visibleRect)
        visible.set(
            visibleRect.left.toInt().coerceIn(0, frameWidth), visibleRect.top.toInt().coerceIn(0, frameHeight),
            (visibleRect.right.toInt() + 1).coerceIn(0, frameWidth), (visibleRect.bottom.toInt() + 1).coerceIn(0, frameHeight),
        )
    }

    /** Catches up what changed out of sight and is in sight now (the keyboard closed, the view zoomed out or moved). */
    private fun copyHidden() {
        val frames = frames ?: return
        if (!frames.hasHidden) return
        val client = client ?: return
        visiblePart()
        val copied = synchronized(client.frameLock) { frames.reveal(client.framebuffer, visible) }
        if (copied) invalidate()
    }

    override fun clipboard(text: String) {
        onClipboard?.invoke(text)
    }

    override fun clipboardText(): String? = onClipboardRequested?.invoke()

    override fun zoomTo(zoom: Float) {
        if (frameWidth == 0) return
        this.zoom = zoom.coerceIn(1f, MAX_ZOOM)
        offsetX = width / 2f - frameWidth * scale() / 2
        offsetY = height / 2f - frameHeight * scale() / 2
        layoutFrame()
        reportVisible()
    }

    override fun closed(error: String?) {
        onClosed?.invoke(error)
    }

    override fun onDraw(canvas: Canvas) {
        if (onSurface) return
        val frames = frames ?: return
        // Compose doesn't clip an embedded view: a zoomed or panned frame would cover the bar above.
        canvas.clipRect(0, 0, width, height)
        // Unscaled: no filtering needed.
        paint.isFilterBitmap = scale() != 1f
        frames.draw(canvas, matrix, paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Keys go to the focused view: this one, not the bar's buttons (focus asked for before
        // the view is attached is lost).
        post { requestFocus() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        moved = false
        beforeCover = null
        layoutFrame()
        reportVisible()
        // Turning the phone changes the size in steps: resize once it settles.
        removeCallbacks(resizeCheck)
        postDelayed(resizeCheck, RESIZE_DELAY_MS)
    }

    private fun fitScale(): Float {
        if (frameWidth == 0 || width == 0 || height == 0) return 1f
        return min(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
    }

    private fun scale() = fitScale() * zoom

    /** The height above the keyboard and the extra keys. */
    private fun uncovered() = (height - coverHeight).coerceAtLeast(1).toFloat()

    /** Fitted, or while something covers the view, all of the frame above it. */
    private fun minZoom(): Float {
        if (coverHeight == 0 || frameWidth == 0 || width == 0) return 1f
        return (min(width.toFloat() / frameWidth, uncovered() / frameHeight) / fitScale()).coerceAtMost(1f)
    }

    /**
     * Clamps the offset (inside the view when smaller than it, centred until [moved]; else no
     * black past the edges, but what the keyboard covers can be moved above it) and updates the matrix.
     */
    private fun layoutFrame() {
        if (frameWidth == 0) return
        val s = scale()
        val w = frameWidth * s
        val h = frameHeight * s
        val room = uncovered()
        // Smaller than the view: centred until moved, then anywhere within the black bars.
        offsetX = if (w <= width) (if (moved) offsetX.coerceIn(0f, width - w) else (width - w) / 2) else offsetX.coerceIn(width - w, 0f)
        offsetY = when {
            h > height -> offsetY.coerceIn(room - h, 0f)
            moved -> offsetY.coerceIn(min(0f, room - h), height - h)
            zoom < 1f -> (room - h) / 2
            else -> (height - h) / 2
        }
        placement.setScale(s, s)
        placement.postTranslate(offsetX, offsetY)
        // The keyboard's every frame comes here: nothing to redraw unless the frame moved.
        if (placement == matrix) return
        matrix.set(placement)
        matrix.invert(inverse)
        copyHidden()
        if (onSurface) client?.let { synchronized(it.frameLock) { showFrame() } } else invalidate()
    }

    /** Tells the connection which part of the framebuffer is on screen (all of it while two fingers move it, and it flies on). */
    private fun reportVisible() {
        if (twoFingers || !panFlinger.isFinished) return
        if (frameWidth == 0) return
        if (zoom <= 1f && covered == 0) return connection.visibleRegion(0, 0, frameWidth, frameHeight, whole = true)
        visiblePart()
        connection.visibleRegion(visible.left, visible.top, visible.width(), visible.height(), whole = visible.width() == frameWidth && visible.height() == frameHeight)
    }

    // ---- Touch

    /** The midpoint of the fingers that stay down ([skip]: the one being lifted, or -1). */
    private fun midpoint(event: MotionEvent, skip: Int): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            x += event.getX(i); y += event.getY(i); n++
        }
        return if (n == 0) 0f to 0f else x / n to y / n
    }

    /** Two-finger drag: moves the framebuffer with the fingers' midpoint (when zoomed in; fitted it stays centred). */
    private fun pan(event: MotionEvent) {
        val (x, y) = midpoint(event, -1)
        moved = true
        offsetX += x - panX
        offsetY += y - panY
        panX = x; panY = y
        layoutFrame()
    }

    private fun endTwoFingers() {
        if (!twoFingers) return
        twoFingers = false
        reportVisible()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return mouse(event)
        if (twoFingers) velocity?.addMovement(event)
        if (twoFingers && event.actionMasked == MotionEvent.ACTION_MOVE) pan(event)
        if (threeFingerTap && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val slop = config.scaledTouchSlop
            for (i in 0 until minOf(3, event.pointerCount)) {
                if (kotlin.math.abs(event.getX(i) - fingerStarts[2 * i]) > slop || kotlin.math.abs(event.getY(i) - fingerStarts[2 * i + 1]) > slop) {
                    threeFingerTap = false
                }
            }
        }
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hasFocus()) requestFocus()
                // A touch stops a fling, as in Android's lists.
                stopFling()
                stopPanFling()
                stopPlacing()
                (velocity ?: VelocityTracker.obtain().also { velocity = it }).clear()
                velocity?.addMovement(event)
                // A notch's worth of the framebuffer, at the current zoom: the content follows the finger.
                gestures.scrollStep = ViewerConnection.scrollNotch * scale()
                probesThisDrag = 0
                gestures.down(event.x, event.y)
                postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(hold)
                stopFling()
                gestures.cancel()
                threeFingerTap = event.pointerCount == 3 && event.eventTime - event.downTime < THREE_FINGER_TAP_MS
                if (threeFingerTap) {
                    threeFingerTime = event.eventTime
                    for (i in 0 until 3) { fingerStarts[2 * i] = event.getX(i); fingerStarts[2 * i + 1] = event.getY(i) }
                }
                if (!twoFingers) {
                    twoFingers = true
                    velocity?.clear()
                    velocity?.addMovement(event)
                    // Zooming and panning reveal parts not asked for while zoomed in: the whole screen until the fingers lift.
                    if (frameWidth > 0) connection.visibleRegion(0, 0, frameWidth, frameHeight, whole = true)
                }
                midpoint(event, -1).let { (x, y) -> panX = x; panY = y }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // The midpoint of the fingers left, so the next move doesn't jump.
                midpoint(event, event.actionIndex).let { (x, y) -> panX = x; panY = y }
                if (event.pointerCount - 1 < 2) {
                    if (twoFingers) panFling(event)
                    endTwoFingers()
                }
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !twoFingers && !pinching) {
                velocity?.addMovement(event)
                gestures.move(event.x, event.y)
                if (!gestures.waitingForHold) removeCallbacks(hold)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                val wasScrolling = gestures.scrolling && !twoFingers
                gestures.up(event.x, event.y)
                if (wasScrolling) fling(event)
                endTwoFingers()
                if (threeFingerTap && event.eventTime - threeFingerTime < THREE_FINGER_TAP_MS) onThreeFingerTap?.invoke()
                threeFingerTap = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                stopFling()
                stopPanFling()
                gestures.cancel()
                endTwoFingers()
                threeFingerTap = false
            }
        }
        return true
    }

    /** Goes on scrolling after a drag let go at speed (Android's fling: its minimum speed, decay and maximum). */
    private fun fling(event: MotionEvent) {
        val tracker = velocity ?: return
        tracker.addMovement(event)
        tracker.computeCurrentVelocity(1000, config.scaledMaximumFlingVelocity.toFloat())
        val vx = tracker.xVelocity
        val vy = tracker.yVelocity
        if (kotlin.math.hypot(vx, vy) < config.scaledMinimumFlingVelocity) return
        flingFromX = event.x
        flingFromY = event.y
        flinger.fling(0, 0, vx.roundToInt(), vy.roundToInt(), Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
        postOnAnimation(flingStep)
    }

    private fun stopFling() {
        flinger.forceFinished(true)
        removeCallbacks(flingStep)
    }

    /** The fingers' pan goes on from their speed as the first lifts ([event]), its limits Android's. */
    private fun panFling(event: MotionEvent) {
        val tracker = velocity ?: return
        tracker.computeCurrentVelocity(1000, config.scaledMaximumFlingVelocity.toFloat())
        var vx = 0f
        var vy = 0f
        for (i in 0 until event.pointerCount) {
            vx += tracker.getXVelocity(event.getPointerId(i))
            vy += tracker.getYVelocity(event.getPointerId(i))
        }
        vx /= event.pointerCount
        vy /= event.pointerCount
        if (kotlin.math.hypot(vx, vy) < config.scaledMinimumFlingVelocity) return
        panFlungX = 0
        panFlungY = 0
        panFlinger.fling(0, 0, vx.roundToInt(), vy.roundToInt(), Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
        postOnAnimation(panFlingStep)
    }

    private fun stopPanFling() {
        if (panFlinger.isFinished) return
        panFlinger.forceFinished(true)
        removeCallbacks(panFlingStep)
        reportVisible()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) releaseKeys()
    }

    /** The key-ups would go elsewhere now: the app would go on seeing the keys held. */
    private fun releaseKeys() {
        heldKeys.values.forEach(keys::up)
        heldKeys.clear()
        addedModifiers.values.flatten().forEach(keys::up)
        addedModifiers.clear()
        keys.releaseAll()
    }

    override fun onDetachedFromWindow() {
        surfaceChanged(null)
        releaseKeys()
        stopFling()
        stopPanFling()
        stopPlacing()
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }

    /** View coordinates to the framebuffer's, inside it. */
    private fun remote(x: Float, y: Float): Pair<Int, Int> {
        if (frameWidth == 0) return 0 to 0
        point[0] = x; point[1] = y
        inverse.mapPoints(point)
        return point[0].toInt().coerceIn(0, frameWidth - 1) to point[1].toInt().coerceIn(0, frameHeight - 1)
    }

    private fun pointer(buttons: Int, x: Float, y: Float) {
        val (rx, ry) = remote(x, y)
        connection.pointer(buttons, rx, ry)
    }

    override fun click(button: Int, x: Float, y: Float) {
        pointer(0, x, y)
        pointer(1 shl button, x, y)
        pointer(0, x, y)
    }

    override fun press(button: Int, x: Float, y: Float) {
        pointer(0, x, y)
        mouseButtons = 1 shl button
        pointer(mouseButtons, x, y)
    }

    override fun move(x: Float, y: Float) = pointer(mouseButtons, x, y)

    override fun release(x: Float, y: Float) {
        mouseButtons = 0
        pointer(0, x, y)
    }

    override fun scroll(x: Float, y: Float, down: Int, right: Int) {
        if (down != 0) {
            startMeasuring()
            scrollMeter.sent(down)
            measureUntil = SystemClock.uptimeMillis() + SCROLL_SETTLE_MS
            if (measuring && !ViewerConnection.scrollNotchMeasured && !probing && probesThisDrag < MAX_PROBES) {
                probing = true
                probesThisDrag++
                gestures.pause()
                postDelayed(endProbe, PROBE_MAX_MS)
            }
        }
        wheel(x, y, if (down > 0) WHEEL_DOWN else WHEEL_UP, kotlin.math.abs(down))
        wheel(x, y, if (right > 0) WHEEL_RIGHT else WHEEL_LEFT, kotlin.math.abs(right))
    }

    private fun wheel(x: Float, y: Float, bit: Int, notches: Int) {
        repeat(notches) {
            pointer(mouseButtons or (1 shl bit), x, y)
            pointer(mouseButtons, x, y)
        }
    }

    override fun held() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    // ---- Keyboard

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password text: most keyboards then offer no suggestions or autocorrection,
        // which would otherwise retype words the app already received.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_NONE
        return KeyInput(this, keys) { onLongText?.invoke(it) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Keysyms.editKey(keyCode)?.let { if (event.repeatCount == 0) keys.chord(it); return true }
        val keysym = keysym(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0 && !Keysyms.isModifier(keysym)) {
            // A keyboard app's shortcut (Undo as Z with Ctrl on) comes as one event: no Ctrl key of its own.
            val added = missingModifiers(event)
            added.forEach { keys.down(it) }
            addedModifiers[keyCode] = added
        }
        heldKeys[keyCode] = keysym
        keys.down(keysym)
        return true
    }

    /**
     * Ctrl, Alt, Meta and Shift that [event] says are on but no held key presses. Shift too, though
     * the character is already capital: with Ctrl, X apps read the key, not the character (Redo
     * as Ctrl+Shift+Z).
     */
    private fun missingModifiers(event: KeyEvent): List<Int> {
        val held = heldKeys.values
        val shortcut = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
        return listOfNotNull(
            Keysyms.SHIFT_L.takeIf { shortcut && event.isShiftPressed && Keysyms.SHIFT_L !in held && Keysyms.SHIFT_R !in held },
            Keysyms.CONTROL_L.takeIf { event.isCtrlPressed && Keysyms.CONTROL_L !in held && Keysyms.CONTROL_R !in held },
            Keysyms.ALT_L.takeIf { event.isAltPressed && Keysyms.ALT_L !in held && Keysyms.ALT_R !in held },
            Keysyms.SUPER_L.takeIf { event.isMetaPressed && Keysyms.SUPER_L !in held && Keysyms.SUPER_R !in held },
        )
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (Keysyms.editKey(keyCode) != null) return true
        val keysym = heldKeys.remove(keyCode) ?: return super.onKeyUp(keyCode, event)
        keys.up(keysym)
        addedModifiers.remove(keyCode)?.asReversed()?.forEach { keys.up(it) }
        return true
    }

    /**
     * The keysym for a key: its own for keys that aren't characters, else the character it types
     * without Ctrl and Meta (sent as keys of their own), and without Alt if Alt makes none. Back,
     * volume and other system keys have none and stay Android's.
     */
    private fun keysym(keyCode: Int, event: KeyEvent): Int? {
        if (keyCode == KeyEvent.KEYCODE_BACK) return null
        Keysyms.ofKeyCode(keyCode)?.let { return it }
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK).inv()
        var char = event.getUnicodeChar(meta)
        if (char == 0) char = event.getUnicodeChar(meta and KeyEvent.META_ALT_MASK.inv())
        if (char == 0 || char and KeyCharacterMap.COMBINING_ACCENT != 0) return null
        return Keysyms.ofChar(char)
    }

    // ---- Mouse

    /** Android's buttons to RFB's mask: left, middle, right in bits 0, 1, 2. */
    private fun buttons(state: Int): Int =
        (if (state and MotionEvent.BUTTON_PRIMARY != 0) 1 else 0) or
            (if (state and MotionEvent.BUTTON_TERTIARY != 0) 2 else 0) or
            (if (state and MotionEvent.BUTTON_SECONDARY != 0) 4 else 0)

    private fun mouse(event: MotionEvent): Boolean {
        mouseButtons = buttons(event.buttonState)
        pointer(mouseButtons, event.x, event.y)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onGenericMotionEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> mouse(event)
            MotionEvent.ACTION_SCROLL -> {
                // One notch per unit, what is left of one kept for the next event (fine wheels and
                // trackpads send fractions); Android's vertical axis is positive upwards, its horizontal one to the right.
                wheelUp += event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                wheelRight += event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val v = wheelUp.toInt()
                val h = wheelRight.toInt()
                wheelUp -= v
                wheelRight -= h
                wheel(event.x, event.y, if (v > 0) WHEEL_UP else WHEEL_DOWN, kotlin.math.abs(v))
                wheel(event.x, event.y, if (h > 0) WHEEL_RIGHT else WHEEL_LEFT, kotlin.math.abs(h))
            }
            else -> return super.onGenericMotionEvent(event)
        }
        return true
    }

    companion object {
        private const val MAX_ZOOM = 8f
        /** After the last notch, how long the content may still move (apps that scroll smoothly animate it). */
        private const val SCROLL_SETTLE_MS = 600L
        /** The keyboard has stopped moving when the covered height stays this long. */
        private const val COVER_SETTLE_MS = 150L
        /** The frame's way back to where it lay, once the keyboard has closed. */
        private const val PLACE_BACK_MS = 200L
        /** The first notch's measurement ends once the content stood still this long, or after [PROBE_MAX_MS]. */
        private const val PROBE_QUIET_MS = 120L
        private const val PROBE_MAX_MS = 500L
        /** A second try, after a notch whose move wasn't recognised. */
        private const val MAX_PROBES = 2
        private const val RESIZE_DELAY_MS = 250L
        /** Three fingers down and up within this: a tap, not the start of a pinch. */
        private const val THREE_FINGER_TAP_MS = 400L
        // Wheel "buttons" 4 to 7, as bits of the pointer mask.
        private const val WHEEL_UP = 3
        private const val WHEEL_DOWN = 4
        private const val WHEEL_LEFT = 5
        private const val WHEEL_RIGHT = 6
    }
}
