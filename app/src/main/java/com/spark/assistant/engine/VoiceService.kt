package com.spark.assistant.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Push-to-talk assistant listener (no always-on mic).
 *
 * Loop (all on-device, no network):
 *  1. Parked with the mic RELEASED until a press arms a window
 *     (assistant button via SparkAssistantSession, notification action,
 *     or the in-app mic button).
 *  2. On press: open mic, record 16kHz mono until ~1.2s silence
 *     (after speech starts), a 10s cap, or the window timeout.
 *  3. Whistle transcribes; no wake phrase required inside a window
 *     (a habitual "hey spark ..." prefix is stripped).
 *  4. needle_complete(audio) -> tool calls; EXECUTE>=0.50, CONFIRM shows
 *     a notification, REFUSE -> fallback regex parser (mapping-aware).
 *  5. Dispatch via ActionDispatcher (plain intents to installed apps).
 *  6. Duck music (AudioFocus) only while intentionally listening.
 *
 * Permissions: RECORD_AUDIO + FOREGROUND_SERVICE_MICROPHONE.
 */
class VoiceService : Service() {

    companion object {
        private const val TAG = "SparkSvc"
        const val CHANNEL_ID = "spark_voice"
        const val NOTIF_ID = 2001
        const val ACTION_START = "com.spark.assistant.LISTEN"
        const val ACTION_STOP = "com.spark.assistant.STOP"

        /**
         * One-shot listen (assistant button, notification action, mic
         * button). Opens a wake-free window; the service stops itself
         * afterwards.
         */
        const val ACTION_LISTEN_ONCE = "com.spark.assistant.LISTEN_ONCE"

        /** How long a LISTEN_ONCE window stays open for the next utterance. */
        const val LISTEN_ONCE_WINDOW_MS = 20_000L
    }

    private var running = false
    private var worker: Thread? = null
    private var audioFocus: AudioFocusRequest? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground FIRST: any startForegroundService() caller (activity,
        // assistant trampoline, notification action) gives the system only
        // seconds to see startForeground(), or the app is killed with
        // ForegroundServiceDidNotStartInTimeException. Readiness is checked
        // after, and reported via the notification.
        startForeground(NOTIF_ID, notification("Spark voice"))
        when (intent?.action) {
            ACTION_STOP -> {
                stopVoice()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LISTEN_ONCE, ACTION_START -> {
                if (!ModelManager.modelsReady(this)) {
                    Log.d(TAG, "listen refused: models missing")
                    updateForeground("Voice models not ready — open Spark once to download")
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Cold-process safe: nativeAvailable is in-memory only, so a
                // fresh process (assistant button, reboot) must (re)load the
                // engine here instead of trusting the flag.
                if (!NeedleBridge.nativeAvailable) {
                    Log.d(TAG, "engine not loaded, attempting load")
                    try {
                        val tools = applicationContext.assets.open("spark_tools.json")
                            .bufferedReader().readText()
                        ModelManager.ensureLoaded(applicationContext, tools)
                    } catch (e: Exception) {
                        Log.w(TAG, "engine load failed", e)
                    }
                }
                if (!NeedleBridge.nativeAvailable) {
                    Log.d(TAG, "listen refused: engine not loaded")
                    updateForeground("Voice engine unavailable on this device/ABI")
                    stopSelf()
                    return START_NOT_STICKY
                }
                armWindow()
                updateForeground("Listening… speak now 🎤")
                if (!running) startVoice()
                return START_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    private fun startVoice() {
        if (!NeedleBridge.nativeAvailable) {
            notify("Voice engine unavailable on this device/ABI")
            stopSelf()
            return
        }
        running = true
        worker = Thread(::loop, "spark-voice").apply { isDaemon = true; start() }
    }

    private fun stopVoice() {
        running = false
        listenArmed = false
        synchronized(lock) { lock.notifyAll() }
        worker?.interrupt()
        worker = null
        abandonDuck()
    }

    override fun onDestroy() {
        stopVoice()
        scope.cancel()
        super.onDestroy()
    }

    // -- main loop ----------------------------------------------------------

    private val lock = java.lang.Object()

    @Volatile
    private var listenArmed = false
    private var armTime = 0L

    /** Arms one button-triggered window; wakes the parked loop thread. */
    private fun armWindow() {
        synchronized(lock) {
            listenArmed = true
            armTime = SystemClock.elapsedRealtime()
            lock.notifyAll()
        }
    }

    private fun loop() {
        while (running && !Thread.interrupted()) {
            // Park with the mic released until a press arms a window.
            try {
                synchronized(lock) {
                    while (running && !listenArmed) lock.wait()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (!running || Thread.interrupted()) return
            listenArmed = false
            captureOne()
            // One-shot use: stop after the window.
            stopVoice()
            stopSelf()
            return
        }
    }

    /**
     * One bounded capture: mic opens on press, closes on ~1.2s of
     * silence (after speech starts), a 10s audio cap, or the window timeout.
     * Gives up after ~5s of pure silence (pressed by accident / too slow).
     */
    private fun captureOne() {
        val sampleRate = WakeWordDetector.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate * 2)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: SecurityException) {
            notify("Microphone permission missing")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) return
        record.startRecording()

        val frame = ShortArray(WakeWordDetector.FRAME_SAMPLES)
        val window = ArrayDeque<Short>()
        var silenceStreak = 0
        var speechSeen = false

        try {
            duck() // duck as soon as we intentionally start listening
            while (running && !Thread.interrupted()) {
                val elapsed = SystemClock.elapsedRealtime() - armTime
                if (elapsed > LISTEN_ONCE_WINDOW_MS) break
                if (!speechSeen && elapsed > 5_000) break // pressed, never spoke
                if (window.size >= sampleRate * 10) break // 10s audio cap
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) continue
                val speech = WakeWordDetector.isSpeech(frame)
                for (i in 0 until n) {
                    if (window.size >= sampleRate * 10) window.removeFirst()
                    window.addLast(frame[i])
                }
                if (speech) {
                    speechSeen = true
                    silenceStreak = 0
                } else {
                    silenceStreak++
                }
                // End of utterance: ~1.2s of silence, but only after speech started.
                if (speechSeen && silenceStreak >= WakeWordDetector.silenceFramesToClose) break
            }
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            abandonDuck()
        }
        val pcm = window.toShortArray()
        // Short commands ("pause") run well under a second; only drop blips.
        if (pcm.size > sampleRate / 3 && running) handleUtterance(pcm)
        else if (running) notify("Didn't catch that — tap 🎤 and try again")
    }

    private fun handleUtterance(pcm: ShortArray) {
        val samples = WakeWordDetector.toFloatMono(pcm)
        val dispatcher = ActionDispatcher(applicationContext)
        scope.launch {
            val mappings = try {
                ActionMappingStore.current(applicationContext)
            } catch (_: Exception) { emptyList() }

            // Button-triggered window: no wake gate at all. stripWake is still
            // applied so a habitual "hey spark ..." prefix never leaks into commands.
            // 1) Engine path: audio -> tool calls in one call.
            // Speech locked to the user's language (default English) instead
            // of auto-detect; user mapping keywords bias the Whistle decode
            // toward the app names we actually care about.
            val lang = EngineSettings.currentLanguage(applicationContext).ifEmpty { null }
            val keywords = mappings
                .flatMap { listOf(it.keyword, it.label) }
                .map { it.trim() }
                .filter { it.length >= 2 }
                .distinct()
                .take(30)
                .joinToString("\n")
                .ifEmpty { null }
            Log.d(TAG, "listen: lang=${lang ?: "auto"} keywords=${keywords?.lines()?.size ?: 0}")
            val result = try {
                NeedleBridge.completeAudio(samples, lang, keywords)
            } catch (_: Exception) { null }

            if (result != null) {
                val command = VoiceTools.stripWake(result.audioText)
                if (command.isBlank()) return@launch // nothing said
                val (decision, calls) = VoiceTools.route(result)
                when (decision) {
                    VoiceDecision.EXECUTE -> {
                        dispatcher.dispatch(calls, mappings)
                        notify("✔ ${calls.firstOrNull()?.name}")
                    }
                    VoiceDecision.CONFIRM -> {
                        // Agreement upgrade: deterministic fallback agrees -> just do it.
                        val fb = CommandParser.parse(command, mappings)
                        val agreed = fb != null && calls.any { it.name == fb.name && it.arguments == fb.arguments }
                        if (agreed) {
                            dispatcher.dispatch(calls, mappings)
                            notify("✔ ${calls.firstOrNull()?.name} (confirmed)")
                        } else {
                            notify("Heard '${result.audioText}' — tap Spark to confirm")
                            onConfirmRequest?.invoke(result)
                        }
                    }
                    VoiceDecision.REFUSE -> fallback(result.audioText, dispatcher, mappings)
                }
                return@launch
            }
            fallback("", dispatcher, mappings)
        }
    }

    private fun fallback(text: String, dispatcher: ActionDispatcher, mappings: List<ActionMapping>) {
        val call = CommandParser.parse(text, mappings) ?: run {
            val shown = text.ifBlank { "that" }
            notify("Heard '$shown' — no matching command")
            return
        }
        dispatcher.dispatch(listOf(call), mappings)
        notify("✔ ${call.name} (offline)")
    }

    /** Host UI (confirm dialog) hooks here. */
    var onConfirmRequest: ((VoiceResult) -> Unit)? = null

    // -- audio focus / notification ------------------------------------------

    private fun duck() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).build()
            audioFocus = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    private fun abandonDuck() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        audioFocus?.let {
            if (Build.VERSION.SDK_INT >= 26) am.abandonAudioFocusRequest(it)
            audioFocus = null
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Spark voice", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(text: String): Notification {
        val listen = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceService::class.java).setAction(ACTION_LISTEN_ONCE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Spark")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_btn_speak_now, "Listen", listen)
            .build()
    }

    private fun notify(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID + 1, notification(text))
    }

    /** Update the foreground notification in place (service is already foregrounded). */
    private fun updateForeground(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, notification(text))
    }
}
