package com.awab.ai

/**
 * ===================================================
 *  محرك الخطوات — StepEngine
 * ===================================================
 *
 *  يدعم ثلاثة أنواع من الخطوات داخل الأوامر المخصصة:
 *
 *  1. خطوة عادية  ← أي أمر من CommandHandler
 *     مثال:  افتح واتساب
 *
 *  2. شرط  ← إذا [شرط]: [أمر_صح] وإلا: [أمر_خطأ]
 *     مثال:  إذا الشاشة تحتوي "إرسال": اضغط على إرسال وإلا: رجوع
 *     (جزء "وإلا" اختياري)
 *
 *  3. حلقة  ← كرر [N] مرات: [أمر]
 *     مثال:  كرر 3 مرات: على الصوت
 *     أو     كرر 5 مرات: سكرين شوت
 *
 * ===================================================
 */

sealed class Step {
    /** خطوة عادية */
    data class Normal(val command: String) : Step()

    /** شرط: إذا [condition] → [onTrue]  (وإلا → [onFalse]) */
    data class Condition(
        val condition: String,
        val onTrue: String,
        val onFalse: String?
    ) : Step()

    /** حلقة: كرر [times] مرات → [command] */
    data class Loop(
        val times: Int,
        val command: String
    ) : Step()
}

object StepEngine {

    // ===== تحليل نص الخطوة =====

    fun parse(raw: String): Step {
        val trimmed = raw.trim()

        // --- شرط ---
        // صيغ: "إذا X: Y وإلا: Z"  أو  "إذا X: Y"
        val conditionRegex = Regex(
            "^(?:إذا|اذا|لو)\\s+(.+?)\\s*:\\s*(.+?)(?:\\s+وإلا\\s*:\\s*(.+))?$",
            RegexOption.IGNORE_CASE
        )
        conditionRegex.matchEntire(trimmed)?.let { m ->
            return Step.Condition(
                condition = m.groupValues[1].trim(),
                onTrue    = m.groupValues[2].trim(),
                onFalse   = m.groupValues[3].trim().takeIf { it.isNotBlank() }
            )
        }

        // --- حلقة ---
        // صيغ: "كرر 3 مرات: على الصوت"  أو  "كرر 3: على الصوت"
        val loopRegex = Regex(
            "^(?:كرر|تكرار)\\s+(\\d+)\\s*(?:مرات?|مره)?\\s*:\\s*(.+)$",
            RegexOption.IGNORE_CASE
        )
        loopRegex.matchEntire(trimmed)?.let { m ->
            val times = m.groupValues[1].toIntOrNull()?.coerceIn(1, 50) ?: 1
            return Step.Loop(times = times, command = m.groupValues[2].trim())
        }

        // --- خطوة عادية ---
        return Step.Normal(trimmed)
    }

    // ===== تقييم الشروط =====

    /**
     * يقيّم الشرط بناءً على حالة الشاشة الحالية (عبر Accessibility)
     * الشروط المدعومة:
     *   - "الشاشة تحتوي X"   → يتحقق إذا كانت الشاشة تحتوي على نص X
     *   - "الشاشة لا تحتوي X"
     *   - "دائماً" / "صح"    → دائماً صحيح
     *   - "خطأ" / "أبداً"    → دائماً خطأ
     */
    fun evaluateCondition(condition: String): Boolean {
        val lower = condition.lowercase().trim()

        if (lower == "دائماً" || lower == "دائما" || lower == "صح" || lower == "true") return true
        if (lower == "خطأ" || lower == "خطا" || lower == "أبداً" || lower == "false") return false

        val service = MyAccessibilityService.getInstance() ?: return false
        val screenText = service.getScreenText().lowercase()

        // "الشاشة تحتوي X"
        val containsPositive = Regex("(?:الشاشة\\s+)?(?:تحتوي|يوجد|موجود)\\s+(?:على\\s+)?[\"']?(.+?)[\"']?$")
        containsPositive.find(lower)?.let {
            val keyword = it.groupValues[1].trim()
            return screenText.contains(keyword)
        }

        // "الشاشة لا تحتوي X"
        val containsNegative = Regex("(?:الشاشة\\s+)?(?:لا\\s+تحتوي|لا\\s+يوجد|غير\\s+موجود)\\s+(?:على\\s+)?[\"']?(.+?)[\"']?$")
        containsNegative.find(lower)?.let {
            val keyword = it.groupValues[1].trim()
            return !screenText.contains(keyword)
        }

        // fallback: اعتبر النص كـ keyword وابحث عنه في الشاشة
        return screenText.contains(lower)
    }

    // ===== توليد وصف مقروء للمعاينة =====

    fun describe(step: Step): String = when (step) {
        is Step.Normal    -> step.command
        is Step.Condition -> buildString {
            append("🔀 إذا [${step.condition}]:\n")
            append("     ✅ ${step.onTrue}")
            step.onFalse?.let { append("\n     ❌ وإلا: $it") }
        }
        is Step.Loop      -> "🔁 كرر ${step.times} مرات: ${step.command}"
    }

    // ===== تلميحات الصيغ للمستخدم =====

    val SYNTAX_HINTS = """
🔵 خطوة عادية:
  افتح واتساب
  سكرين شوت

🔀 شرط (إذا / وإلا):
  إذا الشاشة تحتوي إرسال: اضغط على إرسال وإلا: رجوع
  إذا الشاشة لا تحتوي قبول: رجوع
  لو موجود "تأكيد": اضغط على تأكيد

🔁 حلقة (كرر):
  كرر 3 مرات: على الصوت
  كرر 5 مرات: سكرين شوت
  كرر 2: رجوع
""".trim()
}
