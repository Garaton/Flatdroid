package com.garathon.flatdroid.session

import com.garathon.flatdroid.util.appScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What Flatdroid asks about at the moment an app uses it. */
enum class Sensor { MICROPHONE, CAMERA, SCREEN }

/** What a [Sensor] does for a session right now. */
enum class UseAction {
    /** Android's microphone or camera goes to the app. */
    USE,
    /** The app uses it but the user hasn't answered yet this session: ask. */
    ASK,
    /** Silence or black frames, or nothing at all while the app doesn't use it. */
    IDLE;

    companion object {
        /**
         * The app is [inUse] of the sensor; it is [alwaysAllowed] it, or the user's [answer] for
         * this session is allowed, denied or not given yet (null).
         */
        fun of(alwaysAllowed: Boolean, answer: Boolean?, inUse: Boolean): UseAction = when {
            !inUse -> IDLE
            alwaysAllowed || answer == true -> USE
            answer == null -> ASK
            else -> IDLE
        }
    }
}

/**
 * The question about one [sensor] for the running session, the same whichever path noticed the
 * use: unless [alwaysAllowed], the user is asked through [SessionController] (a notification) the
 * first time the app uses it; the answer holds for the rest of the session and isn't saved. The
 * question waits [ASK_DELAY_MS]: apps open and close a stream while starting, which isn't a
 * request. [initialAnswer] is the answer when the user was asked before the launch
 * (notifications off). [onAction] hears each change, under this object's lock.
 */
class UseQuestion(
    private val sensor: Sensor,
    private val alwaysAllowed: Boolean,
    initialAnswer: Boolean?,
    private val onAction: (UseAction) -> Unit,
) {
    private var inUse = false
    private var answer: Boolean? = initialAnswer
    private var action: UseAction? = null
    private var pendingAsk: Job? = null
    /** [forget] came while the app used the sensor: the answer goes once it stops. */
    private var forgetWhenUnused = false
    private var stopped = false
    /** One instance, so stop() removes this question's handler and not a newer session's. */
    private val handler: (Boolean) -> Unit = ::answer

    init {
        SessionController.onAnswer(sensor, handler)
    }

    /** Whether the app uses the sensor now. */
    @Synchronized
    fun inUse(now: Boolean) {
        if (stopped) return
        inUse = now
        if (!now && forgetWhenUnused) {
            forgetWhenUnused = false
            answer = null
        }
        update()
    }

    /**
     * An Allow stops holding: the next use asks again, or, while the app still uses the sensor,
     * the one after it stops. A Deny still holds for the session.
     */
    @Synchronized
    fun forget() {
        if (stopped || answer != true) return
        if (inUse) {
            forgetWhenUnused = true
            return
        }
        answer = null
        update()
    }

    /** The user's answer for this session; a second answer is ignored. */
    @Synchronized
    fun answer(allow: Boolean) {
        if (stopped || answer != null) return
        answer = allow
        val until = if (allow && sensor == Sensor.SCREEN) "until the share ends" else "for this session"
        SessionController.log("[flatdroid] ${sensor.name.lowercase()} ${if (allow) "allowed" else "denied"} $until")
        update()
    }

    @Synchronized
    fun stop() {
        stopped = true
        pendingAsk?.cancel()
        SessionController.onAnswer(sensor, null, handler)
        SessionController.ask(sensor, false)
    }

    private fun update() {
        val now = UseAction.of(alwaysAllowed, answer, inUse)
        if (now != action) {
            action = now
            onAction(now)
        }
        if (now == UseAction.ASK) {
            if (pendingAsk == null) pendingAsk = appScope.launch {
                delay(ASK_DELAY_MS)
                askIfStillUsed()
            }
        } else {
            pendingAsk?.cancel()
            pendingAsk = null
            SessionController.ask(sensor, false)
        }
    }

    @Synchronized
    private fun askIfStillUsed() {
        pendingAsk = null
        if (!stopped && UseAction.of(alwaysAllowed, answer, inUse) == UseAction.ASK) SessionController.ask(sensor, true)
    }

    private companion object {
        const val ASK_DELAY_MS = 1000L
    }
}
