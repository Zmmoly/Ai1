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

    // ===== تنسيق العرض =====

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
