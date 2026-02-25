package com.awab.ai

/**
 * =====================================================
 *  StepEngine v2 — محرك خطوات بلا حدود
 * =====================================================
 *
 *  الخطوة هي شجرة (Tree) وليس نصاً مسطحاً.
 *  كل خطوة يمكن أن تكون:
 *
 *  ① Normal   → أمر عادي
 *  ② IfChain  → سلسلة إذا / وإلا إذا / وإلا (بلا حدود)
 *  ③ Loop     → حلقة تكرار (جسمها قائمة Steps)
 *
 *  الفاصل بين الفروع:    |
 *  الفاصل شرط → أمر:     →  أو  :
 *
 *  أمثلة:
 *  إذا A → x1 | وإلا إذا B → x2 | وإلا إذا C → x3 | وإلا → xN
 *  كرر 5 مرات → إذا A → x1 | وإلا → x2
 *  إذا A و B → x1 | وإلا إذا A أو C → x2 | وإلا → x3
 * =====================================================
 */

// ===== نموذج البيانات =====

sealed class Step {
    data class Normal(val command: String) : Step()

    data class IfChain(
        val branches: List<Branch>,
        val elseBranch: List<Step>?
    ) : Step() {
        data class Branch(val condition: String, val steps: List<Step>)
    }

    data class Loop(val times: Int, val body: List<Step>) : Step()
}

// ===== المحرك =====

object StepEngine {

    // ─── تحليل نص الخطوة ───────────────────

    fun parse(raw: String): Step {
        val t = raw.trim()

        // حلقة: "كرر N مرات → ..."
        Regex(
            "^(?:كرر|تكرار)\\s+(\\d+)\\s*(?:مرات?|مره)?\\s*(?:→|->|:)\\s*(.+)$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).matchEntire(t)?.let { m ->
            val times = m.groupValues[1].toIntOrNull()?.coerceIn(1, 100) ?: 1
            return Step.Loop(times, listOf(parse(m.groupValues[2].trim())))
        }

        // شرط
        if (t.startsWith("إذا") || t.startsWith("اذا") || t.startsWith("لو ")) {
            return parseIfChain(t)
        }

        return Step.Normal(t)
    }

    private fun parseIfChain(raw: String): Step {
        val segments = splitOnPipe(raw)
        val branches = mutableListOf<Step.IfChain.Branch>()
        var elseBranch: List<Step>? = null

        for (seg in segments) {
            val s = seg.trim()

            // "وإلا → X" بدون شرط
            Regex(
                "^(?:وإلا|والا|else)\\s*(?:→|->|:)\\s*(.+)$",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).matchEntire(s)?.let { m ->
                elseBranch = listOf(parse(m.groupValues[1].trim()))
                return@let
            }
            if (elseBranch != null) continue

            // "إذا X → Y"  أو  "وإلا إذا X → Y"
            Regex(
                "^(?:(?:وإلا|والا|else)\\s+)?(?:إذا|اذا|لو|if)\\s+(.+?)\\s*(?:→|->|:)\\s*(.+)$",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).matchEntire(s)?.let { m ->
                branches.add(
                    Step.IfChain.Branch(
                        condition = m.groupValues[1].trim(),
                        steps = listOf(parse(m.groupValues[2].trim()))
                    )
                )
            }
        }

        return Step.IfChain(branches, elseBranch)
    }

    /** تقسيم على | مع مراعاة الأقواس */
    private fun splitOnPipe(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val cur = StringBuilder()
        for (ch in text) {
            when (ch) {
                '(', '[', '{' -> { depth++; cur.append(ch) }
                ')', ']', '}' -> { depth--; cur.append(ch) }
                '|' -> if (depth == 0) { parts.add(cur.toString()); cur.clear() }
                     else cur.append(ch)
                else -> cur.append(ch)
            }
        }
        if (cur.isNotBlank()) parts.add(cur.toString())
        return parts
    }

    // ─── تقييم الشروط ──────────────────────

    fun evaluateCondition(condition: String): Boolean {
        val lower = condition.lowercase().trim()

        if (lower in listOf("دائماً","دائما","صح","true","نعم","yes")) return true
        if (lower in listOf("خطأ","خطا","أبداً","false","لا","no"))   return false

        // AND: "... و ..."
        if (lower.contains(" و ") && !lower.startsWith("الشاشة")) {
            return lower.split(" و ").all { evaluateCondition(it.trim()) }
        }
        // OR: "... أو ..."
        if (lower.contains(" أو ")) {
            return lower.split(" أو ").any { evaluateCondition(it.trim()) }
        }

        val service = MyAccessibilityService.getInstance() ?: return false
        val screenText = service.getScreenText().lowercase()

        // "تحتوي X" / "يوجد X" / "موجود X"
        Regex("(?:الشاشة\\s+)?(?:تحتوي|يوجد|موجود)\\s+(?:على\\s+)?[\"']?(.+?)[\"']?$")
            .find(lower)?.let { return screenText.contains(it.groupValues[1].trim()) }

        // "لا تحتوي X" / "لا يوجد X" / "غير موجود X"
        Regex("(?:الشاشة\\s+)?(?:لا\\s+تحتوي|لا\\s+يوجد|غير\\s+موجود)\\s+(?:على\\s+)?[\"']?(.+?)[\"']?$")
            .find(lower)?.let { return !screenText.contains(it.groupValues[1].trim()) }

        // fallback: ابحث مباشرة
        return screenText.contains(lower)
    }

    // ─── وصف مقروء ─────────────────────────

    fun describe(step: Step, indent: String = ""): String = when (step) {
        is Step.Normal -> "$indent▶ ${step.command}"

        is Step.IfChain -> buildString {
            step.branches.forEachIndexed { i, b ->
                val kw = if (i == 0) "🔀 إذا" else "↪ وإلا إذا"
                appendLine("$indent$kw [${b.condition}]")
                b.steps.forEach { appendLine(describe(it, "$indent    ")) }
            }
            step.elseBranch?.let { els ->
                appendLine("$indent↩ وإلا")
                els.forEach { appendLine(describe(it, "$indent    ")) }
            }
        }.trimEnd()

        is Step.Loop -> buildString {
            appendLine("$indent🔁 كرر ${step.times} مرات:")
            step.body.forEach { appendLine(describe(it, "$indent    ")) }
        }.trimEnd()
    }

    // ─── تلميحات الصيغ ─────────────────────

    val SYNTAX_HINTS = """
▶ خطوة عادية:
  افتح واتساب

🔀 شرط بسيط:
  إذا الشاشة تحتوي إرسال → اضغط على إرسال

🔀 شرط مع وإلا:
  إذا الشاشة تحتوي إرسال → اضغط على إرسال | وإلا → رجوع

🔀 سلسلة شروط بلا حدود:
  إذا تحتوي A → أمر1 | وإلا إذا تحتوي B → أمر2 | وإلا إذا تحتوي C → أمر3 | وإلا → رجوع

🔁 حلقة:
  كرر 3 مرات → على الصوت

🔁 حلقة + شرط داخلها:
  كرر 5 مرات → إذا تحتوي تأكيد → اضغط على تأكيد | وإلا → سكرين شوت

🔗 شروط مركبة:
  إذا تحتوي A و تحتوي B → أمر
  إذا تحتوي A أو تحتوي B → أمر
""".trim()
}
