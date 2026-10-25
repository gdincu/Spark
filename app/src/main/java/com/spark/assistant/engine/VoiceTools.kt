package com.spark.assistant.engine

/** Single assistant tool call, mirrors tools.json + python/voice_tools.py. */
data class ToolCall(val name: String, val arguments: Map<String, Any?> = emptyMap())

/** Needle complete() response subset we route on. */
data class VoiceResult(
    val functionCalls: List<ToolCall> = emptyList(),
    val suppressedCalls: List<ToolCall> = emptyList(),
    val confidence: Double = 0.0,
    val audioText: String = "",
    val audioLanguage: String? = null,
    val reasoning: String? = null,
)

enum class VoiceDecision { EXECUTE, CONFIRM, REFUSE }

object VoiceTools {
    // Same calibration as HeyTune (Needle 3 base): act at 0.50, engine
    // floor 0.10 -> suppressed_calls. Fine-tuning on the tool set is the
    // proper fix (+18-36 pts per Cactus DroidCall result).
    const val EXECUTE_AT = 0.50
    const val CONFIRM_AT = 0.10 // engine floor: below -> suppressed_calls

    // The wake phrase is "hey spark" (two words, word-boundary matched:
    // bare "hey", "they", "whey" etc. must NOT trigger). Anything after it
    // is the command; "hey spark" alone is ignored before any Needle call.
    // Push-to-talk windows never REQUIRE the wake phrase; stripWake is still
    // applied so a habitual prefix never leaks into commands.
    private val WAKE_RE = Regex("""\bhey[\s,\-]+sparks?\b""", RegexOption.IGNORE_CASE)
    private val WAKE_LEAD_RE = Regex("""^\s*hey[\s,\-]+sparks?\b[\s,.!:-]*""", RegexOption.IGNORE_CASE)

    fun hasWake(text: String): Boolean = WAKE_RE.containsMatchIn(text.trim())

    fun stripWake(text: String): String = WAKE_LEAD_RE.replaceFirst(text.trim(), "")

    fun route(result: VoiceResult): Pair<VoiceDecision, List<ToolCall>> {
        if (result.functionCalls.isNotEmpty() && result.confidence >= EXECUTE_AT) {
            return VoiceDecision.EXECUTE to result.functionCalls
        }
        val held = result.functionCalls.ifEmpty { result.suppressedCalls }
        if (held.isNotEmpty()) return VoiceDecision.CONFIRM to held
        return VoiceDecision.REFUSE to emptyList()
    }
}
