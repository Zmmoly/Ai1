package com.awab.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

// ===== النماذج =====

data class ShoppingItem(
    val name: String,
    val pricePerUnit: Double,
    val quantity: Double,
    val total: Double,
    val priceSource: String,
    val sessionId: String,         // ينتمي لأي جلسة
    val timestamp: Long = System.currentTimeMillis()
)

data class ShoppingSession(
    val id: String,                // معرف فريد
    val budget: Double,            // 0 = ميزانية عامة
    val startTime: Long,
    val endTime: Long?,            // null = جلسة مفتوحة
    val label: String              // "ميزانية عامة" أو "جلسة X"
)

data class ParsedPurchase(
    val itemName: String,
    val explicitPrice: Double?,
    val quantity: Double?,
    val isWeightBased: Boolean
)

object ShoppingManager {

    private const val PREFS_NAME       = "shopping_prefs"
    private const val KEY_ITEMS        = "shopping_items"
    private const val KEY_SESSIONS     = "shopping_sessions"
    private const val KEY_ACTIVE_SESSION = "active_session_id"
    private const val GENERAL_SESSION_ID = "general"

    // ===== الجلسات =====

    fun saveSessions(context: Context, sessions: List<ShoppingSession>) {
        val arr = JSONArray()
        for (s in sessions) {
            arr.put(JSONObject().apply {
                put("id",        s.id)
                put("budget",    s.budget)
                put("startTime", s.startTime)
                put("endTime",   s.endTime ?: -1L)
                put("label",     s.label)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SESSIONS, arr.toString()).apply()
    }

    fun loadSessions(context: Context): MutableList<ShoppingSession> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SESSIONS, "[]") ?: "[]"
        val result = mutableListOf<ShoppingSession>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(ShoppingSession(
                    id        = obj.getString("id"),
                    budget    = obj.getDouble("budget"),
                    startTime = obj.getLong("startTime"),
                    endTime   = obj.getLong("endTime").takeIf { it >= 0 },
                    label     = obj.getString("label")
                ))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return result
    }

    fun getActiveSessionId(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_SESSION, GENERAL_SESSION_ID) ?: GENERAL_SESSION_ID
    }

    fun setActiveSessionId(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE_SESSION, id).apply()
    }

    /**
     * يبدأ جلسة جديدة بميزانية محددة
     * يُرجع الجلسة الجديدة
     */
    fun startNewSession(context: Context, budget: Double): ShoppingSession {
        val sessions = loadSessions(context)

        // أغلق الجلسة المفتوحة السابقة إن وجدت
        val activeId = getActiveSessionId(context)
        val updated = sessions.map {
            if (it.id == activeId && it.endTime == null && it.id != GENERAL_SESSION_ID)
                it.copy(endTime = System.currentTimeMillis())
            else it
        }.toMutableList()

        // أنشئ جلسة جديدة
        val sessionNumber = updated.count { it.id != GENERAL_SESSION_ID } + 1
        val newSession = ShoppingSession(
            id        = "session_${System.currentTimeMillis()}",
            budget    = budget,
            startTime = System.currentTimeMillis(),
            endTime   = null,
            label     = "جلسة $sessionNumber"
        )

        // تأكد من وجود الجلسة العامة
        if (updated.none { it.id == GENERAL_SESSION_ID }) {
            updated.add(0, ShoppingSession(
                id        = GENERAL_SESSION_ID,
                budget    = 0.0,
                startTime = 0L,
                endTime   = null,
                label     = "ميزانية عامة"
            ))
        }

        updated.add(newSession)
        saveSessions(context, updated)
        setActiveSessionId(context, newSession.id)
        return newSession
    }

    /**
     * ينهي الجلسة الحالية ويرجع للميزانية العامة
     */
    fun endActiveSession(context: Context): ShoppingSession? {
        val sessions  = loadSessions(context)
        val activeId  = getActiveSessionId(context)
        if (activeId == GENERAL_SESSION_ID) return null

        val session = sessions.find { it.id == activeId } ?: return null
        val updated = sessions.map {
            if (it.id == activeId) it.copy(endTime = System.currentTimeMillis()) else it
        }
        saveSessions(context, updated)
        setActiveSessionId(context, GENERAL_SESSION_ID)
        return session
    }

    /** يتأكد من وجود الجلسة العامة */
    private fun ensureGeneralSession(context: Context) {
        val sessions = loadSessions(context)
        if (sessions.none { it.id == GENERAL_SESSION_ID }) {
            val updated = sessions.toMutableList()
            updated.add(0, ShoppingSession(
                id        = GENERAL_SESSION_ID,
                budget    = 0.0,
                startTime = 0L,
                endTime   = null,
                label     = "ميزانية عامة"
            ))
            saveSessions(context, updated)
        }
    }

    // ===== العناصر =====

    fun saveItems(context: Context, items: List<ShoppingItem>) {
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().apply {
                put("name",         item.name)
                put("pricePerUnit", item.pricePerUnit)
                put("quantity",     item.quantity)
                put("total",        item.total)
                put("priceSource",  item.priceSource)
                put("sessionId",    item.sessionId)
                put("timestamp",    item.timestamp)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun loadItems(context: Context): MutableList<ShoppingItem> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "[]") ?: "[]"
        val result = mutableListOf<ShoppingItem>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(ShoppingItem(
                    name         = obj.getString("name"),
                    pricePerUnit = obj.getDouble("pricePerUnit"),
                    quantity     = obj.getDouble("quantity"),
                    total        = obj.getDouble("total"),
                    priceSource  = obj.optString("priceSource", "مدخل"),
                    sessionId    = obj.optString("sessionId", GENERAL_SESSION_ID),
                    timestamp    = obj.optLong("timestamp", 0)
                ))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return result
    }

    fun addItem(context: Context, item: ShoppingItem) {
        ensureGeneralSession(context)
        val items = loadItems(context)
        items.add(item)
        saveItems(context, items)
    }

    fun clearItems(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_ITEMS).remove(KEY_SESSIONS).remove(KEY_ACTIVE_SESSION).apply()
    }

    // ===== الإجماليات =====

    fun getSessionTotal(context: Context, sessionId: String): Double =
        loadItems(context).filter { it.sessionId == sessionId }.sumOf { it.total }

    fun getTotal(context: Context): Double = loadItems(context).sumOf { it.total }

    // ===== الجلسة الحالية =====

    fun getActiveSession(context: Context): ShoppingSession? {
        val id = getActiveSessionId(context)
        return loadSessions(context).find { it.id == id }
    }

    // ===== تحليل الشراء =====

    fun parsePurchase(rawInput: String): ParsedPurchase? {
        val input = rawInput.normalizeNumbers()
        val lower = input.lowercase().trim()
        val triggers = listOf("اشتريت", "أخذت", "اخذت", "جبت", "حصلت على", "شريت")
        val trigger = triggers.firstOrNull { lower.startsWith(it) } ?: return null
        val rest = lower.removePrefix(trigger).trim()
        if (rest.isBlank()) return null

        var explicitPrice: Double? = null
        var workingText = rest

        val pricePattern = Regex("\\s+(?:بـ?|بسعر)\\s+(\\d+(?:\\.\\d+)?)")
        val priceMatch = pricePattern.find(workingText)
        if (priceMatch != null) {
            explicitPrice = priceMatch.groupValues[1].toDoubleOrNull()
            workingText = workingText.substring(0, priceMatch.range.first).trim()
        }

        var quantity: Double? = null
        var itemName: String

        val qtyFirstPattern = Regex("^(\\d+(?:\\.\\d+)?)\\s+(.+)$")
        val qf = qtyFirstPattern.find(workingText)
        if (qf != null) {
            quantity = qf.groupValues[1].toDoubleOrNull()
            itemName = qf.groupValues[2].trim()
        } else {
            itemName = workingText.trim()
        }

        if (itemName.isBlank()) return null

        return ParsedPurchase(
            itemName      = itemName,
            explicitPrice = explicitPrice,
            quantity      = quantity,
            isWeightBased = false
        )
    }

    fun buildItem(parsed: ParsedPurchase, memoryPrice: Double?, sessionId: String): ShoppingItem? {
        val pricePerUnit: Double
        val priceSource: String

        when {
            parsed.explicitPrice != null -> {
                pricePerUnit = parsed.explicitPrice
                priceSource  = "مدخل"
            }
            memoryPrice != null -> {
                pricePerUnit = memoryPrice
                priceSource  = "ذاكرة"
            }
            else -> return null
        }

        val qty   = parsed.quantity ?: 1.0
        val total = pricePerUnit * qty

        return ShoppingItem(
            name         = parsed.itemName,
            pricePerUnit = pricePerUnit,
            quantity     = qty,
            total        = total,
            priceSource  = priceSource,
            sessionId    = sessionId
        )
    }

    // ===== البحث بالتاريخ =====

    fun getItemsByDate(context: Context, dayStart: Long, dayEnd: Long): List<ShoppingItem> =
        loadItems(context).filter { it.timestamp in dayStart..dayEnd }

    fun getSessionsByDate(context: Context, dayStart: Long, dayEnd: Long): List<ShoppingSession> =
        loadSessions(context).filter { it.startTime in dayStart..dayEnd || it.id == GENERAL_SESSION_ID }

    fun parseDate(rawInput: String): Pair<Long, Long>? {
        val input = rawInput.normalizeNumbers()
        val lower = input.lowercase().trim()
        val cal   = java.util.Calendar.getInstance()

        fun startOfDay(c: java.util.Calendar): Long {
            c.set(java.util.Calendar.HOUR_OF_DAY, 0)
            c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0)
            c.set(java.util.Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
        fun endOfDay(start: Long) = start + 86_399_999L

        if (lower.contains("اليوم")) {
            val s = startOfDay(cal); return Pair(s, endOfDay(s))
        }
        if (lower.contains("امس") || lower.contains("امس")) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            val s = startOfDay(cal); return Pair(s, endOfDay(s))
        }
        if (lower.contains("اول امس") || lower.contains("اول امس")) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, -2)
            val s = startOfDay(cal); return Pair(s, endOfDay(s))
        }

        val dayNames = mapOf(
            "الأحد" to java.util.Calendar.SUNDAY, "الاحد" to java.util.Calendar.SUNDAY,
            "الاثنين" to java.util.Calendar.MONDAY,
            "الثلاثاء" to java.util.Calendar.TUESDAY,
            "الأربعاء" to java.util.Calendar.WEDNESDAY, "الاربعاء" to java.util.Calendar.WEDNESDAY,
            "الخميس" to java.util.Calendar.THURSDAY,
            "الجمعة" to java.util.Calendar.FRIDAY,
            "السبت" to java.util.Calendar.SATURDAY
        )
        for ((name, dayOfWeek) in dayNames) {
            if (lower.contains(name)) {
                var diff = cal.get(java.util.Calendar.DAY_OF_WEEK) - dayOfWeek
                if (diff <= 0) diff += 7
                cal.add(java.util.Calendar.DAY_OF_YEAR, -diff)
                val s = startOfDay(cal); return Pair(s, endOfDay(s))
            }
        }

        val datePattern = Regex("(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?")
        datePattern.find(lower)?.let { m ->
            val day   = m.groupValues[1].toIntOrNull() ?: return null
            val month = m.groupValues[2].toIntOrNull() ?: return null
            val year  = m.groupValues[3].toIntOrNull()
                ?.let { if (it < 100) 2000 + it else it }
                ?: cal.get(java.util.Calendar.YEAR)
            cal.set(year, month - 1, day)
            val s = startOfDay(cal); return Pair(s, endOfDay(s))
        }

        return null
    }

    // ===== التنسيق =====

    private val timeFmt = SimpleDateFormat("hh:mm a", Locale("ar"))

    /** يعرض كل جلسات يوم معين كل واحدة في جدول منفصل */
    fun formatDateReceipt(context: Context, items: List<ShoppingItem>, dateLabel: String): String {
        if (items.isEmpty()) return "🛒 لم تشتري شيئاً $dateLabel"

        val sessions = loadSessions(context)
        val sb = StringBuilder()

        // جمّع العناصر حسب الجلسة
        val bySession = items.groupBy { it.sessionId }

        // رتّب الجلسات بالوقت
        val sortedSessions = bySession.keys.mapNotNull { sid ->
            sessions.find { it.id == sid } ?: if (sid == GENERAL_SESSION_ID)
                ShoppingSession(GENERAL_SESSION_ID, 0.0, 0L, null, "ميزانية عامة")
            else null
        }.sortedBy { it.startTime }

        sortedSessions.forEachIndexed { index, session ->
            val sessionItems = bySession[session.id] ?: return@forEachIndexed
            val total = sessionItems.sumOf { it.total }

            if (index > 0) sb.appendLine()

            // رأس الجلسة
            if (session.id == GENERAL_SESSION_ID) {
                sb.appendLine("📦 الميزانية العامة")
            } else {
                val timeStr = timeFmt.format(java.util.Date(session.startTime))
                sb.appendLine("🛒 ${session.label} ($timeStr)")
                if (session.budget > 0) sb.appendLine("💼 الميزانية: ${formatNum(session.budget)} ر")
            }
            sb.appendLine("─────────────────")

            sessionItems.forEachIndexed { i, item ->
                val qtyStr = if (item.quantity != 1.0) " × ${formatNum(item.quantity)}" else ""
                val src    = if (item.priceSource == "ذاكرة") " 🧠" else ""
                sb.appendLine("${i + 1}. ${item.name}$qtyStr = ${formatNum(item.total)} ر$src")
            }

            sb.appendLine("─────────────────")
            sb.append("💰 الإجمالي: ${formatNum(total)} ر")

            if (session.budget > 0) {
                val remaining = session.budget - total
                if (remaining >= 0) sb.append(" | ✅ الباقي: ${formatNum(remaining)} ر")
                else sb.append(" | ⚠️ تجاوزت بـ ${formatNum(-remaining)} ر")
            }
            sb.appendLine()
        }

        return sb.toString().trimEnd()
    }

    /** يعرض الجلسة الحالية المفتوحة */
    fun formatCurrentSession(context: Context): String {
        val session = getActiveSession(context)
        val sessionId = getActiveSessionId(context)
        val items = loadItems(context).filter { it.sessionId == sessionId }
        val total = items.sumOf { it.total }

        if (items.isEmpty()) return "🛒 قائمة التسوق فارغة."

        val sb = StringBuilder()

        if (sessionId == GENERAL_SESSION_ID) {
            sb.appendLine("📦 الميزانية العامة")
        } else {
            sb.appendLine("🛒 ${session?.label ?: "الجلسة الحالية"}")
            if ((session?.budget ?: 0.0) > 0)
                sb.appendLine("💼 الميزانية: ${formatNum(session!!.budget)} ر")
        }

        sb.appendLine("─────────────────")
        items.forEachIndexed { i, item ->
            val qtyStr = if (item.quantity != 1.0) " × ${formatNum(item.quantity)}" else ""
            val src    = if (item.priceSource == "ذاكرة") " 🧠" else ""
            sb.appendLine("${i + 1}. ${item.name}$qtyStr = ${formatNum(item.total)} ر$src")
        }
        sb.appendLine("─────────────────")
        sb.append("💰 الإجمالي: ${formatNum(total)} ر")

        val budget = session?.budget ?: 0.0
        if (budget > 0) {
            val remaining = budget - total
            if (remaining >= 0) sb.append(" | ✅ الباقي: ${formatNum(remaining)} ر")
            else sb.append(" | ⚠️ تجاوزت بـ ${formatNum(-remaining)} ر")
        }

        return sb.toString().trimEnd()
    }

    fun formatNum(n: Double): String =
        if (n % 1.0 == 0.0) n.toLong().toString() else "%.2f".format(n)
}
