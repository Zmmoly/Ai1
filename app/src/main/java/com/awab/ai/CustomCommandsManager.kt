package com.awab.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * نموذج بيانات الأمر المخصص
 * كل أمر مخصص يحتوي على:
 * - اسم مخصص (trigger) يكتبه المستخدم
 * - قائمة أوامر بالتسلسل
 * - تأخير بين كل أمر (بالثواني)
 */
data class CustomCommand(
    val id: String,
    val name: String,              // الاسم الذي يكتبه المستخدم لتشغيل الأمر
    val description: String,       // وصف اختياري
    val steps: List<String>,       // الأوامر بالتسلسل
    val delaySeconds: Int = 2      // التأخير بين كل خطوة
)

object CustomCommandsManager {

    private const val PREFS_NAME = "custom_commands_prefs"
    private const val KEY_COMMANDS = "commands_json"

    // ===== حفظ وتحميل =====

    fun saveCommands(context: Context, commands: List<CustomCommand>) {
        val jsonArray = JSONArray()
        for (cmd in commands) {
            val obj = JSONObject().apply {
                put("id", cmd.id)
                put("name", cmd.name)
                put("description", cmd.description)
                put("delaySeconds", cmd.delaySeconds)
                val stepsArray = JSONArray()
                cmd.steps.forEach { stepsArray.put(it) }
                put("steps", stepsArray)
            }
            jsonArray.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COMMANDS, jsonArray.toString())
            .apply()
    }

    fun loadCommands(context: Context): MutableList<CustomCommand> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COMMANDS, "[]") ?: "[]"
        val result = mutableListOf<CustomCommand>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val stepsArr = obj.getJSONArray("steps")
                val steps = mutableListOf<String>()
                for (j in 0 until stepsArr.length()) steps.add(stepsArr.getString(j))
                result.add(
                    CustomCommand(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", ""),
                        steps = steps,
                        delaySeconds = obj.optInt("delaySeconds", 2)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun addCommand(context: Context, command: CustomCommand) {
        val list = loadCommands(context)
        list.add(command)
        saveCommands(context, list)
    }

    fun deleteCommand(context: Context, id: String) {
        val list = loadCommands(context)
        list.removeAll { it.id == id }
        saveCommands(context, list)
    }

    fun updateCommand(context: Context, updated: CustomCommand) {
        val list = loadCommands(context)
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) list[idx] = updated
        saveCommands(context, list)
    }

    /**
     * البحث عن أمر مخصص بناءً على النص المكتوب
     */
    fun findByTrigger(context: Context, input: String): CustomCommand? {
        val lower = input.lowercase().trim()
        return loadCommands(context).firstOrNull {
            it.name.lowercase().trim() == lower
        }
    }

    fun generateId(): String = System.currentTimeMillis().toString()

    /**
     * يحوّل قائمة الخطوات الخام إلى قائمة Step مع دعم حلقات متعددة الأسطر.
     *
     * الصيغة المدعومة في الأوامر المخصصة:
     *   ابدأ حلقة 5 مرات
     *      افتح واتساب
     *      اضغط على إرسال
     *      رجوع
     *   انهي حلقة
     *
     * تعمل مع حلقات متداخلة بلا حدود.
     */
    fun parseStepsToStepList(rawSteps: List<String>): List<Step> {
        return parseBlock(rawSteps, 0).first
    }

    private val loopStartRegex = Regex(
        "^(?:ابدأ|ابدا|بدأ|بدا)\\s+حلقة\\s+(\\d+)\\s*(?:مرات?|مره)?$",
        RegexOption.IGNORE_CASE
    )

    private fun parseBlock(lines: List<String>, startIndex: Int): Pair<List<Step>, Int> {
        val steps = mutableListOf<Step>()
        var i = startIndex
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isBlank()) { i++; continue }

            // نهاية كتلة حالية
            if (line.matches(Regex("^(?:انهي|انهِ|أنهي|انتهي)\\s+حلقة$", RegexOption.IGNORE_CASE))) {
                return Pair(steps, i + 1)
            }

            // بداية حلقة متعددة الأسطر
            val loopMatch = loopStartRegex.matchEntire(line)
            if (loopMatch != null) {
                val times = loopMatch.groupValues[1].toIntOrNull()?.coerceIn(1, 100) ?: 1
                val (bodySteps, nextIndex) = parseBlock(lines, i + 1)
                steps.add(Step.Loop(times, bodySteps))
                i = nextIndex
                continue
            }

            // خطوة عادية (تمر على StepEngine لدعم كرر / انتظر / إذا)
            steps.add(StepEngine.parse(line))
            i++
        }
        return Pair(steps, i)
    }

    // قائمة الأوامر الجاهزة (hints للمستخدم عند بناء الخطوات)
    val AVAILABLE_COMMANDS = listOf(
        "افتح [اسم التطبيق]",
        "أقفل [اسم التطبيق]",
        "اتصل ب[اسم أو رقم]",
        "رجوع",
        "الشاشة الرئيسية",
        "التطبيقات الأخيرة",
        "افتح الإشعارات",
        "سكرين شوت",
        "على الصوت",
        "خفض الصوت",
        "كتم الصوت",
        "شغل الواي فاي",
        "اطفي الواي فاي",
        "شغل البلوتوث",
        "اطفي البلوتوث",
        "شغل النت",
        "اطفي النت",
        "شغل وضع الطيران",
        "اضغط على [نص]",
        "اقرا الشاشة"
    )
}
