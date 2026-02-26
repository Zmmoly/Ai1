package com.awab.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * نموذج عنصر في قائمة التسوق
 */
data class ShoppingItem(
    val name: String,           // اسم المنتج
    val pricePerUnit: Double,   // سعر الوحدة أو الكيلو
    val quantity: Double,       // الكمية أو الوزن
    val total: Double,          // الإجمالي = السعر × الكمية
    val priceSource: String,    // "ذاكرة" أو "مدخل" أو "وزن"
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * نتيجة تحليل جملة "اشتريت ..."
 */
data class ParsedPurchase(
    val itemName: String,
    val explicitPrice: Double?,   // سعر مذكور صراحةً (اشتريت X بـ 10)
    val quantity: Double?,        // كمية أو وزن مذكور (اشتريت X كيلو 2)
    val isWeightBased: Boolean    // هل الرقم يمثل وزناً أو كمية؟
)

object ShoppingManager {

    private const val PREFS_NAME = "shopping_prefs"
    private const val KEY_ITEMS  = "shopping_items"
    private const val KEY_BUDGET = "shopping_budget"

    // ===== حفظ وتحميل القائمة =====

    fun saveItems(context: Context, items: List<ShoppingItem>) {
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().apply {
                put("name",         item.name)
                put("pricePerUnit", item.pricePerUnit)
                put("quantity",     item.quantity)
                put("total",        item.total)
                put("priceSource",  item.priceSource)
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
                    timestamp    = obj.optLong("timestamp", 0)
                ))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return result
    }

    fun clearItems(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_ITEMS).apply()
    }

    // ===== الميزانية =====

    fun saveBudget(context: Context, amount: Double) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_BUDGET, amount.toFloat()).apply()
    }

    fun loadBudget(context: Context): Double {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_BUDGET, 0f).toDouble()
    }

    // ===== إجمالي المشتريات =====

    fun getTotal(context: Context): Double =
        loadItems(context).sumOf { it.total }

    fun getRemaining(context: Context): Double {
        val budget = loadBudget(context)
        return if (budget > 0) budget - getTotal(context) else 0.0
    }

    // ===== إضافة عنصر =====

    fun addItem(context: Context, item: ShoppingItem) {
        val items = loadItems(context)
        items.add(item)
        saveItems(context, items)
    }

    // ===== تحليل جملة "اشتريت ..." =====

    /**
     * يحلل الجملة ويُرجع ParsedPurchase
     *
     * القواعد:
     * - "بـ X" أو "ب X"           → سعر الوحدة
     * - "X شيء" (رقم قبل الاسم)  → كمية (قطع)
     * - "شيء" بدون رقم أو بـ      → يبحث في الذاكرة، إذا ما لقى يسأل
     */
    fun parsePurchase(input: String): ParsedPurchase? {
        val lower = input.lowercase().trim()

        val triggers = listOf("اشتريت", "أخذت", "اخذت", "جبت", "حصلت على", "شريت")
        val trigger = triggers.firstOrNull { lower.startsWith(it) } ?: return null
        val rest = lower.removePrefix(trigger).trim()

        if (rest.isBlank()) return null

        // 1) استخراج السعر: "بـ X" أو "ب X" أو "بسعر X"
        var explicitPrice: Double? = null
        var workingText = rest

        val pricePattern = Regex("\\s+(?:بـ?|بسعر)\\s+(\\d+(?:\\.\\d+)?)")
        val priceMatch = pricePattern.find(workingText)
        if (priceMatch != null) {
            explicitPrice = priceMatch.groupValues[1].toDoubleOrNull()
            workingText = workingText.substring(0, priceMatch.range.first).trim()
        }

        // 2) استخراج الكمية: رقم في البداية فقط "3 تفاح"
        var quantity: Double? = null
        var itemName: String

        val qtyFirstPattern = Regex("^(\\d+(?:\\.\\d+)?)\\s+(.+)$")
        val qf = qtyFirstPattern.find(workingText)
        if (qf != null) {
            quantity = qf.groupValues[1].toDoubleOrNull()
            itemName = qf.groupValues[2].trim()
        } else {
            // لا يوجد رقم في البداية → الكل هو الاسم
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

    /**
     * يحوّل ParsedPurchase + سعر الذاكرة → ShoppingItem
     * يُرجع null إذا لم يُعرف السعر
     */
    fun buildItem(
        parsed: ParsedPurchase,
        memoryPrice: Double?
    ): ShoppingItem? {

        val pricePerUnit: Double
        val priceSource: String

        when {
            // السعر مذكور صراحةً
            parsed.explicitPrice != null -> {
                pricePerUnit = parsed.explicitPrice
                priceSource  = "مدخل"
            }
            // السعر من الذاكرة
            memoryPrice != null -> {
                pricePerUnit = memoryPrice
                priceSource  = "ذاكرة"
            }
            // لا يوجد سعر
            else -> return null
        }

        val qty   = parsed.quantity ?: 1.0
        val total = pricePerUnit * qty

        return ShoppingItem(
            name         = parsed.itemName,
            pricePerUnit = pricePerUnit,
            quantity     = qty,
            total        = total,
            priceSource  = priceSource
        )
    }

    // ===== البحث بالتاريخ =====

    /**
     * يُرجع المشتريات في يوم محدد
     * @param dayStart بداية اليوم بالميلي ثانية
     * @param dayEnd   نهاية اليوم بالميلي ثانية
     */
    fun getItemsByDate(context: Context, dayStart: Long, dayEnd: Long): List<ShoppingItem> {
        return loadItems(context).filter { it.timestamp in dayStart..dayEnd }
    }

    /**
     * يحلل نص التاريخ ويُرجع (بداية اليوم، نهاية اليوم)
     * يدعم: اليوم، امس، أول امس، يوم الاثنين...الجمعة، تاريخ رقمي 20/3
     */
    fun parseDate(input: String): Pair<Long, Long>? {
        val lower = input.lowercase().trim()
        val cal   = java.util.Calendar.getInstance()

        // اضبط لبداية اليوم
        fun startOfDay(c: java.util.Calendar): Long {
            c.set(java.util.Calendar.HOUR_OF_DAY, 0)
            c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0)
            c.set(java.util.Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
        fun endOfDay(start: Long) = start + 86_399_999L  // +23:59:59.999

        // اليوم
        if (lower.contains("اليوم")) {
            val s = startOfDay(cal)
            return Pair(s, endOfDay(s))
        }

        // امس
        if (lower.contains("امس") || lower.contains("أمس")) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            val s = startOfDay(cal)
            return Pair(s, endOfDay(s))
        }

        // أول امس
        if (lower.contains("أول امس") || lower.contains("اول امس")) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, -2)
            val s = startOfDay(cal)
            return Pair(s, endOfDay(s))
        }

        // أيام الأسبوع
        val dayNames = mapOf(
            "الأحد"    to java.util.Calendar.SUNDAY,
            "الاحد"    to java.util.Calendar.SUNDAY,
            "الاثنين"  to java.util.Calendar.MONDAY,
            "الثلاثاء" to java.util.Calendar.TUESDAY,
            "الأربعاء" to java.util.Calendar.WEDNESDAY,
            "الاربعاء" to java.util.Calendar.WEDNESDAY,
            "الخميس"   to java.util.Calendar.THURSDAY,
            "الجمعة"   to java.util.Calendar.FRIDAY,
            "السبت"    to java.util.Calendar.SATURDAY
        )
        for ((name, dayOfWeek) in dayNames) {
            if (lower.contains(name)) {
                // ارجع للخلف حتى نجد هذا اليوم
                var diff = cal.get(java.util.Calendar.DAY_OF_WEEK) - dayOfWeek
                if (diff <= 0) diff += 7
                cal.add(java.util.Calendar.DAY_OF_YEAR, -diff)
                val s = startOfDay(cal)
                return Pair(s, endOfDay(s))
            }
        }

        // تاريخ رقمي: "20/3" أو "20/3/2025"
        val datePattern = Regex("(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?")
        datePattern.find(lower)?.let { m ->
            val day   = m.groupValues[1].toIntOrNull() ?: return null
            val month = m.groupValues[2].toIntOrNull() ?: return null
            val year  = m.groupValues[3].toIntOrNull()
                ?.let { if (it < 100) 2000 + it else it }
                ?: cal.get(java.util.Calendar.YEAR)
            cal.set(year, month - 1, day)
            val s = startOfDay(cal)
            return Pair(s, endOfDay(s))
        }

        return null
    }

    /** يُنسّق فاتورة ليوم محدد */
    fun formatDateReceipt(context: Context, items: List<ShoppingItem>, dateLabel: String): String {
        if (items.isEmpty()) return "🛒 لم تشتري شيئاً $dateLabel"

        val total = items.sumOf { it.total }
        val sb    = StringBuilder("🛒 مشتريات $dateLabel:\n")
        sb.appendLine("─────────────────")
        items.forEachIndexed { i, item ->
            val qtyStr = if (item.quantity != 1.0) " × ${formatNum(item.quantity)}" else ""
            val src    = if (item.priceSource == "ذاكرة") " 🧠" else ""
            sb.appendLine("${i + 1}. ${item.name}$qtyStr = ${formatNum(item.total)} ر$src")
        }
        sb.appendLine("─────────────────")
        sb.append("💰 الإجمالي: ${formatNum(total)} ر")
        return sb.toString()
    }

    fun formatReceipt(context: Context): String {
        val items   = loadItems(context)
        val budget  = loadBudget(context)
        val total   = getTotal(context)

        if (items.isEmpty()) return "🛒 قائمة التسوق فارغة."

        val sb = StringBuilder("🛒 قائمة المشتريات:\n")
        sb.appendLine("─────────────────")
        items.forEachIndexed { i, item ->
            val qtyStr = if (item.quantity != 1.0) {
                if (item.priceSource == "وزن" || item.quantity < 10)
                    " × ${formatNum(item.quantity)}"
                else " × ${formatNum(item.quantity)}"
            } else ""
            val sourceTag = if (item.priceSource == "ذاكرة") " 🧠" else ""
            sb.appendLine("${i + 1}. ${item.name}$qtyStr = ${formatNum(item.total)} ر$sourceTag")
        }
        sb.appendLine("─────────────────")
        sb.appendLine("💰 الإجمالي: ${formatNum(total)} ر")

        if (budget > 0) {
            val remaining = budget - total
            if (remaining >= 0) {
                sb.appendLine("✅ الباقي: ${formatNum(remaining)} ر")
            } else {
                sb.appendLine("⚠️ تجاوزت الميزانية بـ ${formatNum(-remaining)} ر")
            }
        }

        return sb.toString().trimEnd()
    }

    fun formatNum(n: Double): String =
        if (n % 1.0 == 0.0) n.toLong().toString() else "%.2f".format(n)
}
