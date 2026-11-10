package com.garathon.flatdroid.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.garathon.flatdroid.MainActivity
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.session.SessionService
import com.garathon.flatdroid.session.SessionState
import com.garathon.flatdroid.ui.followHideScreen
import com.garathon.flatdroid.ui.theme.FlatdroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The running session's screen, full screen under a slim bar (back to the app list, the soft
 * keyboard, close the focused window, stop the app), with an extra keys row above the keyboard.
 * Android's Back goes to the app, as its own back. Leaving (the bar's back, Home) keeps the session
 * and its connection; it closes itself when the session ends. Rotation doesn't recreate it (manifest).
 */
@OptIn(ExperimentalLayoutApi::class)
class ViewerActivity : ComponentActivity() {
    private lateinit var view: ViewerView

    /**
     * The viewer over the surface its frames are shown on, without being drawn by the app (Android
     * 10 and later, see [NativeFrames]); the surface lies under the window, which leaves a hole for it.
     */
    private fun frameHolder(): View {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return view
        val surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            // Nothing drawn into the surface itself: where no frame covers it, Android's own black
            // background for it shows, a plain colour the compositor fills without another buffer.
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = view.surfaceChanged(surface)

            override fun surfaceDestroyed(holder: SurfaceHolder) = view.surfaceChanged(null)
        })
        return FrameLayout(this).apply {
            addView(surface)
            addView(view)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = SessionController.ui.value.state as? SessionState.Running
        val socket = session?.socket
        if (socket == null) {
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        followHideScreen()
        view = ViewerView(this, ViewerConnection).apply {
            followPercent = session.followPercent
            // Wayland has no server option for it: the app's copies stop here.
            if (session.shareClipboard) onClipboard = ::copiedInApp
            onClipboardRequested = ::androidClipboardText
            // A keyboard pasting what is on Android's clipboard: the app pastes it itself, at once.
            onLongText = { text ->
                if (session.shareClipboard && androidClipboardText() == text) keys.chord(Keysyms.PASTE)
                else Toast.makeText(this@ViewerActivity, R.string.viewer_text_too_long, Toast.LENGTH_LONG).show()
            }
            onThreeFingerTap = {
                val shown = ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                showKeyboard(!shown)
            }
            onClosed = { error ->
                if (error != null) Toast.makeText(this@ViewerActivity, getString(R.string.viewer_disconnected, error), Toast.LENGTH_LONG).show()
                finish()
            }
        }
        setContent {
            FlatdroidTheme {
                val keyboardShown = WindowInsets.isImeVisible
                // The keyboard's height changes every frame while it moves: read only while laying
                // out (never composing), so the row and the frame keep up with it.
                val ime = WindowInsets.ime
                val rowHeight = with(LocalDensity.current) { (EXTRA_KEY_HEIGHT * EXTRA_KEYS.size).roundToPx() }
                // A long press on the keyboard button shows or hides the extra keys: on their own
                // without the keyboard, or above it while it is out (each remembered apart). The row
                // slides; read while laying out, like the keyboard.
                var keysAlone by rememberSaveable { mutableStateOf(false) }
                var keysWithKeyboard by rememberSaveable { mutableStateOf(true) }
                val keysAloneOffset = animateIntAsState(if (keysAlone) 0 else rowHeight, label = "extra keys")
                val keysWithKeyboardOffset = animateIntAsState(if (keysWithKeyboard) 0 else rowHeight, label = "extra keys with keyboard")
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        TopBar(
                            keyboardShown = keyboardShown,
                            onToggleKeys = { if (keyboardShown) keysWithKeyboard = !keysWithKeyboard else keysAlone = !keysAlone },
                        )
                        // The keyboard and the extra keys lie over the screen, which stays as it was.
                        AndroidView(factory = { frameHolder() }, modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds())
                    }
                    // Always there: above the keyboard while it is shown, sliding below the screen as it closes.
                    ExtraKeys(
                        Modifier.align(Alignment.BottomCenter).imePadding()
                            .offset {
                                val keyboard = ime.getBottom(this)
                                // With the keyboard: above it (sliding with it), unless hidden there, then behind it.
                                val withKeyboard = minOf((rowHeight - keyboard).coerceAtLeast(0), keysAloneOffset.value)
                                IntOffset(0, if (keyboard > 0) maxOf(withKeyboard, keysWithKeyboardOffset.value) else keysAloneOffset.value)
                            }
                            // The row's top is the top of all that covers the screen (the keyboard is below
                            // it, or above it when the row is hidden behind it): not asked for or copied.
                            .onGloballyPositioned { row ->
                                view.getLocationInWindow(viewLocation)
                                view.setCovered(viewLocation[1] + view.height - row.positionInWindow().y.roundToInt())
                            },
                    )
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = view.keys.chord(Keysyms.BACK)
        })
        view.requestFocus()
        ViewerConnection.attach(view)
        ViewerConnection.open(socket, cacheDir, session.waylandSocket, session.shareClipboard, session.outputRefresh)
        appLabel = session.appId
        lifecycleScope.launch(Dispatchers.IO) {
            InstalledApps(applicationContext).name(session.appId)?.let { name -> withContext(Dispatchers.Main) { appLabel = name } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                SessionController.ui.collect { ui ->
                    if ((ui.state as? SessionState.Running)?.socket != socket) finish()
                }
            }
        }
    }

    @Composable
    private fun TopBar(keyboardShown: Boolean, onToggleKeys: () -> Unit) {
        var confirmStop by rememberSaveable { mutableStateOf(false) }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                    .height(ViewerSize.BAR_HEIGHT_DP.dp),
            ) {
                IconButton(onClick = ::backToList, modifier = NO_KEY_FOCUS) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.viewer_back))
                }
                Text(
                    appLabel,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Tap: the Android keyboard (with the extra keys above it); hold: the extra keys alone.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = NO_KEY_FOCUS
                        .size(48.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClickLabel = stringResource(R.string.viewer_keyboard),
                            onLongClickLabel = stringResource(R.string.viewer_extra_keys),
                            onLongClick = onToggleKeys,
                            onClick = { showKeyboard(!keyboardShown) },
                        ),
                ) {
                    Icon(Icons.Filled.Keyboard, contentDescription = stringResource(R.string.viewer_keyboard))
                }
                IconButton(onClick = ::closeWindow, modifier = NO_KEY_FOCUS) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.viewer_close_window))
                }
                IconButton(onClick = { confirmStop = true }, modifier = NO_KEY_FOCUS) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = stringResource(R.string.session_stop))
                }
            }
        }
        if (confirmStop) {
            AlertDialog(
                onDismissRequest = { confirmStop = false },
                title = { Text(stringResource(R.string.viewer_stop_title, appLabel)) },
                text = { Text(stringResource(R.string.viewer_stop_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmStop = false
                        SessionService.stop(this)
                    }) { Text(stringResource(R.string.session_stop)) }
                },
                dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
    }

    /** The app's ID until its name has been read. */
    private var appLabel by mutableStateOf("")

    private val viewLocation = IntArray(2)

    /**
     * Keys a phone keyboard lacks, in two rows above it while it is shown, the arrows where a
     * keyboard has them. Shift, Ctrl and Alt hold for the next key (highlighted until then); the
     * others are held as long as they are touched, so X repeats them.
     */
    @Composable
    private fun ExtraKeys(modifier: Modifier) {
        var latches by remember { mutableIntStateOf(0) }
        DisposableEffect(Unit) {
            view.keys.onLatchChanged = { latches++ }
            onDispose { view.keys.onLatchChanged = null }
        }
        // Read here and passed down, so a latch set or released (also by a typed key) redraws the keys.
        val latched = latches.let { EXTRA_KEYS.flatten().map { it.second }.filter { Keysyms.isModifier(it) && view.keys.isLatched(it) }.toSet() }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
            Column {
                for (row in EXTRA_KEYS) {
                    Row(Modifier.fillMaxWidth().height(EXTRA_KEY_HEIGHT)) {
                        for ((label, keysym) in row) {
                            ExtraKey(label, keysym, latched = keysym in latched, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    /**
     * A key: pressed when the finger lifts (a modifier then latched for the next key), or once held
     * [HOLD_MS] (then repeating while held; a modifier held for the keys pressed meanwhile). A touch
     * that leaves the key or is taken by Android first (a swipe up from the screen's edge to show
     * its bars or go home starts on the keys) presses nothing. Every key works the same, wherever it is.
     */
    @Composable
    private fun ExtraKey(label: String, keysym: Int, latched: Boolean, modifier: Modifier) {
        val latchable = Keysyms.isModifier(keysym)
        var pressed by remember { mutableStateOf(false) }
        val colors = MaterialTheme.colorScheme
        // Latched (Shift, Ctrl, Alt waiting for the next key) or held under the finger.
        val background = when {
            latched -> colors.primaryContainer
            pressed -> colors.surfaceContainerHighest
            else -> Color.Transparent
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .fillMaxHeight()
                .padding(2.dp)
                .background(background, RoundedCornerShape(4.dp))
                .pointerInput(keysym) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            // Wait for what this is: a tap, a hold, or a touch Android takes (its swipe).
                            val lifted = withTimeoutOrNull(HOLD_MS) { tryAwaitRelease() }
                            if (lifted == false) {
                                // Android took it, or the finger left the key: nothing pressed.
                                pressed = false
                                return@detectTapGestures
                            }
                            if (lifted == true) {
                                // A tap: pressed now, all at once.
                                if (latchable) {
                                    view.keys.hold(keysym)
                                    view.keys.release(keysym, true)
                                } else {
                                    view.keys.tap(keysym)
                                }
                                pressed = false
                                return@detectTapGestures
                            }
                            // Held still: a press like any other from here.
                            if (latchable) {
                                // Held: down for the keys pressed meanwhile; tapped: latched for the next key.
                                view.keys.hold(keysym)
                                var tapped = false
                                try {
                                    tapped = tryAwaitRelease()
                                } finally {
                                    view.keys.release(keysym, tapped)
                                }
                            } else {
                                view.keys.down(keysym)
                                try {
                                    // Xvnc leaves key repeat to the viewer: repeated presses while held, at Android's rates.
                                    coroutineScope {
                                        val repeat = launch {
                                            delay(ViewConfiguration.getKeyRepeatTimeout().toLong())
                                            while (true) {
                                                view.keys.down(keysym)
                                                delay(ViewConfiguration.getKeyRepeatDelay().toLong())
                                            }
                                        }
                                        tryAwaitRelease()
                                        repeat.cancel()
                                    }
                                } finally {
                                    view.keys.up(keysym)
                                }
                            }
                            pressed = false
                        },
                    )
                },
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (latched) colors.onPrimaryContainer else colors.primary)
        }
    }

    /** Shows or hides the soft keyboard, typing into the viewer. */
    private fun showKeyboard(show: Boolean) {
        view.requestFocus()
        val controller = WindowCompat.getInsetsController(window, view)
        if (show) controller.show(WindowInsetsCompat.Type.ime()) else controller.hide(WindowInsetsCompat.Type.ime())
    }

    /** Flatdroid's app list; the session keeps running. */
    private fun backToList() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    /** Alt+F4, which Openbox's and labwc's configurations bind to closing the focused window. */
    private fun closeWindow() {
        ViewerConnection.key(true, KEY_ALT)
        ViewerConnection.key(true, KEY_F4)
        ViewerConnection.key(false, KEY_F4)
        ViewerConnection.key(false, KEY_ALT)
    }

    override fun onStart() {
        super.onStart()
        if (!::view.isInitialized) return
        ViewerConnection.shown(view, true)
        clipboard.addPrimaryClipChangedListener(clipChanged)
    }

    override fun onStop() {
        if (::view.isInitialized) {
            ViewerConnection.shown(view, false)
            clipboard.removePrimaryClipChangedListener(clipChanged)
        }
        super.onStop()
    }

    // ---- Clipboard: the app's copies go to Android's; Android's text goes to the app only when it pastes.

    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val clipChanged = ClipboardManager.OnPrimaryClipChangedListener { offerClipboard() }
    /** When the clip the app copied was put on Android's clipboard: not offered back to it. */
    private var ownClip = NO_CLIP
    /** When the clip last offered to the app was made, so it is offered once. */
    private var offeredClip = NO_CLIP

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android tells only the focused app about its clipboard (and only that it holds text, without reading it).
        if (hasFocus && ::view.isInitialized) offerClipboard()
    }

    /** Tells the app there is new text on Android's clipboard, without reading it. */
    private fun offerClipboard() {
        val description = clipboard.primaryClipDescription ?: return
        if (!description.hasMimeType("text/*")) return
        val made = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) description.timestamp else NO_CLIP
        if (made != NO_CLIP && (made == ownClip || made == offeredClip)) return
        offeredClip = made
        ViewerConnection.announceClipboard()
    }

    /** The app pastes: Android's clipboard is read now, and only now. */
    private fun androidClipboardText(): String? =
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()

    private fun copiedInApp(text: String) {
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(appLabel, text))
        } catch (_: RuntimeException) {
            // Android takes about 1 MB at most through its clipboard.
            Toast.makeText(this, R.string.viewer_copy_too_large, Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ownClip = clipboard.primaryClipDescription?.timestamp ?: NO_CLIP
    }

    override fun onDestroy() {
        if (::view.isInitialized) ViewerConnection.detach(view)
        super.onDestroy()
    }

    private companion object {
        const val NO_CLIP = -1L

        const val KEY_ALT = Keysyms.ALT_L
        const val KEY_F4 = Keysyms.F1 + 3

        val EXTRA_KEY_HEIGHT = 40.dp

        /** How long an extra key waits, held, before it presses (Android takes a swipe well before). */
        const val HOLD_MS = 150L

        /** The bar's buttons never take key focus: every key goes to the app (Enter would press a focused button). */
        val NO_KEY_FOCUS = Modifier.focusProperties { canFocus = false }

        /** The extra keys: two rows of key caps (not translated) and their keysyms. */
        val EXTRA_KEYS = listOf(
            listOf(
                "Esc" to Keysyms.ESCAPE, "Shift" to Keysyms.SHIFT_L, "Del" to Keysyms.DELETE,
                "Home" to Keysyms.HOME, "↑" to Keysyms.UP, "End" to Keysyms.END, "PgUp" to Keysyms.PAGE_UP,
            ),
            listOf(
                "Tab" to Keysyms.TAB, "Ctrl" to Keysyms.CONTROL_L, "Alt" to Keysyms.ALT_L,
                "←" to Keysyms.LEFT, "↓" to Keysyms.DOWN, "→" to Keysyms.RIGHT, "PgDn" to Keysyms.PAGE_DOWN,
            ),
        )
    }
}
