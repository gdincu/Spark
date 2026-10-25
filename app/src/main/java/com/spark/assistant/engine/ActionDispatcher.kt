package com.spark.assistant.engine

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent

/**
 * Dispatches voice tool calls as plain Android intents — no network.
 *
 *  - open_app: resolve the [app] string via user mappings first, then fuzzy
 *    match against launchable apps, then fire the launcher intent.
 *  - play_media: MEDIA_PLAY_FROM_SEARCH, pinned to the mapped package when
 *    the [app] keyword resolves to one, otherwise system-wide.
 *  - pause/resume/next/previous: media-button KeyEvents to the active player.
 *  - set_volume: AudioManager music-stream volume.
 */
class ActionDispatcher(private val context: Context) {

    data class InstalledApp(val label: String, val packageName: String)

    fun dispatch(calls: List<ToolCall>, mappings: List<ActionMapping>): List<Any?> =
        calls.map { dispatchOne(it, mappings) }

    fun dispatchOne(call: ToolCall, mappings: List<ActionMapping>): Any? = when (call.name) {
        "open_app" -> openApp(call.arguments["app"] as? String ?: "", mappings)
        "play_media" -> playMedia(
            call.arguments["query"] as? String ?: "",
            call.arguments["app"] as? String,
            mappings,
        )
        "pause_playback" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE).also { lastAction = "pause" }
        "resume_playback" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY).also { lastAction = "resume" }
        "next_track" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT).also { lastAction = "next" }
        "previous_track" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS).also { lastAction = "previous" }
        "set_volume" -> {
            val level = (call.arguments["level"] as? Number)?.toInt()?.coerceIn(0, 100) ?: 50
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max / 100f).toInt(), 0)
            lastAction = "volume=$level"
        }
        else -> "unknown tool: ${call.name}"
    }

    fun openApp(app: String, mappings: List<ActionMapping>): Any? {
        val target = resolvePackage(app, mappings)
        val pm = context.packageManager
        val pkg = target
            ?: fuzzyPackage(app, pm)
            ?: return "no app found for '$app'".also { lastAction = "open(unresolved)=$app" }
        val intent = pm.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return "cannot launch $pkg".also { lastAction = "open(failed)=$pkg" }
        context.startActivity(intent)
        lastAction = "open=$pkg"
        return true
    }

    fun playMedia(query: String, app: String?, mappings: List<ActionMapping>): Any? {
        if (query.isBlank()) return "empty query".also { lastAction = "play(empty)" }
        val pkg = app?.let { resolvePackage(it, mappings) ?: fuzzyPackage(it, context.packageManager) }
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SearchManager.QUERY, query)
            if (pkg != null) setPackage(pkg)
        }
        return try {
            context.startActivity(intent)
            lastAction = "play=$query${if (pkg != null) " on $pkg" else ""}"
            true
        } catch (_: Exception) {
            // No handler for the search intent: fall back to launching the app.
            if (pkg != null) {
                context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }?.let { context.startActivity(it); lastAction = "open=$pkg"; return true }
            }
            lastAction = "play(unhandled)=$query"
            "no player found for '$query'"
        }
    }

    /** Keyword -> package via user mappings (keyword or label match). */
    fun resolvePackage(app: String, mappings: List<ActionMapping>): String? {
        val a = app.trim()
        if (a.isEmpty()) return null
        mappings.firstOrNull {
            it.keyword.equals(a, ignoreCase = true) || it.label.equals(a, ignoreCase = true)
        }?.let { return it.packageName }
        // Direct package name passthrough.
        if (a.contains('.')) {
            try {
                context.packageManager.getPackageInfo(a, 0)
                return a
            } catch (_: Exception) { /* fall through to fuzzy */ }
        }
        return null
    }

    fun installedApps(): List<InstalledApp> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .mapNotNull {
                val pkg = it.activityInfo?.packageName ?: return@mapNotNull null
                InstalledApp(it.loadLabel(pm).toString(), pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun fuzzyPackage(app: String, pm: PackageManager): String? {
        val a = app.trim().lowercase()
        if (a.isEmpty()) return null
        val all = installedApps()
        all.firstOrNull { it.label.equals(app.trim(), ignoreCase = true) }?.let { return it.packageName }
        all.firstOrNull { it.label.lowercase().contains(a) }?.let { return it.packageName }
        all.firstOrNull { it.packageName.lowercase().contains(a.replace(" ", "")) }?.let { return it.packageName }
        return null
    }

    private fun mediaKey(code: Int): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.uptimeMillis()
        return try {
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            true
        } catch (_: Exception) {
            false
        }
    }

    var lastAction: String? = null
        private set
}
