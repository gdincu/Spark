package com.spark.assistant.assistant

import android.app.role.RoleManager
import android.content.Context
import android.os.Build

/** Read-only helpers around the system ASSISTANT role (API 29+).
 *
 * Spark is set as the default assistant manually by the user
 * (system Settings → Apps → Default apps → Digital assistant app);
 * these only report state for the Status card. Uses public
 * RoleManager APIs only.
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
}
