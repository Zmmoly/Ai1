package com.awab.ai

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(
    val id: Int,
    val text: String,           // نص التذكير
    val triggerTimeMs: Long,    // وقت التنفيذ بالميلي ثانية
    val createdAt: Long = System.currentTimeMillis()
)

object ReminderManager {

    private const val PREFS_NAME = "reminders_prefs"
    private const val KEY_REMINDERS = "reminders_json"
    const val CHANNEL_ID = "reminders_channel"
    const val ACTION_REMINDER = "com.awab.ai.REMINDER"
    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_REMINDER_TEXT = "reminder_text"

    // ===== حفظ وتحميل =====

    fun saveReminders(context: Context, reminders: List<Reminder>) {
        val arr = JSONArray()
        for (r in reminders) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("text", r.text)
                put("triggerTimeMs", r.triggerTimeMs)
                put("createdAt", r.createdAt)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_REMINDERS, arr.toString()).apply()
    }

    fun loadReminders(context: Context): MutableList<Reminder> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REMINDERS, "[]") ?: "[]"
        val result = mutableListOf<Reminder>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    Reminder(
                        id = obj.getInt("id"),
                        text = obj.getString("text"),
                        triggerTimeMs = obj.getLong("triggerTimeMs"),
                        createdAt = obj.optLong("createdAt", 0)
                    )
                )
            }
        } catch (e: Exception) { e.printStackTrace() }
        return result
    }

    fun addReminder(context: Context, text: String, triggerTimeMs: Long): Reminder {
        val reminders = loadReminders(context)
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val reminder = Reminder(id = id, text = text, triggerTimeMs = triggerTimeMs)
        reminders.add(reminder)
        saveReminders(context, reminders)
        scheduleAlarm(context, reminder)
        return reminder
    }

    fun deleteReminder(context: Context, id: Int) {
        val reminders = loadReminders(context)
        reminders.find { it.id == id }?.let { cancelAlarm(context, it) }
        reminders.removeAll { it.id == id }
        saveReminders(context, reminders)
    }

    fun getPendingReminders(context: Context): List<Reminder> {
        val now = System.currentTimeMillis()
        return loadReminders(context).filter { it.triggerTimeMs > now }
    }

    // ===== جدولة AlarmManager =====

    fun scheduleAlarm(context: Context, reminder: Reminder) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            putExtra(EXTRA_REMINDER_TEXT, reminder.text)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.triggerTimeMs,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.triggerTimeMs,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.triggerTimeMs,
                pendingIntent
            )
        }
    }

    fun cancelAlarm(context: Context, reminder: Reminder) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    // ===== إنشاء قناة الإشعارات =====

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تذكيرات أواب AI",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "تذكيرات من المساعد الذكي"
                enableVibration(true)
                enableLights(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    // ===== تحليل النص =====

    /**
     * النقطة الرئيسية: يحلل الجملة ويُرجع (نص التذكير، وقت التشغيل بالميلي ثانية)
     *
     * يدعم نوعين:
     * 1. مدة نسبية:  "ذكرني بعد 5 دقائق بشرب الماء"
     * 2. تاريخ/وقت محدد: "ذكرني في 25/6 الساعة 9 باجتماع"
     *                     "ذكرني يوم الجمعة الساعة 3 عصراً بالدواء"
     *                     "ذكرني غداً الساعة 8 الصبح"
     *                     "ذكرني بكرة الساعة 10:30 بالاجتماع"
     */
    fun parseReminder(input: String): Pair<String, Long>? {
        val lower = input.lowercase().trim()

        // استخراج نص التذكير (الجزء بعد كلمة "بـ" في نهاية الجملة)
        fun extractText(after: String): String {
            val bPattern = Regex("\\s+ب(?:ـ)?\\s*(.+)$")
            return bPattern.find(after)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() } ?: "تذكير!"
        }

        val triggers = listOf("ذكرني", "تذكيرني", "نبهني", "خلني أتذكر", "خلني اتذكر")

        for (trigger in triggers) {
            if (!lower.startsWith(trigger)) continue
            val rest = lower.removePrefix(trigger).trim()

            // --- نوع 1: "بعد [مدة]" ---
            if (rest.startsWith("بعد ")) {
                val afterBaad = rest.removePrefix("بعد ").trim()
                // فصل المدة عن نص التذكير
                val bIdx = afterBaad.lastIndexOf(" ب")
                val durationText = if (bIdx > 0) afterBaad.substring(0, bIdx).trim() else afterBaad
                val reminderText = extractText(" $afterBaad")
                val durationMs = parseDuration(durationText) ?: continue
                return Pair(reminderText, System.currentTimeMillis() + durationMs)
            }

            // --- نوع 2: تاريخ/وقت محدد ---
            val triggerMs = parseDateTime(rest) ?: continue
            val reminderText = extractText(rest)
            return Pair(reminderText, triggerMs)
        }
        return null
    }

    /**
     * تحليل التاريخ والوقت المحدد من النص
     *
     * أمثلة مدعومة:
     * - "في 25/6 الساعة 9"
     * - "في 25/6/2025 الساعة 10:30"
     * - "يوم الجمعة الساعة 3 عصراً"
     * - "الجمعة الساعة 8 مساءً"
     * - "غداً الساعة 7 صباحاً"
     * - "بكرة الساعة 10"
     * - "اليوم الساعة 5"
     * - "الساعة 9 مساءً"  (اليوم)
     */
    fun parseDateTime(text: String): Long? {
        val lower = text.lowercase().trim()
        val cal = java.util.Calendar.getInstance()

        // ===== استخراج الوقت (الساعة والدقائق) =====
        var hour = -1
        var minute = 0
        var isPM: Boolean? = null

        // الساعة HH:MM أو H:MM
        val timeColonPattern = Regex("الساعة\\s+(\\d{1,2}):(\\d{2})(?:\\s*(صباحاً|صباح|ص|مساءً|مساء|م|عصراً|عصر|ليلاً|ليل))?")
        val timeColonMatch = timeColonPattern.find(lower)
        if (timeColonMatch != null) {
            hour = timeColonMatch.groupValues[1].toInt()
            minute = timeColonMatch.groupValues[2].toInt()
            isPM = parsePeriod(timeColonMatch.groupValues[3])
        }

        // الساعة H (بدون دقائق)
        if (hour == -1) {
            val timeSimplePattern = Regex("الساعة\\s+(\\d{1,2})(?:\\s*(صباحاً|صباح|ص|مساءً|مساء|م|عصراً|عصر|ليلاً|ليل))?")
            val timeSimpleMatch = timeSimplePattern.find(lower)
            if (timeSimpleMatch != null) {
                hour = timeSimpleMatch.groupValues[1].toInt()
                isPM = parsePeriod(timeSimpleMatch.groupValues[2])
            }
        }

        // لا يوجد وقت محدد → نفشل (نحتاج وقتاً للتاريخ المحدد)
        if (hour == -1) return null

        // تحويل 12h → 24h
        if (isPM == true && hour < 12) hour += 12
        if (isPM == false && hour == 12) hour = 0

        // ===== استخراج اليوم =====

        // "اليوم" أو "الساعة X" (بدون يوم = اليوم)
        if (lower.contains("اليوم") ||
            (!lower.contains("غداً") && !lower.contains("غدا") &&
             !lower.contains("بكرة") && !lower.contains("بكره") &&
             !lower.contains("يوم") && !lower.contains("في ") &&
             !lower.contains("الأحد") && !lower.contains("الاثنين") &&
             !lower.contains("الثلاثاء") && !lower.contains("الأربعاء") &&
             !lower.contains("الخميس") && !lower.contains("الجمعة") &&
             !lower.contains("السبت"))) {
            cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
            cal.set(java.util.Calendar.MINUTE, minute)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            // إذا الوقت فات → اليوم القادم
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        // "غداً" أو "بكرة"
        if (lower.contains("غداً") || lower.contains("غدا") ||
            lower.contains("بكرة") || lower.contains("بكره")) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
            cal.set(java.util.Calendar.MINUTE, minute)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        // أيام الأسبوع
        val dayOfWeek = parseDayOfWeek(lower)
        if (dayOfWeek != null) {
            val today = cal.get(java.util.Calendar.DAY_OF_WEEK)
            var diff = dayOfWeek - today
            if (diff <= 0) diff += 7  // الأسبوع القادم إذا فات
            cal.add(java.util.Calendar.DAY_OF_YEAR, diff)
            cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
            cal.set(java.util.Calendar.MINUTE, minute)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        // تاريخ رقمي: "في 25/6" أو "في 25/6/2025"
        val datePattern = Regex("في\\s+(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?")
        val dateMatch = datePattern.find(lower)
        if (dateMatch != null) {
            val day = dateMatch.groupValues[1].toInt()
            val month = dateMatch.groupValues[2].toInt() - 1  // Calendar: 0-based
            val yearRaw = dateMatch.groupValues[3]
            val year = when {
                yearRaw.length == 4 -> yearRaw.toInt()
                yearRaw.length == 2 -> 2000 + yearRaw.toInt()
                else -> cal.get(java.util.Calendar.YEAR)
            }
            cal.set(year, month, day, hour, minute, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        // تاريخ: "25 يونيو" أو "الخامس والعشرين من يونيو"
        val monthName = parseMonthName(lower)
        if (monthName != null) {
            val dayNum = extractDayNumber(lower)
            if (dayNum != null) {
                val year = cal.get(java.util.Calendar.YEAR)
                cal.set(year, monthName, dayNum, hour, minute, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                // إذا فات → السنة القادمة
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    cal.add(java.util.Calendar.YEAR, 1)
                }
                return cal.timeInMillis
            }
        }

        return null
    }

    private fun parsePeriod(text: String): Boolean? {
        return when {
            text.contains("مساء") || text.contains("م") && text.length == 1
                    || text.contains("عصر") || text.contains("ليل") -> true
            text.contains("صباح") || text.contains("ص") && text.length == 1 -> false
            else -> null
        }
    }

    private fun parseDayOfWeek(lower: String): Int? {
        return when {
            lower.contains("الأحد") || lower.contains("احد") -> java.util.Calendar.SUNDAY
            lower.contains("الاثنين") || lower.contains("اثنين") -> java.util.Calendar.MONDAY
            lower.contains("الثلاثاء") || lower.contains("ثلاثاء") -> java.util.Calendar.TUESDAY
            lower.contains("الأربعاء") || lower.contains("اربعاء") -> java.util.Calendar.WEDNESDAY
            lower.contains("الخميس") || lower.contains("خميس") -> java.util.Calendar.THURSDAY
            lower.contains("الجمعة") || lower.contains("جمعة") || lower.contains("جمعه") -> java.util.Calendar.FRIDAY
            lower.contains("السبت") || lower.contains("سبت") -> java.util.Calendar.SATURDAY
            else -> null
        }
    }

    private fun parseMonthName(lower: String): Int? {
        // Calendar: يناير=0 ... ديسمبر=11
        return when {
            lower.contains("يناير") || lower.contains("جانفي") -> 0
            lower.contains("فبراير") || lower.contains("فيفري") -> 1
            lower.contains("مارس") -> 2
            lower.contains("أبريل") || lower.contains("ابريل") || lower.contains("نيسان") -> 3
            lower.contains("مايو") || lower.contains("ماي") -> 4
            lower.contains("يونيو") || lower.contains("يونو") || lower.contains("جوان") -> 5
            lower.contains("يوليو") || lower.contains("يوليه") || lower.contains("جويلية") -> 6
            lower.contains("أغسطس") || lower.contains("اغسطس") || lower.contains("أوت") -> 7
            lower.contains("سبتمبر") || lower.contains("سبتمبار") -> 8
            lower.contains("أكتوبر") || lower.contains("اكتوبر") -> 9
            lower.contains("نوفمبر") || lower.contains("نوفمبار") -> 10
            lower.contains("ديسمبر") || lower.contains("ديسمبار") || lower.contains("دجنبر") -> 11
            else -> null
        }
    }

    private fun extractDayNumber(lower: String): Int? {
        val numPattern = Regex("(\\d{1,2})\\s*(?:يناير|فبراير|مارس|أبريل|ابريل|مايو|يونيو|يوليو|أغسطس|سبتمبر|أكتوبر|نوفمبر|ديسمبر)")
        return numPattern.find(lower)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * تحويل النص الزمني (مدة نسبية) لميلي ثانية
     */
    fun parseDuration(text: String): Long? {
        val lower = text.lowercase().trim()

        val specialMap = mapOf(
            "ثانية" to 1_000L,
            "دقيقة" to 60_000L,
            "ربع ساعة" to 15 * 60_000L,
            "نص ساعة" to 30 * 60_000L,
            "نصف ساعة" to 30 * 60_000L,
            "ساعة" to 3_600_000L,
            "ساعتين" to 2 * 3_600_000L,
            "يوم" to 86_400_000L,
            "يومين" to 2 * 86_400_000L,
            "أسبوع" to 7 * 86_400_000L,
            "اسبوع" to 7 * 86_400_000L
        )

        for ((key, ms) in specialMap) {
            if (lower == key || (lower.endsWith(key) && lower.substringBefore(key).isBlank())) {
                return ms
            }
        }

        val numPattern = Regex("(\\d+(?:\\.\\d+)?)\\s*(ثانية|ثواني|دقيقة|دقيقه|دقائق|ساعة|ساعه|ساعات|يوم|أيام|ايام)")
        val match = numPattern.find(lower) ?: return null
        val num = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2]

        return when {
            unit.contains("ثان") -> (num * 1_000).toLong()
            unit.contains("دقيق") -> (num * 60_000).toLong()
            unit.contains("ساع") -> (num * 3_600_000).toLong()
            unit.contains("يوم") || unit.contains("أيام") || unit.contains("ايام") ->
                (num * 86_400_000).toLong()
            else -> null
        }
    }

    /**
     * تنسيق وقت العرض (يُستخدم في رسالة التأكيد)
     */
    fun formatTriggerTime(triggerMs: Long): String {
        val now = System.currentTimeMillis()
        val diff = triggerMs - now
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = triggerMs }
        val dayNames = arrayOf("", "الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
        val dayName = dayNames[cal.get(java.util.Calendar.DAY_OF_WEEK)]
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val min = cal.get(java.util.Calendar.MINUTE)
        val timeStr = String.format("%02d:%02d", hour, min)
        val period = if (hour < 12) "صباحاً" else "مساءً"
        return "$dayName الساعة $timeStr $period (بعد ${formatDuration(diff)})"
    }

    /**
     * تنسيق المدة لعرضها بشكل مقروء
     */
    fun formatDuration(ms: Long): String {
        return when {
            ms < 60_000 -> "${ms / 1000} ثانية"
            ms < 3_600_000 -> "${ms / 60_000} دقيقة"
            ms < 86_400_000 -> {
                val hours = ms / 3_600_000
                val mins = (ms % 3_600_000) / 60_000
                if (mins > 0) "$hours ساعة و$mins دقيقة" else "$hours ساعة"
            }
            else -> {
                val days = ms / 86_400_000
                val hours = (ms % 86_400_000) / 3_600_000
                if (hours > 0) "$days يوم و$hours ساعة" else "$days يوم"
            }
        }
    }
}
