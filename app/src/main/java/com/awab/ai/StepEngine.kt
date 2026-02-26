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

    /**
     * انتظار حدث على الشاشة
     * @param targetText  النص المطلوب
     * @param waitForShow true=ظهور / false=اختفاء
     * @param timeoutSec  مهلة بالثواني (افتراضي 15)
     * @param onFound     أمر يُنفَّذ عند تحقق الشرط (اختياري)
     * @param onTimeout   أمر يُنفَّذ عند انتهاء المهلة (اختياري)
     */
    data class Wait(
        val targetText: String,
        val waitForShow: Boolean,
        val timeoutSec: Int,
        val onFound: String?,
        val onTimeout: String?,
        val packageName: String? = null   // null = التطبيق الحالي تلقائياً
    ) : Step()
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

        // انتظار ظهور/اختفاء عنصر
        // صيغ:
        //   انتظر ظهور [تخطي]
        //   انتظر ظهور [تخطي] في يوتيوب
        //   انتظر ظهور [تخطي] في يوتيوب لمدة 10 ثانية ثم اضغط على تخطي وإلا رجوع
        //   انتظر اختفاء [جاري التحميل] في واتساب ثم اضغط على ابدأ
        val waitRegex = Regex(
            "^انتظر\\s+(ظهور|اختفاء)\\s+\\[(.+?)\\]" +
            "(?:\\s+في\\s+([\\w\\u0600-\\u06FF]+))?" +
            "(?:\\s+لمدة\\s+(\\d+)\\s*(?:ثانية|ثواني|ث))?" +
            "(?:\\s+ثم\\s+(.+?))?" +
            "(?:\\s+وإلا\\s+(.+))?$",
            RegexOption.IGNORE_CASE
        )
        waitRegex.matchEntire(t)?.let { m ->
            val waitForShow = m.groupValues[1] == "ظهور"
            val target      = m.groupValues[2].trim()
            val appName     = m.groupValues[3].trim().takeIf { it.isNotBlank() }
            val timeout     = m.groupValues[4].toIntOrNull() ?: 15
            val onFound     = m.groupValues[5].trim().takeIf { it.isNotBlank() }
            val onTimeout   = m.groupValues[6].trim().takeIf { it.isNotBlank() }
            val pkg         = appName?.let { resolvePackage(it) }
            return Step.Wait(target, waitForShow, timeout, onFound, onTimeout, pkg)
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

    // ─── خريطة أسماء التطبيقات ─────────────

    private val appPackageMap = mapOf(
        "يوتيوب"      to "com.google.android.youtube",
        "youtube"     to "com.google.android.youtube",
        "واتساب"      to "com.whatsapp",
        "whatsapp"    to "com.whatsapp",
        "انستقرام"    to "com.instagram.android",
        "instagram"   to "com.instagram.android",
        "سناب"        to "com.snapchat.android",
        "سناب شات"    to "com.snapchat.android",
        "snapchat"    to "com.snapchat.android",
        "تيك توك"     to "com.zhiliaoapp.musically",
        "tiktok"      to "com.zhiliaoapp.musically",
        "تويتر"       to "com.twitter.android",
        "twitter"     to "com.twitter.android",
        "اكس"         to "com.twitter.android",
        "x"           to "com.twitter.android",
        "فيسبوك"      to "com.facebook.katana",
        "facebook"    to "com.facebook.katana",
        "تيليقرام"    to "org.telegram.messenger",
        "تيليغرام"    to "org.telegram.messenger",
        "telegram"    to "org.telegram.messenger",
        "كروم"        to "com.android.chrome",
        "chrome"      to "com.android.chrome",
        "جوجل"        to "com.google.android.googlequicksearchbox",
        "google"      to "com.google.android.googlequicksearchbox",
        "الاعدادات"   to "com.android.settings",
        "الإعدادات"   to "com.android.settings",
        "settings"    to "com.android.settings",
        "نتفليكس"     to "com.netflix.mediaclient",
        "netflix"     to "com.netflix.mediaclient",
        "سبوتيفاي"    to "com.spotify.music",
        "spotify"     to "com.spotify.music",
        "جيميل"       to "com.google.android.gm",
        "gmail"       to "com.google.android.gm",
        "خرائط"       to "com.google.android.apps.maps",
        "maps"        to "com.google.android.apps.maps"
    )

    /**
     * يحوّل اسم التطبيق بالعربي أو الإنجليزي إلى package name
     * إذا لم يجده في الخريطة يعتبر النص نفسه package name
     */
    fun resolvePackage(appName: String): String {
        val lower = appName.lowercase().trim()
        return appPackageMap[lower] ?: appName
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

        is Step.Wait -> buildString {
            val dir    = if (step.waitForShow) "ظهور" else "اختفاء"
            val appStr = step.packageName?.let {
                val name = appPackageMap.entries.firstOrNull { e -> e.value == it }?.key ?: it
                " في $name"
            } ?: ""
            append("$indent⏳ انتظر $dir [${step.targetText}]$appStr لمدة ${step.timeoutSec}ث")
            step.onFound?.let   { append(" ثم $it") }
            step.onTimeout?.let { append(" وإلا $it") }
        }
    }

    // ─── تلميحات الصيغ ─────────────────────

    val SYNTAX_HINTS = """
▶ خطوة عادية:
  افتح واتساب

🔀 شرط بسيط:
  إذا الشاشة تحتوي إرسال → اضغط على إرسال

🔀 سلسلة شروط بلا حدود:
  إذا تحتوي A → أمر1 | وإلا إذا تحتوي B → أمر2 | وإلا → رجوع

🔁 حلقة:
  كرر 3 مرات → على الصوت

⏳ انتظار ظهور عنصر (التطبيق الحالي تلقائياً):
  انتظر ظهور [تخطي]
  انتظر ظهور [تخطي] ثم اضغط على تخطي
  انتظر ظهور [تخطي] لمدة 10 ثانية ثم اضغط على تخطي وإلا رجوع

⏳ انتظار مع تحديد التطبيق:
  انتظر ظهور [تخطي] في يوتيوب ثم اضغط على تخطي
  انتظر ظهور [قبول] في واتساب لمدة 15 ثانية ثم اضغط على قبول
  انتظر اختفاء [جاري التحميل] في انستقرام ثم اضغط على ابدأ

🔗 شروط مركبة:
  إذا تحتوي A و تحتوي B → أمر
  إذا تحتوي A أو تحتوي B → أمر
""".trim()
}
