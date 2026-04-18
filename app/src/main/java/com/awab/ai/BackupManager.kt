package com.awab.ai

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * نموذج بيانات الأمر المخصص
 */
data class CustomCommand(
    val id: String,
    val name: String,
    val description: String,
    val steps: List<String>,
    val delaySeconds: Int = 2
)

object CustomCommandsManager {

    private const val PREFS_NAME   = "custom_commands_prefs"
    private const val KEY_COMMANDS = "commands_json"   // مفتاح موحّد

    // ملف خارجي يبقى بعد حذف التطبيق (Documents/AwabAI_Backup/)
    private const val BACKUP_DIR = "AwabAI_Backup"
    private const val AUTO_FILE  = "custom_commands_auto.json"

    // ===== الملف الخارجي =====

    private fun getAutoFile(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            BACKUP_DIR
        )
        if (!dir.exists()) dir.mkdirs()
        return File(dir, AUTO_FILE)
    }

    private fun saveToExternalFile(commands: List<CustomCommand>) {
        try { getAutoFile().writeText(buildJsonArray(commands).toString(2)) }
        catch (_: Exception) { }
    }

    private fun loadFromExternalFile(): MutableList<CustomCommand>? {
        return try {
            val file = getAutoFile()
            if (!file.exists()) null else parseJsonArray(JSONArray(file.readText()))
        } catch (_: Exception) { null }
    }

    // ===== بناء / تحليل JSON =====

    private fun buildJsonArray(commands: List<CustomCommand>): JSONArray {
        val arr = JSONArray()
        for (cmd in commands) {
            val obj = JSONObject().apply {
                put("id", cmd.id)
                put("name", cmd.name)
                put("description", cmd.description)
                put("delaySeconds", cmd.delaySeconds)
                val stepsArr = JSONArray()
                cmd.steps.forEach { stepsArr.put(it) }
                put("steps", stepsArr)
            }
            arr.put(obj)
        }
        return arr
    }

    private fun parseJsonArray(arr: JSONArray): MutableList<CustomCommand> {
        val result = mutableListOf<CustomCommand>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val stepsArr = obj.getJSONArray("steps")
            val steps = (0 until stepsArr.length()).map { stepsArr.getString(it) }
            result.add(
                CustomCommand(
                    id           = obj.getString("id"),
                    name         = obj.getString("name"),
                    description  = obj.optString("description", ""),
                    steps        = steps,
                    delaySeconds = obj.optInt("delaySeconds", 2)
                )
            )
        }
        return result
    }

    // ===== حفظ وتحميل =====

    fun saveCommands(context: Context, commands: List<CustomCommand>) {
        val json = buildJsonArray(commands).toString()

        // 1) SharedPreferences
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_COMMANDS, json).apply()

        // 2) ملف خارجي — يبقى بعد حذف التطبيق أو مسح بياناته
        saveToExternalFile(commands)
    }

    /**
     * تحميل بالأولوية:
     *   1. SharedPreferences (إن وُجدت)
     *   2. الملف الخارجي    (عند إعادة التثبيت)
     *   3. قائمة فارغة
     */
    fun loadCommands(context: Context): MutableList<CustomCommand> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COMMANDS, null)

        if (!json.isNullOrEmpty() && json != "[]") {
            return try { parseJsonArray(JSONArray(json)) }
            catch (_: Exception) { mutableListOf() }
        }

        // SharedPreferences فارغة → جرّب الملف الخارجي
        val fromFile = loadFromExternalFile()
        if (!fromFile.isNullOrEmpty()) {
            saveCommands(context, fromFile)   // أعد الكتابة في SharedPreferences
            return fromFile
        }

        return mutableListOf()
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
        val idx  = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) list[idx] = updated
        saveCommands(context, list)
    }

    fun findByTrigger(context: Context, input: String): CustomCommand? {
        val lower = input.lowercase().trim()
        return loadCommands(context).firstOrNull { it.name.lowercase().trim() == lower }
    }

    fun generateId(): String = System.currentTimeMillis().toString()

    fun parseStepsToStepList(rawSteps: List<String>): List<Step> {
        return parseBlock(rawSteps, 0).first
    }

    private val loopStartRegex = Regex(
        "^(?:ابدأ|ابدا|بدأ|بدا)\\s+حلقة\\s+(\\d+)\\s*(?:مرات?|مره)?\$",
        RegexOption.IGNORE_CASE
    )

    private fun parseBlock(lines: List<String>, startIndex: Int): Pair<List<Step>, Int> {
        val steps = mutableListOf<Step>()
        var i = startIndex
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isBlank()) { i++; continue }

            if (line.matches(Regex("^(?:انهي|انهِ|أنهي|انتهي)\\s+حلقة\$", RegexOption.IGNORE_CASE))) {
                return Pair(steps, i + 1)
            }

            val loopMatch = loopStartRegex.matchEntire(line)
            if (loopMatch != null) {
                val times = loopMatch.groupValues[1].toIntOrNull()?.coerceIn(1, 100) ?: 1
                val (bodySteps, nextIndex) = parseBlock(lines, i + 1)
                steps.add(Step.Loop(times, bodySteps))
                i = nextIndex
                continue
            }

            val parsedLine = StepEngine.parse(line)
            if (parsedLine is Step.ItemList) {
                val remainingLines = lines.subList(i + 1, lines.size).filter { it.trim().isNotBlank() }
                steps.add(parsedLine.copy(bodySteps = remainingLines.map { StepEngine.parse(it.trim()) }))
                return Pair(steps, lines.size)
            }

            if (parsedLine is Step.ForEach) {
                val remainingLines = lines.subList(i + 1, lines.size).filter { it.trim().isNotBlank() }
                steps.add(parsedLine.copy(bodySteps = remainingLines.map { StepEngine.parse(it.trim()) }))
                return Pair(steps, lines.size)
            }

            steps.add(StepEngine.parse(line))
            i++
        }
        return Pair(steps, i)
    }

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
        "اقرا الشاشة",
        "انتظر [N] ثانية",
        "قائمة نصوص: [نص1] | [نص2] | [نص3]",
        "قائمة روابط: [رابط1] | [رابط2]"
    )
}
