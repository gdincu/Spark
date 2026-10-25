package com.spark.assistant.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.spark.assistant.engine.VoiceService

/** Session host, bound explicitly via assistant_interaction.xml. */
class SparkAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        SparkAssistantSession(this)
}

/**
 * Headless session: no assistant UI of its own. A press routes straight
 * into one Spark listen window (same path as the notification action).
 */
class SparkAssistantSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // AssistantListenActivity is translucent + finishes immediately, so
        // the user only ever sees the "Listening… speak now" notification.
        // (Launched via the session so background-activity-start rules allow it.)
        startAssistantActivity(Intent(context, AssistantListenActivity::class.java))
        hide()
    }
}
