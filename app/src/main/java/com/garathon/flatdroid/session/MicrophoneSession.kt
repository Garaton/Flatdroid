package com.garathon.flatdroid.session

import android.content.Context
import com.garathon.flatdroid.audio.MicStreamer
import java.io.File
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.schedule

/** What the microphone does for a session right now. */
enum class MicAction {
    /** Record from Android's microphone into PulseAudio. */
    STREAM,
    /** The app records but the user hasn't answered yet this session: ask. */
    ASK,
    IDLE;

    companion object {
        /**
         * The app is [recording] from its microphone; it is [alwaysAllowed] it, or the user's
         * [answer] for this session is allowed, denied or not given yet (null).
         */
        fun of(alwaysAllowed: Boolean, answer: Boolean?, recording: Boolean): MicAction = when {
            !recording -> IDLE
            alwaysAllowed || answer == true -> STREAM
            answer == null -> ASK
            else -> IDLE
        }
    }
}

/**
 * The microphone for one session of [appId], whose microphone setting is on: Android's microphone
 * goes into PulseAudio only while the app records ([MicStreamer] tells). Unless [alwaysAllowed], the user is
 * asked through [SessionController] (a notification) the first time the app records; the answer
 * holds for the rest of the session and isn't saved. The question waits [ASK_DELAY_MS]: apps
 * such as Audacity open and close a recording stream while starting, which isn't a request.
 * [initialAnswer] is the answer when the user was asked before the launch (notifications off).
 */
class MicrophoneSession(
    context: Context,
    private val appId: String,
    pipe: File,
    rate: Int,
    private val alwaysAllowed: Boolean,
    initialAnswer: Boolean? = null,
) {
    private var recording = false
    private var answer: Boolean? = initialAnswer
    private val timer = Timer("microphone-ask", true)
    private var pendingAsk: TimerTask? = null
    private var stopped = false
    // Last: its thread starts at once and may call back.
    private val streamer = MicStreamer(context.applicationContext, pipe, rate, ::onRecording)

    /** Whether the app records from the microphone (from [MicStreamer], on its thread). */
    @Synchronized
    private fun onRecording(now: Boolean) {
        if (stopped) return
        recording = now
        update()
    }

    /** The user's answer for this session; a second answer is ignored. */
    @Synchronized
    fun answer(allow: Boolean) {
        if (stopped || answer != null) return
        answer = allow
        SessionController.log("[flatdroid] microphone ${if (allow) "allowed" else "denied"} for this session")
        update()
    }

    fun stop() {
        synchronized(this) {
            stopped = true
            timer.cancel()
            SessionController.requestMicrophone(null)
        }
        // Outside the lock: the streamer's thread may be waiting for it in onRecording.
        streamer.close()
    }

    private fun update() {
        val action = MicAction.of(alwaysAllowed, answer, recording)
        if (action == MicAction.STREAM) {
            if (!streamer.start()) SessionController.log("[flatdroid] Android's microphone permission is missing; the app records silence")
        } else {
            streamer.stop()
        }
        if (action == MicAction.ASK) {
            if (pendingAsk == null) pendingAsk = timer.schedule(ASK_DELAY_MS) { askIfStillRecording() }
        } else {
            pendingAsk?.cancel()
            pendingAsk = null
            SessionController.requestMicrophone(null)
        }
    }

    @Synchronized
    private fun askIfStillRecording() {
        pendingAsk = null
        if (!stopped && MicAction.of(alwaysAllowed, answer, recording) == MicAction.ASK) SessionController.requestMicrophone(appId)
    }

    private companion object {
        const val ASK_DELAY_MS = 1000L
    }
}
