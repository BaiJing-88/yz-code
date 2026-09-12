package com.yzcode.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object HistoryStore {
    private const val PREFS_NAME = "yzcode_history"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 50

    data class Entry(val code: String, val sender: String, val time: Long, val status: String = "上传成功")

    @Synchronized
    fun add(context: Context, entry: Entry) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val old = readArray(sp.getString(KEY_ITEMS, "[]")) ?: JSONArray()
        val next = JSONArray()
        val obj = JSONObject()
        obj.put("code", entry.code)
        obj.put("sender", entry.sender)
        obj.put("time", entry.time)
        obj.put("status", entry.status)
        next.put(obj)
        for (i in 0 until old.length()) {
            if (next.length() >= MAX_ITEMS) break
            next.put(old.get(i))
        }
        sp.edit().putString(KEY_ITEMS, next.toString()).commit()
    }

    @Synchronized
    fun list(context: Context): List<Entry> {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = readArray(sp.getString(KEY_ITEMS, "[]")) ?: return emptyList()
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Entry(o.optString("code", ""), o.optString("sender", ""), o.optLong("time", 0L), o.optString("status", "上传成功")))
        }
        return out
    }

    @Synchronized
    fun isUploaded(context: Context, code: String, time: Long): Boolean =
        list(context).any {
            it.code == code && it.status == "上传成功" &&
                (it.time == time || kotlin.math.abs(it.time - time) < 180_000L)
        }

    private fun readArray(raw: String?): JSONArray? = try {
        JSONArray(raw ?: "[]")
    } catch (e: Exception) {
        null
    }
}
