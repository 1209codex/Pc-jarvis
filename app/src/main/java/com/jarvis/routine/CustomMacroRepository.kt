package com.jarvis.routine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jarvis.macro.MacroActionType
import com.jarvis.macro.MacroStep
import com.jarvis.macro.UiMacro
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent repository for custom user-created voice routines and automation macros.
 * Stores routines in SharedPreferences using structured JSON.
 */
class CustomMacroRepository(context: Context) {

    private val TAG = "CustomMacroRepo"
    private val prefs: SharedPreferences =
        context.getSharedPreferences("jarvis_custom_macros", Context.MODE_PRIVATE)
    private val KEY_MACROS = "custom_macro_list_json"

    init {
        seedDefaultsIfEmpty()
    }

    @Synchronized
    fun getAll(): List<UiMacro> {
        val jsonStr = prefs.getString(KEY_MACROS, null) ?: return emptyList()
        val list = mutableListOf<UiMacro>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(deserializeMacro(obj))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stored macros", e)
        }
        return list
    }

    @Synchronized
    fun save(macro: UiMacro) {
        val current = getAll().toMutableList()
        current.removeAll { it.id == macro.id }
        current.add(macro)
        persist(current)
    }

    @Synchronized
    fun delete(macroId: String): Boolean {
        val current = getAll().toMutableList()
        val removed = current.removeAll { it.id == macroId }
        if (removed) {
            persist(current)
        }
        return removed
    }

    @Synchronized
    fun getById(id: String): UiMacro? {
        return getAll().firstOrNull { it.id == id }
    }

    private fun persist(macros: List<UiMacro>) {
        try {
            val array = JSONArray()
            for (m in macros) {
                array.put(serializeMacro(m))
            }
            prefs.edit().putString(KEY_MACROS, array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist macros", e)
        }
    }

    private fun serializeMacro(m: UiMacro): JSONObject {
        val obj = JSONObject()
        obj.put("id", m.id)
        obj.put("name", m.name)
        obj.put("description", m.description)
        obj.put("targetPackage", m.targetPackage ?: "")
        obj.put("createdAt", m.createdAt)
        obj.put("lastRunAt", m.lastRunAt)
        obj.put("runCount", m.runCount)

        val triggersArr = JSONArray()
        for (t in m.triggers) {
            triggersArr.put(t)
        }
        obj.put("triggers", triggersArr)

        val stepsArr = JSONArray()
        for (s in m.steps) {
            val stepObj = JSONObject()
            stepObj.put("stepIndex", s.stepIndex)
            stepObj.put("actionType", s.actionType.name)
            stepObj.put("target", s.target)
            stepObj.put("payload", s.payload)
            stepObj.put("timeoutMs", s.timeoutMs)
            stepObj.put("isOptional", s.isOptional)
            stepObj.put("description", s.description)
            stepsArr.put(stepObj)
        }
        obj.put("steps", stepsArr)
        return obj
    }

    private fun deserializeMacro(obj: JSONObject): UiMacro {
        val triggers = mutableListOf<String>()
        val triggersArr = obj.optJSONArray("triggers")
        if (triggersArr != null) {
            for (i in 0 until triggersArr.length()) {
                triggers.add(triggersArr.getString(i))
            }
        }

        val steps = mutableListOf<MacroStep>()
        val stepsArr = obj.optJSONArray("steps")
        if (stepsArr != null) {
            for (i in 0 until stepsArr.length()) {
                val sObj = stepsArr.getJSONObject(i)
                val actionTypeStr = sObj.optString("actionType", MacroActionType.DELAY.name)
                val actionType = try {
                    MacroActionType.valueOf(actionTypeStr)
                } catch (_: Exception) {
                    MacroActionType.DELAY
                }
                steps.add(
                    MacroStep(
                        stepIndex = sObj.optInt("stepIndex", i + 1),
                        actionType = actionType,
                        target = sObj.optString("target", ""),
                        payload = sObj.optString("payload", ""),
                        timeoutMs = sObj.optLong("timeoutMs", 3000L),
                        isOptional = sObj.optBoolean("isOptional", false),
                        description = sObj.optString("description", "")
                    )
                )
            }
        }

        return UiMacro(
            id = obj.getString("id"),
            name = obj.getString("name"),
            description = obj.optString("description", ""),
            targetPackage = obj.optString("targetPackage", "").takeIf { it.isNotBlank() },
            steps = steps,
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            lastRunAt = obj.optLong("lastRunAt", 0L),
            runCount = obj.optInt("runCount", 0),
            triggers = triggers
        )
    }

    private fun seedDefaultsIfEmpty() {
        if (!prefs.contains(KEY_MACROS)) {
            val morningRoutine = UiMacro(
                id = "routine_morning_brief",
                name = "Morning Briefing Protocol",
                description = "Navigates home, clears notifications, and opens daily dashboard.",
                steps = listOf(
                    MacroStep(1, MacroActionType.GLOBAL_ACTION, target = "home", description = "Return Home"),
                    MacroStep(2, MacroActionType.DELAY, payload = "400", description = "Stabilize UI"),
                    MacroStep(3, MacroActionType.GLOBAL_ACTION, target = "notifications", description = "View Day's Notifications")
                ),
                triggers = listOf("good morning", "morning protocol", "start morning routine", "subah ho gayi")
            )

            val focusRoutine = UiMacro(
                id = "routine_deep_focus",
                name = "Deep Focus Sequence",
                description = "Opens recents and clears all background apps for zero distractions.",
                steps = listOf(
                    MacroStep(1, MacroActionType.GLOBAL_ACTION, target = "recents", description = "Open Recents"),
                    MacroStep(2, MacroActionType.DELAY, payload = "500", description = "Wait for animation"),
                    MacroStep(3, MacroActionType.CLICK_TEXT, target = "Close all", isOptional = true, description = "Tap Close all"),
                    MacroStep(4, MacroActionType.CLICK_TEXT, target = "Clear all", isOptional = true, description = "Fallback Tap Clear all")
                ),
                triggers = listOf("focus mode", "deep focus", "dhyan lagao", "study mode")
            )

            val nightRoutine = UiMacro(
                id = "routine_night_standby",
                name = "Night Standby Protocol",
                description = "Returns to home screen and turns off screen display.",
                steps = listOf(
                    MacroStep(1, MacroActionType.GLOBAL_ACTION, target = "home", description = "Return Home"),
                    MacroStep(2, MacroActionType.DELAY, payload = "300", description = "Wait"),
                    MacroStep(3, MacroActionType.GLOBAL_ACTION, target = "lock_screen", isOptional = true, description = "Lock Screen")
                ),
                triggers = listOf("good night", "night mode", "shubh ratri", "sleep time")
            )

            persist(listOf(morningRoutine, focusRoutine, nightRoutine))
        }
    }
}
