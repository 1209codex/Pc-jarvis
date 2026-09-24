package com.jarvis.reminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * File-backed persistence for scheduled reminders and routine jobs.
 * Encode/decode are pure so the JSON round-trip is JVM-testable.
 */
class ReminderStore(private val context: Context) {

    private val file = File(context.filesDir, "reminders.json")

    fun load(): MutableList<ScheduledItem> {
        if (!file.exists()) return mutableListOf()
        return try {
            decode(file.readText())
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(items: List<ScheduledItem>) {
        file.writeText(encode(items))
    }

    fun add(item: ScheduledItem) {
        val items = load()
        items.removeAll { it.id == item.id }
        items.add(item)
        save(items)
    }

    fun remove(id: String) {
        val items = load()
        items.removeAll { it.id == id }
        save(items)
    }

    fun update(item: ScheduledItem) {
        val items = load()
        items.removeAll { it.id == item.id }
        items.add(item)
        save(items)
    }

    companion object {
        fun encode(items: List<ScheduledItem>): String {
            val arr = JSONArray()
            for (item in items) {
                arr.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("kind", item.kind.name)
                        .put("fire_at", item.fireAtMillis)
                        .put("hour", item.hour)
                        .put("minute", item.minute)
                        .put("days", JSONArray(item.daysOfWeek))
                        .put("label", item.label)
                        .put("routine_id", item.routineId)
                )
            }
            return arr.toString()
        }

        fun decode(json: String): MutableList<ScheduledItem> {
            val arr = JSONArray(json)
            val out = mutableListOf<ScheduledItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val days = mutableListOf<Int>()
                val daysArr = o.optJSONArray("days")
                if (daysArr != null) {
                    for (j in 0 until daysArr.length()) days.add(daysArr.getInt(j))
                }
                out.add(
                    ScheduledItem(
                        id = o.getString("id"),
                        kind = runCatching { ScheduledKind.valueOf(o.getString("kind")) }.getOrDefault(ScheduledKind.REMINDER),
                        fireAtMillis = o.optLong("fire_at", 0L),
                        hour = o.optInt("hour", -1),
                        minute = o.optInt("minute", -1),
                        daysOfWeek = days,
                        label = o.optString("label", ""),
                        routineId = o.optString("routine_id", "")
                    )
                )
            }
            return out
        }
    }
}