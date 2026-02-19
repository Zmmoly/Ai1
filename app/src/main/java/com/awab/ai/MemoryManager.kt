package com.awab.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class MemoryManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("awab_memory", Context.MODE_PRIVATE)

    // حفظ معلومة
    fun save(key: String, value: String) {
        val all = getAll()
        all.put(key.lowercase().trim(), value.trim())
        prefs.edit().putString("data", all.toString()).apply()
    }

    // استرجاع معلومة بالمفتاح
    fun get(key: String): String? {
        val all = getAll()
        val lowerKey = key.lowercase().trim()
        if (all.has(lowerKey)) return all.getString(lowerKey)

        // بحث جزئي
        val keys = all.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.contains(lowerKey) || lowerKey.contains(k)) {
                return all.getString(k)
            }
        }
        return null
    }

    // حذف معلومة
    fun delete(key: String): Boolean {
        val all = getAll()
        val lowerKey = key.lowercase().trim()
        return if (all.has(lowerKey)) {
            all.remove(lowerKey)
            prefs.edit().putString("data", all.toString()).apply()
            true
        } else false
    }

    // عرض كل المعلومات المحفوظة
    fun getAll(): JSONObject {
        val raw = prefs.getString("data", "{}") ?: "{}"
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
    }

    // مسح كل الذاكرة
    fun clearAll() {
        prefs.edit().remove("data").apply()
    }

    // عدد العناصر المحفوظة
    fun count(): Int = getAll().length()
}
