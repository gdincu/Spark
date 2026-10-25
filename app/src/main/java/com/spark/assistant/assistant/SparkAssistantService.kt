package com.spark.assistant.assistant

import android.service.voice.VoiceInteractionService

/**
 * Entry point that makes Spark selectable as the system digital
 * assistant (Settings → Apps → Default apps → Digital assistant app).
 * Only the default assistant receives push-to-talk (headset button,
 * long-press-home/gesture, steering-wheel button) invocations.
 *
 * The heavy lifting stays in the engine (models, Needle, VoiceService);
 * this service routes invocations there via a headless session.
 */
class SparkAssistantService : VoiceInteractionService()
