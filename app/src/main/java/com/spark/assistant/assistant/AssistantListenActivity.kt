package com.spark.assistant.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import com.spark.assistant.engine.ModelManager
import com.spark.assistant.engine.NeedleBridge
import com.spark.assistant.engine.VoiceService
import com.spark.assistant.ui.MainActivity

/**
 * Invisible trampoline between the assistant invocation and Spark voice.
 * Runs in the foreground when started, so handing off to the voice
 * foreground service is always allowed here (unlike from the session alone).
 *
 * - Models + mic ready → fires ACTION_LISTEN_ONCE and finishes.
 * - Otherwise → opens the app so the user completes setup once
 *   (downloads the ~45 MB models), then finishes.
 */
class AssistantListenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micOk && ModelManager.modelsReady(this) && NeedleBridge.nativeAvailable) {
            startForegroundService(
                Intent(this, VoiceService::class.java)
                    .setAction(VoiceService.ACTION_LISTEN_ONCE),
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Toast.makeText(
                this,
                "Open Spark once to set up voice",
                Toast.LENGTH_LONG,
            ).show()
        }
        finish()
    }
}
