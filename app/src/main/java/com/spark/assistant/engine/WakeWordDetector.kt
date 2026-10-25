package com.spark.assistant.engine

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lightweight VAD gate for the push-to-talk listen window.
 *
 * Audio path: AudioRecord @16kHz mono -> 80ms frames (1280 samples) ->
 * VAD -> utterance buffer (ends on ~1.2s silence / 10s cap) ->
 * Whistle transcribe -> optional wake-phrase text check.
 *
 * Push-to-talk only: no always-on listening. The mic opens when the
 * assistant button (or the in-app mic button) arms a window and closes
 * when the utterance ends.
 */
object WakeWordDetector {

    const val SAMPLE_RATE = 16000
    const val FRAME_SAMPLES = 1280 // 80ms, matches Whistle's frame rate

    /** RMS threshold above which a frame counts as speech. Tune per device. */
    var vadThresholdRms: Float = 0.02f

    /** Silence frames that close the window (~1.2s). */
    var silenceFramesToClose: Int = 15

    fun rms(frame: ShortArray, offset: Int = 0, length: Int = frame.size): Float {
        var sum = 0.0
        for (i in offset until offset + length) {
            val s = frame[i] / 32768.0
            sum += s * s
        }
        return sqrt(sum / length).toFloat()
    }

    fun isSpeech(frame: ShortArray): Boolean = rms(frame) > vadThresholdRms

    fun isWake(transcript: String): Boolean = VoiceTools.hasWake(transcript)

    /** Peak-normalise int16 mono to [-1,1] floats for needle_transcribe. */
    fun toFloatMono(pcm: ShortArray): FloatArray =
        FloatArray(pcm.size) { abs(pcm[it] / 32768f).coerceAtMost(1f) * if (pcm[it] < 0) -1f else 1f }
}
