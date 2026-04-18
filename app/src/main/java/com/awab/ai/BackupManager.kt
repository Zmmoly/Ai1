package com.awab.ai

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupManager {

    private const val BACKUP_DIR  = "AwabAI_Backup"
    private const val BACKUP_FILE = "awab_backup.json"

    // ===== تصدير =====

    /**
     * يجمع كل البيانات ويحفظها في ملف JSON واحد
     * يُرجع مسار الملف عند النجاح أو null عند الفشل
     */
    fun export(context: Context): String? {
        return try {
            val root = JSONObject()

            // 1) الذاكرة العامة
            val memory = context.getSharedPreferences("awab_memory", Context.MODE_PRIVATE)
            root.put("memory", memory.getString("data", "{}"))

            // 2) المشتريات والميزانية والجلسات
            val shopping = context.getSharedPreferences("shopping_prefs", Context.MODE_PRIVATE)
            root.put("shopping_items",    shopping.getString("shopping_items", "[]"))
            root.put("shopping_sessions", shopping.getString("shopping_sessions", "[]"))
            root.put("active_session_id", shopping.getString("active_session_id", "general"))

            // 3) أسماء التطبيقات المخصصة
            val appNames = context.getSharedPreferences("app_names", Context.MODE_PRIVATE)
            root.put("app_names", appNames.getString("custom_names", ""))

            // 4) الأوامر المخصصة
            val commands = context.getSharedPreferences("custom_commands_prefs", Context.MODE_PRIVATE)
            root.put("custom_commands", commands.getString("commands_json", "[]"))

            // 5) وقت النسخ
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            root.put("backup_time", time)

            // حفظ الملف
            val dir  = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), BACKUP_DIR)
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, BACKUP_FILE)
            file.writeText(root.toString(2))

            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    // ===== استيراد =====

    /**
     * يقرأ ملف النسخة الاحتياطية ويعيد كل البيانات
     * يُرجع رسالة النتيجة
     */
    fun import(context: Context): String {
        return try {
            val dir  = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), BACKUP_DIR)
            val file = File(dir, BACKUP_FILE)

            if (!file.exists()) {
                return "⚠️ لم أجد ملف النسخة الاحتياطية\nالمسار: ${file.absolutePath}"
            }

            val root = JSONObject(file.readText())
            var restored = 0

            // 1) الذاكرة العامة
            if (root.has("memory")) {
                context.getSharedPreferences("awab_memory", Context.MODE_PRIVATE)
                    .edit().putString("data", root.getString("memory")).apply()
                restored++
            }

            // 2) المشتريات والجلسات
            if (root.has("shopping_items")) {
                context.getSharedPreferences("shopping_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("shopping_items",    root.getString("shopping_items"))
                    .putString("shopping_sessions", root.optString("shopping_sessions", "[]"))
                    .putString("active_session_id", root.optString("active_session_id", "general"))
                    .apply()
                restored++
            }

            // 3) أسماء التطبيقات المخصصة
            if (root.has("app_names")) {
                context.getSharedPreferences("app_names", Context.MODE_PRIVATE)
                    .edit().putString("custom_names", root.getString("app_names")).apply()
                restored++
            }

            // 4) الأوامر المخصصة
            if (root.has("custom_commands")) {
                context.getSharedPreferences("custom_commands_prefs", Context.MODE_PRIVATE)
                    .edit().putString("commands_json", root.getString("custom_commands")).apply()
                restored++
            }

            val backupTime = root.optString("backup_time", "غير معروف")
            "✅ تم استيراد البيانات بنجاح!\n\n📅 تاريخ النسخة: $backupTime\n📦 $restored مصادر بيانات"

        } catch (e: Exception) {
            "❌ فشل الاستيراد: ${e.message}"
        }
    }

    fun getBackupPath(context: Context): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS), BACKUP_DIR)
        return File(dir, BACKUP_FILE).absolutePath
    }
}
