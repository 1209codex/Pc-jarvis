package com.jarvis.assistant

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Helper to inspect and configure Jarvis as the Android System Default Digital Assistant.
 */
object AssistantSettingsHelper {

    private const val TAG = "AssistantSettingsHelper"

    /**
     * Checks if Jarvis is currently configured as the system default voice interaction / digital assistant service.
     */
    fun isDefaultAssistant(context: Context): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                    if (roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                        return true
                    }
                }
            }

            val setting = Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
            if (setting != null) {
                val currentComponent = ComponentName.unflattenFromString(setting)
                if (currentComponent != null && currentComponent.packageName == context.packageName) {
                    return true
                }
            }

            val defaultAssist = Settings.Secure.getString(context.contentResolver, "assistant")
            if (defaultAssist != null) {
                val currentComponent = ComponentName.unflattenFromString(defaultAssist)
                if (currentComponent != null && currentComponent.packageName == context.packageName) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking default assistant status: ${e.message}")
        }
        return false
    }

    /**
     * Creates an [Intent] to open Android's Default Digital Assistant settings screen.
     */
    fun createDefaultAssistantSettingsIntent(context: Context): Intent {
        // Try RoleManager on Android 10+ (Q+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }

        // Fall back to Voice Input settings
        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Opens Android's Default Digital Assistant settings screen.
     */
    fun openDefaultAssistantSettings(context: Context): Boolean {
        return try {
            val intent = createDefaultAssistantSettingsIntent(context)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch voice input settings; falling back to manage default apps", e)
            try {
                val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(fallback)
                true
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Failed all default assistant settings intents: ${fallbackEx.message}")
                false
            }
        }
    }
}
