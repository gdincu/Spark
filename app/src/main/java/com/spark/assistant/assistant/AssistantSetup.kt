package com.spark.assistant.assistant

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.os.Build

/** Helpers around the system ASSISTANT role (API 29+).
 *
 * Uses only public RoleManager APIs:
 * - isRoleAvailable to check support,
 * - isRoleHeld to check if Spark is the default assistant,
 * - createRequestRoleIntent to ask the user (via
 *   Activity-Result launcher in the settings UI).
 */
object AssistantSetup {
    fun isRoleAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
    }

    fun isDefaultAssistant(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return try {
            rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } catch (_: Exception) {
            false
        }
    }

    /** Public intent that prompts the user to make Spark the assistant. */
    fun createRequestIntent(context: Context): android.content.Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return null
        return rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
    }

    const val REQUEST_CODE = 1977

    /** Forward this from `onActivityResult` if you used the legacy launcher. */
    fun onActivityResult(requestCode: Int, activity: Activity, onDone: (granted: Boolean) -> Unit) {
        if (requestCode != REQUEST_CODE) return
        onDone(isDefaultAssistant(activity))
    }
}
