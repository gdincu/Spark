package com.spark.assistant.engine

/**
 * Offline fallback parser: fast regex path used when Needle confidence is
 * below floor or the model is unavailable. Must stay in sync with
 * python/pipeline.py::parse_fallback (same commands, same order).
 *
 * User [mappings] are checked FIRST so a mapped keyword always wins over
 * the generic patterns (e.g. "tune" -> the user's music app).
 */
object CommandParser {

    private val volumeTo = Regex("""\bvolume(?: to| at)?\s*(?<level>\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val playQuery = Regex("""\b(?:play|put on|listen to)\b\s+(?<query>.+)$""", RegexOption.IGNORE_CASE)
    private val openApp = Regex("""\b(?:open|launch|start)\b\s+(?<app>.+)$""", RegexOption.IGNORE_CASE)
    // Greedy prefix backtracks to the LAST marker: titles may contain
    // 'in'/'on' ("lo-fi in the air on tune" -> app "tune").
    private val appSplit = Regex("""^(?<query>.+)\s+\b(?:on|in|using|with)\b\s+(?<app>[\w][\w\s.-]*)$""", RegexOption.IGNORE_CASE)

    fun parse(text: String, mappings: List<ActionMapping> = emptyList()): ToolCall? {
        if (mappings.isNotEmpty()) {
            parseMapping(text, mappings)?.let { return it }
        }
        val t = VoiceTools.stripWake(text).trim()
        if (t.isEmpty()) return null
        val low = t.lowercase()

        if (low.contains("pause") || low.contains("stop the music") || low == "stop") {
            return ToolCall("pause_playback")
        }
        if (Regex("""\b(resume|continue|unpause)\b""").containsMatchIn(t)) {
            return ToolCall("resume_playback")
        }
        if (Regex("""\b(next( song| track)?|skip( this)?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
            return ToolCall("next_track")
        }
        if (Regex("""\b(previous( song| track)?|go back|last song)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
            return ToolCall("previous_track")
        }
        volumeTo.find(t)?.let {
            val level = it.groups["level"]!!.value.toInt().coerceIn(0, 100)
            return ToolCall("set_volume", mapOf("level" to level))
        }
        if (low.contains("louder")) return ToolCall("set_volume", mapOf("level" to 70))
        if (low.contains("quieter")) return ToolCall("set_volume", mapOf("level" to 30))
        playQuery.find(t)?.let {
            var q = it.groups["query"]!!.value.trim().trimEnd('.', '!', '?')
            var app: String? = null
            appSplit.find(q)?.let { s ->
                app = s.groups["app"]!!.value.trim()
                q = s.groups["query"]!!.value.trim()
            }
            if (q.isNotEmpty()) {
                return if (app != null) ToolCall("play_media", mapOf("query" to q, "app" to app!!))
                else ToolCall("play_media", mapOf("query" to q))
            }
        }
        openApp.find(t)?.let {
            val a = it.groups["app"]!!.value.trim().trimEnd('.', '!', '?')
            if (a.isNotEmpty() && !a.equals("spark", ignoreCase = true)) {
                return ToolCall("open_app", mapOf("app" to a))
            }
        }
        return null
    }

    private fun parseMapping(text: String, mappings: List<ActionMapping>): ToolCall? {
        val t = VoiceTools.stripWake(text).trim()
        if (t.isEmpty()) return null
        for (m in mappings) {
            val kw = m.keyword.trim()
            if (kw.isEmpty()) continue
            if (!t.contains(kw, ignoreCase = true)) continue
            if (m.queryTemplate != null && m.queryTemplate.contains("{query}")) {
                var rest = Regex(Regex.escape(kw), RegexOption.IGNORE_CASE).replace(t, " ").trim()
                rest = Regex("""^(play|put on|listen to|open|launch|start)\b\s*""", RegexOption.IGNORE_CASE)
                    .replaceFirst(rest, "").trim()
                    .replace(Regex("""\s+\b(on|in|using|with)\s*$"""), "")
                    .trim().trimEnd('.', ',', '!', '?')
                appSplit.find(rest)?.let { s ->
                    rest = s.groups["query"]!!.value.trim()
                }
                if (rest.isNotEmpty()) {
                    return ToolCall("play_media", mapOf("query" to rest, "app" to m.keyword))
                }
                return ToolCall("open_app", mapOf("app" to m.keyword))
            }
            return ToolCall("open_app", mapOf("app" to m.keyword))
        }
        return null
    }
}
