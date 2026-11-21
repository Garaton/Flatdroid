package com.garathon.flatdroid.audio

import android.content.Context
import com.garathon.flatdroid.session.Sensor
import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.session.UseAction
import com.garathon.flatdroid.session.UseQuestion
import java.io.File

/**
 * The microphone for one session whose microphone setting is on: Android's microphone goes into
 * PulseAudio only while the app records ([MicStreamer] tells) and the user allowed it
 * ([UseQuestion]: asked the first time the app records, unless [alwaysAllowed]); silence otherwise.
 * [initialAnswer] is the answer when the user was asked before the launch (notifications off).
 */
class MicrophoneSession(
    context: Context,
    pipe: File,
    rate: Int,
    alwaysAllowed: Boolean,
    initialAnswer: Boolean? = null,
) {
    private val question: UseQuestion = UseQuestion(Sensor.MICROPHONE, alwaysAllowed, initialAnswer) { action ->
        if (action != UseAction.USE) streamer.stop()
        else if (!streamer.start()) SessionController.log("[flatdroid] Android's microphone permission is missing; the app records silence")
    }
    // Last: its thread starts at once and may call back.
    private val streamer: MicStreamer = MicStreamer(context.applicationContext, pipe, rate, question::inUse)

    fun stop() {
        question.stop()
        // Outside the question's lock: the streamer's thread may be waiting for it.
        streamer.close()
    }
}
