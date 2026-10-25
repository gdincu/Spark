package com.spark.assistant.engine

import org.json.JSONObject

/**
 * JNI boundary for the Cactus C++ engine (libneedle.a + needle.h).
 *
 * The native lib loads whichever .cact it is given:
 *  - whistle.cact  -> NEEDLE_SPEECH
 *  - needle3.cact  -> NEEDLE_TEXT
 * Loading BOTH lets needle_complete() do audio->tool-calls inside the
 * engine, so the transcript never crosses JNI as anything but JSON.
 *
 * Models are downloaded on first use to app filesDir (see ModelManager)
 * and kept alive by the native layer for the process lifetime.
 */
object NeedleBridge {

    init {
        try {
            System.loadLibrary("spark_bridge")
        } catch (_: UnsatisfiedLinkError) {
            // Unit-test / stub-ABI environment without NDK libs.
        }
    }

    var nativeAvailable: Boolean = false
        private set

    /** Load whistle.cact + needle3.cact and register toolsJson. */
    external fun nativeLoad(whistlePath: String, needlePath: String, toolsJson: String): Boolean

    /**
     * Engine JSON for audio->tool-calls (function_calls, confidence, audio_*).
     * keywords is newline-separated (see needle_set_audio): user mapping
     * keywords bias the Whistle decode toward the names we care about.
     */
    external fun nativeCompleteAudio(samples: FloatArray, language: String?, keywords: String?): ByteArray?

    /** Engine JSON for transcription only ({text, language, ttft_ms, ...}). */
    external fun nativeTranscribe(samples: FloatArray, language: String?, keywords: String?): ByteArray?

    fun load(whistlePath: String, needlePath: String, toolsJson: String): Boolean {
        return try {
            nativeAvailable = nativeLoad(whistlePath, needlePath, toolsJson)
            nativeAvailable
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    fun completeAudio(samples: FloatArray, language: String?, keywords: String? = null): VoiceResult? {
        val bytes = try {
            nativeCompleteAudio(samples, language, keywords)
        } catch (_: UnsatisfiedLinkError) {
            return null
        } ?: return null
        return parseEngineJson(String(bytes, Charsets.UTF_8))
    }

    fun transcribe(samples: FloatArray, language: String?, keywords: String? = null): VoiceResult? {
        val bytes = try {
            nativeTranscribe(samples, language, keywords)
        } catch (_: UnsatisfiedLinkError) {
            return null
        } ?: return null
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        return VoiceResult(
            audioText = o.optString("text", ""),
            audioLanguage = o.optString("language", null),
        )
    }

    internal fun parseEngineJson(json: String): VoiceResult {
        val o = JSONObject(json)
        fun calls(key: String): List<ToolCall> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            return List(arr.length()) { i ->
                val c = arr.getJSONObject(i)
                val args = c.optJSONObject("arguments")
                val map = mutableMapOf<String, Any?>()
                args?.keys()?.forEach { k -> map[k] = args.get(k) }
                ToolCall(c.optString("name"), map)
            }
        }
        return VoiceResult(
            functionCalls = calls("function_calls"),
            suppressedCalls = calls("suppressed_calls"),
            confidence = o.optDouble("confidence", 0.0),
            audioText = o.optString("audio_text", ""),
            audioLanguage = o.optString("audio_language", null),
            reasoning = o.optString("reasoning", null),
        )
    }
}
