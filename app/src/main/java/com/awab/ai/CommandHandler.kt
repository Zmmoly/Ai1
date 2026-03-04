package com.awab.ai

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast

class CommandHandler(private val context: Context) {

    /**
     * معالجة أوامر متعددة في رسالة واحدة
     * الأوامر يمكن أن تكون مفصولة بـ: و، ثم، ،
     */
    fun handleMultipleCommands(rawMessage: String): List<String> {
        val message = rawMessage.normalizeNumbers()
        // فصل الأوامر بناءً على الفواصل
        val separators = listOf(" و ", " ثم ", "،", ",", "\n")
        var commands = listOf(message)
        
        // فصل بناءً على كل فاصل
        for (separator in separators) {
            commands = commands.flatMap { it.split(separator) }
        }
        
        // تنفيذ كل أمر وجمع النتائج
        val results = mutableListOf<String>()
        
        for ((index, command) in commands.withIndex()) {
            val trimmedCommand = command.trim()
            if (trimmedCommand.isNotEmpty()) {
                val result = handleCommand(trimmedCommand)
                
                // إضافة رقم الأمر إذا كان هناك أكثر من أمر
                if (commands.size > 1) {
                    results.add("${index + 1}. $result")
                } else {
                    results.add(result)
                }
                
                // انتظار بسيط بين الأوامر (500ms)
                if (index < commands.size - 1) {
                    Thread.sleep(500)
                }
            }
        }
        
        return results
    }

    fun handleCommand(rawMessage: String): String {
        val message = rawMessage.normalizeNumbers()
        val lowerMessage = message.lowercase()

        return when {
            // عرض كل التطبيقات المثبتة
            lowerMessage.contains("اعرض التطبيقات") || 
            lowerMessage.contains("كل التطبيقات") || 
            lowerMessage.contains("قائمة التطبيقات") ||
            lowerMessage == "list apps" -> {
                listInstalledApps()
            }

            // فتح تطبيق
            lowerMessage.startsWith("افتح") || lowerMessage.startsWith("شغل تطبيق") -> {
                val appName = message.substringAfter("افتح").substringAfter("شغل تطبيق").trim()
                openApp(appName)
            }

            // اتصال - بجميع الصيغ
            lowerMessage.startsWith("اتصل ب") -> {
                val contactName = message.substringAfter("اتصل ب").trim()
                makeCall(contactName)
            }

            lowerMessage.startsWith("اضرب ل") -> {
                val contactName = message.substringAfter("اضرب ل").trim()
                makeCall(contactName)
            }

            lowerMessage.startsWith("اتصل") || lowerMessage.startsWith("كلم") -> {
                val contactName = message.substringAfter("اتصل").substringAfter("كلم").trim()
                makeCall(contactName)
            }

            // إغلاق تطبيق (يستخدم Accessibility)
            lowerMessage.startsWith("أقفل") || lowerMessage.startsWith("اقفل") -> {
                val appName = message.substringAfter("أقفل").substringAfter("اقفل").trim()
                closeApp(appName)
            }

            // رجوع
            lowerMessage.contains("رجوع") || lowerMessage.contains("ارجع") || lowerMessage == "back" -> {
                performBack()
            }

            // الشاشة الرئيسية
            lowerMessage.contains("الشاشة الرئيسية") || lowerMessage.contains("هوم") || lowerMessage == "home" -> {
                performHome()
            }

            // Recent Apps
            lowerMessage.contains("التطبيقات الأخيرة") || lowerMessage == "recent" || lowerMessage == "recents" -> {
                performRecents()
            }

            // الإشعارات
            lowerMessage.contains("افتح الإشعارات") || lowerMessage.contains("الاشعارات") -> {
                performNotifications()
            }

            // واي فاي (بطريقتين)
            lowerMessage.contains("شغل الوايفاي") || lowerMessage.contains("شغل wifi") -> {
                toggleWifi(true)
            }

            lowerMessage.contains("اطفي الوايفاي") || lowerMessage.contains("اطفئ wifi") -> {
                toggleWifi(false)
            }

            // بلوتوث (بطريقتين)
            lowerMessage.contains("شغل البلوتوث") || lowerMessage.contains("شغل bluetooth") -> {
                toggleBluetooth(true)
            }

            lowerMessage.contains("اطفي البلوتوث") || lowerMessage.contains("اطفئ bluetooth") -> {
                toggleBluetooth(false)
            }

            // بيانات الجوال
            lowerMessage.contains("شغل النت") || lowerMessage.contains("شغل البيانات") -> {
                openMobileDataSettings("لتشغيل بيانات الجوال")
            }

            lowerMessage.contains("اطفي النت") || lowerMessage.contains("اطفئ البيانات") -> {
                openMobileDataSettings("لإطفاء بيانات الجوال")
            }

            // وضع الطيران
            lowerMessage.contains("شغل وضع الطيران") || lowerMessage.contains("airplane mode") -> {
                openAirplaneModeSettings("لتشغيل وضع الطيران")
            }

            // نقطة اتصال
            lowerMessage.contains("شغل نقطة اتصال") || lowerMessage.contains("هوت سبوت") || lowerMessage.contains("hotspot") -> {
                openHotspotSettings()
            }

            // قلب الشاشة (تدوير)
            lowerMessage.contains("قلب الشاشة") || lowerMessage.contains("دور الشاشة") -> {
                openRotationSettings()
            }

            // سكرين شوت (يستخدم Accessibility)
            lowerMessage.contains("سكرين شوت") || lowerMessage.contains("لقطة شاشة") || lowerMessage.contains("screenshot") -> {
                takeScreenshot()
            }

            // الصوت
            lowerMessage.contains("على الصوت") || lowerMessage.contains("ارفع الصوت") || lowerMessage.contains("زود الصوت") -> {
                increaseVolume()
            }

            lowerMessage.contains("خفض الصوت") || lowerMessage.contains("قلل الصوت") || lowerMessage.contains("نزل الصوت") -> {
                decreaseVolume()
            }

            lowerMessage.contains("كتم الصوت") || lowerMessage.contains("اسكت") -> {
                muteVolume()
            }

            // قراءة الشاشة
            lowerMessage.contains("اقرا الشاشة") || lowerMessage.contains("ماذا في الشاشة") || lowerMessage == "read screen" -> {
                readScreen()
            }

            // الضغط على عنصر بالنص
            lowerMessage.startsWith("اضغط على") || lowerMessage.startsWith("انقر على") -> {
                val text = message.substringAfter("اضغط على").substringAfter("انقر على").trim()
                clickOnText(text)
            }

            // مراقبة تطبيق: "راقب يوتيوب 5 مرات → انتظر ظهور [تخطي] ثم اضغط على تخطي"
            lowerMessage.startsWith("راقب ") -> {
                parseAndStartWatch(message)
            }

            // إيقاف المراقبة: "أوقف مراقبة يوتيوب"
            lowerMessage.startsWith("أوقف مراقبة ") || lowerMessage.startsWith("اوقف مراقبة ") ||
            lowerMessage.startsWith("أوقف المراقبة") || lowerMessage.startsWith("اوقف المراقبة") -> {
                stopWatch(message)
            }

            // عرض المراقبات النشطة
            lowerMessage.contains("المراقبات") || lowerMessage.contains("مراقبات نشطة") -> {
                listWatches()
            }

            else -> null
        } ?: "لم أفهم الأمر. جرب:\n• افتح [اسم التطبيق]\n• اتصل [اسم أو رقم]\n• اتصل ب[اسم]\n• اضرب ل[اسم]\n• شغل الواي فاي\n• سكرين شوت\n• على الصوت\n• رجوع\n• اقرا الشاشة"
    }

    // ===== دوال المراقبة =====

    /**
     * يحلل أمر المراقبة ويبدأها
     * الصيغة: راقب [تطبيق] [N] مرات → انتظر ظهور [نص] ثم اضغط على نص
     */
    private fun parseAndStartWatch(message: String): String {
        val service = MyAccessibilityService.getInstance()
            ?: return "⚠️ يجب تفعيل خدمة إمكانية الوصول"

        // راقب يوتيوب 5 مرات → انتظر ظهور [تخطي] ثم اضغط على تخطي
        val pattern = Regex(
            "^راقب\s+([\w\u0600-\u06FF]+)" +
            "(?:\s+(\d+)\s*مر(?:ات?|ه))?" +
            "\s*[→\->:]\s*" +
            "انتظر\s+ظهور\s+\[(.+?)\]" +
            "(?:\s+ثم\s+(.+))?$",
            RegexOption.IGNORE_CASE
        )

        val m = pattern.find(message.trim()) ?: return "⚠️ صيغة غير صحيحة. مثال:\nراقب يوتيوب 5 مرات → انتظر ظهور [تخطي] ثم اضغط على تخطي"

        val appName     = m.groupValues[1].trim()
        val countStr    = m.groupValues[2].trim()
        val targetText  = m.groupValues[3].trim()
        val actionCmd   = m.groupValues[4].trim()
        val repeatCount = if (countStr.isBlank()) 1 else countStr.toIntOrNull() ?: 1

        // حل اسم التطبيق
        val pkg = StepEngine.resolvePackage(appName)

        val watchId = service.registerWatchTask(
            packageName  = pkg,
            targetText   = targetText,
            repeatCount  = repeatCount
        ) {
            // عند العثور على النص — نفّذ الإجراء
            if (actionCmd.isNotBlank()) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    handleCommand(actionCmd)
                }
            }
        }

        val countLabel = if (repeatCount == 1) "مرة واحدة" else "$repeatCount مرات"
        return "👁️ بدأت مراقبة [$appName] — سأبحث عن [$targetText] وأنفذ [$actionCmd] × $countLabel\n\nللإيقاف: أوقف مراقبة $appName\n(معرف المراقبة: #$watchId)"
    }

    /** إيقاف مراقبة تطبيق */
    private fun stopWatch(message: String): String {
        val service = MyAccessibilityService.getInstance()
            ?: return "⚠️ خدمة إمكانية الوصول غير مفعّلة"

        val lower = message.lowercase()
        val appName = lower
            .removePrefix("أوقف مراقبة").removePrefix("اوقف مراقبة")
            .removePrefix("أوقف المراقبة").removePrefix("اوقف المراقبة")
            .trim()

        if (appName.isBlank()) {
            // إيقاف كل المراقبات
            val watches = service.getActiveWatchTasks()
            if (watches.isEmpty()) return "لا توجد مراقبات نشطة"
            watches.forEach { service.cancelWatchTask(it.id) }
            return "🛑 تم إيقاف جميع المراقبات (${watches.size})"
        }

        val pkg = StepEngine.resolvePackage(appName)
        val count = service.cancelWatchTasksByPackage(pkg)
        return if (count > 0)
            "🛑 تم إيقاف مراقبة [$appName] ($count مهمة)"
        else
            "⚠️ لا توجد مراقبة نشطة لـ [$appName]"
    }

    /** عرض المراقبات النشطة */
    private fun listWatches(): String {
        val service = MyAccessibilityService.getInstance()
            ?: return "⚠️ خدمة إمكانية الوصول غير مفعّلة"

        val watches = service.getActiveWatchTasks()
        if (watches.isEmpty()) return "📭 لا توجد مراقبات نشطة حالياً"

        val sb = StringBuilder("👁️ المراقبات النشطة:\n\n")
        watches.forEach { w ->
            val count = if (w.remainingCount == -1) "∞" else "${w.remainingCount}"
            sb.appendLine("#${w.id} | ${w.packageName} | [${w.targetText}] | متبقي: $count")
        }
        return sb.toString().trimEnd()
    }

    private fun openApp(appName: String): String {
        if (appName.isBlank()) {
            return "أي تطبيق تريد أن تفتح؟"
        }

        // أولاً: جرب الأسماء المخصصة
        val customNames = AppNamesActivity.getCustomNames(context)
        for ((packageName, names) in customNames) {
            if (names.any { it.equals(appName, ignoreCase = true) }) {
                // استخدم الاسم المخصص الذي تطابق
                val matchedName = names.first { it.equals(appName, ignoreCase = true) }
                return launchApp(packageName, matchedName)
            }
        }

        // ثانياً: قائمة التطبيقات الشائعة مع Package Names
        val commonApps = mapOf(
            "واتساب" to "com.whatsapp",
            "whatsapp" to "com.whatsapp",
            "واتس اب" to "com.whatsapp",
            "انستقرام" to "com.instagram.android",
            "instagram" to "com.instagram.android",
            "انستا" to "com.instagram.android",
            "فيسبوك" to "com.facebook.katana",
            "facebook" to "com.facebook.katana",
            "فيس بوك" to "com.facebook.katana",
            "تويتر" to "com.twitter.android",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "يوتيوب" to "com.google.android.youtube",
            "youtube" to "com.google.android.youtube",
            "تيك توك" to "com.zhiliaoapp.musically",
            "tiktok" to "com.zhiliaoapp.musically",
            "سناب شات" to "com.snapchat.android",
            "snapchat" to "com.snapchat.android",
            "سناب" to "com.snapchat.android",
            "تليجرام" to "org.telegram.messenger",
            "telegram" to "org.telegram.messenger",
            "كروم" to "com.android.chrome",
            "chrome" to "com.android.chrome",
            "متصفح" to "com.android.chrome",
            "الكاميرا" to "com.android.camera",
            "camera" to "com.android.camera",
            "كاميرا" to "com.android.camera",
            "المعرض" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos",
            "معرض" to "com.google.android.apps.photos",
            "صور" to "com.google.android.apps.photos",
            "الإعدادات" to "com.android.settings",
            "settings" to "com.android.settings",
            "اعدادات" to "com.android.settings",
            "جيميل" to "com.google.android.gm",
            "gmail" to "com.google.android.gm",
            "خرائط" to "com.google.android.apps.maps",
            "maps" to "com.google.android.apps.maps",
            "خرائط جوجل" to "com.google.android.apps.maps",
            "تطبيق الرسائل" to "com.google.android.apps.messaging",
            "رسائل" to "com.google.android.apps.messaging",
            "messages" to "com.google.android.apps.messaging"
        )

        val lowerAppName = appName.lowercase()
        val packageName = commonApps[lowerAppName]
        
        // جرب فتح التطبيق من القائمة الشائعة
        if (packageName != null) {
            return launchApp(packageName, appName)
        }

        // إذا لم يكن في القائمة، ابحث في كل التطبيقات المثبتة
        return searchAndLaunchApp(appName)
    }

    private fun launchApp(packageName: String, appName: String): String {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "✅ تم فتح $appName"
            } else {
                return "❌ التطبيق $appName غير مثبت على جهازك"
            }
        } catch (e: Exception) {
            return "❌ خطأ في فتح $appName: ${e.message}"
        }
    }

    private fun searchAndLaunchApp(appName: String): String {
        try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            
            // الحصول على كل التطبيقات المثبتة
            val allApps = pm.queryIntentActivities(intent, 0)
            
            // البحث عن التطبيق بالاسم
            val lowerSearchName = appName.lowercase()
            val matchingApps = allApps.filter { resolveInfo ->
                val appLabel = resolveInfo.loadLabel(pm).toString().lowercase()
                appLabel.contains(lowerSearchName) || lowerSearchName.contains(appLabel)
            }

            return when {
                matchingApps.isEmpty() -> {
                    "❌ لم أجد تطبيقاً باسم \"$appName\"\n\n💡 نصائح:\n• تأكد من كتابة الاسم بشكل صحيح\n• جرب اسم أقصر (مثل: \"واتس\" بدلاً من \"واتساب\")\n• التطبيق يجب أن يكون مثبتاً"
                }
                matchingApps.size == 1 -> {
                    // وجد تطبيق واحد فقط - افتحه
                    val app = matchingApps[0]
                    val foundAppName = app.loadLabel(pm).toString()
                    val packageName = app.activityInfo.packageName
                    launchApp(packageName, foundAppName)
                }
                else -> {
                    // وجد أكثر من تطبيق - اعرض القائمة
                    val appList = matchingApps.take(5).joinToString("\n") { 
                        "• ${it.loadLabel(pm)}"
                    }
                    "🔍 وجدت ${matchingApps.size} تطبيق بهذا الاسم:\n\n$appList\n\n💡 جرب اسم أكثر تحديداً"
                }
            }
        } catch (e: Exception) {
            return "❌ خطأ في البحث عن التطبيق: ${e.message}"
        }
    }

    private fun makeCall(contactName: String): String {
        if (contactName.isBlank()) {
            return "من تريد أن تتصل به؟"
        }

        // إذا كان رقم - اتصال مباشر
        val contactNorm = contactName.normalizeNumbers()
        if (contactNorm.matches(Regex("^[0-9+]+$"))) {
            try {
                val intent = Intent(Intent.ACTION_CALL).apply {  // ← تم التغيير من ACTION_DIAL إلى ACTION_CALL
                    data = Uri.parse("tel:$contactName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return "📞 اتصال مباشر بـ $contactName..."
            } catch (e: SecurityException) {
                return "⚠️ يجب منح إذن CALL_PHONE من الإعدادات"
            } catch (e: Exception) {
                return "❌ خطأ في الاتصال: ${e.message}"
            }
        }

        // إذا كان اسم جهة اتصال - البحث في جهات الاتصال
        return searchContactAndCall(contactName)
    }

    private fun searchContactAndCall(contactName: String): String {
        try {
            // البحث عن جهة الاتصال
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$contactName%"),
                null
            )

            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                
                val foundName = cursor.getString(nameIndex)
                val phoneNumber = cursor.getString(numberIndex)
                cursor.close()

                // الاتصال المباشر بالرقم المعثور عليه
                try {
                    val intent = Intent(Intent.ACTION_CALL).apply {  // ← تم التغيير من ACTION_DIAL إلى ACTION_CALL
                        data = Uri.parse("tel:$phoneNumber")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    
                    return "📞 اتصال مباشر بـ $foundName\n📱 $phoneNumber"
                } catch (e: SecurityException) {
                    return "⚠️ يجب منح إذن CALL_PHONE من الإعدادات"
                } catch (e: Exception) {
                    return "❌ خطأ في الاتصال: ${e.message}"
                }
            } else {
                cursor?.close()
                return "❌ لم أجد جهة اتصال باسم \"$contactName\"\n\nيمكنك:\n• كتابة الرقم مباشرة\n• التأكد من الاسم الصحيح"
            }
        } catch (e: SecurityException) {
            return "⚠️ يجب منح إذن الوصول لجهات الاتصال\n\nانتقل إلى: الإعدادات ⚙️ → طلب الأذونات"
        } catch (e: Exception) {
            return "❌ خطأ في البحث عن جهة الاتصال: ${e.message}"
        }
    }

    private fun closeApp(appName: String): String {
        if (appName.isBlank()) {
            return "أي تطبيق تريد أن تغلق؟"
        }

        val service = MyAccessibilityService.getInstance()
        
        return if (service != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val success = service.closeAppByName(appName)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (success) {
                        Toast.makeText(context, "✅ تم إغلاق $appName", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "⚠️ لم أجد $appName في التطبيقات الأخيرة", Toast.LENGTH_SHORT).show()
                    }
                }
            }, 100)
            "🔄 جاري إغلاق $appName...\n\nسأفتح Recent Apps وأبحث عن التطبيق"
        } else {
            "⚠️ يجب تفعيل خدمة إمكانية الوصول\n\n✅ خطوات التفعيل:\n1. اضغط على ⚙️ في الأسفل\n2. اضغط \"فتح إعدادات إمكانية الوصول\"\n3. فعّل \"أواب AI\""
        }
    }

    private fun openWifiSettings(action: String): String {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "✅ فتح إعدادات الواي فاي $action"
        } catch (e: Exception) {
            return "❌ خطأ في فتح إعدادات الواي فاي"
        }
    }

    private fun openBluetoothSettings(action: String): String {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "✅ فتح إعدادات البلوتوث $action"
        } catch (e: Exception) {
            return "❌ خطأ في فتح إعدادات البلوتوث"
        }
    }

    private fun openMobileDataSettings(action: String): String {
        try {
            val intent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "✅ فتح إعدادات البيانات $action"
        } catch (e: Exception) {
            return "❌ خطأ في فتح إعدادات البيانات"
        }
    }

    private fun openAirplaneModeSettings(action: String): String {
        try {
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "✅ فتح إعدادات الطيران $action"
        } catch (e: Exception) {
            return "❌ خطأ في فتح إعدادات الطيران"
        }
    }

    private fun openHotspotSettings(): String {
        try {
            val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "✅ فتح إعدادات نقطة الاتصال"
        } catch (e: Exception) {
            return "❌ خطأ في فتح إعدادات نقطة الاتصال"
        }
    }

    private fun openRotationSettings(): String {
        try {
            val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "✅ فتح إعدادات الشاشة لتغيير التدوير"
        } catch (e: Exception) {
            return "❌ خطأ في فتح إعدادات الشاشة"
        }
    }

    private fun takeScreenshot(): String {
        val service = MyAccessibilityService.getInstance()
        
        return if (service != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                service.takeScreenshot { success ->
                    if (success) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(context, "✅ تم أخذ السكرين شوت!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(context, "❌ فشل أخذ السكرين شوت", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                "📸 جاري أخذ سكرين شوت..."
            } else {
                "⚠️ ميزة السكرين شوت تحتاج Android 11+\n\nجهازك: Android ${Build.VERSION.SDK_INT}\n\nيمكنك أخذ سكرين شوت بالضغط على:\n• زر الباور + خفض الصوت"
            }
        } else {
            "⚠️ يجب تفعيل خدمة إمكانية الوصول من الإعدادات أولاً\n\nانتقل إلى: الإعدادات ⚙️ → فتح إعدادات إمكانية الوصول"
        }
    }

    private fun increaseVolume(): String {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI
            )
            return "✅ تم رفع الصوت 🔊"
        } catch (e: Exception) {
            return "❌ خطأ في رفع الصوت"
        }
    }

    private fun decreaseVolume(): String {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            return "✅ تم خفض الصوت 🔉"
        } catch (e: Exception) {
            return "❌ خطأ في خفض الصوت"
        }
    }

    private fun muteVolume(): String {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_MUTE,
                AudioManager.FLAG_SHOW_UI
            )
            return "✅ تم كتم الصوت 🔇"
        } catch (e: Exception) {
            return "❌ خطأ في كتم الصوت"
        }
    }

    // ===== وظائف Accessibility المتقدمة =====

    private fun performBack(): String {
        val service = MyAccessibilityService.getInstance()
        
        return if (service != null) {
            if (service.performBack()) {
                "✅ تم الضغط على زر الرجوع"
            } else {
                "❌ فشل الضغط على زر الرجوع"
            }
        } else {
            "⚠️ يجب تفعيل خدمة إمكانية الوصول من الإعدادات"
        }
    }

    private fun performHome(): String {
        val service = MyAccessibilityService.getInstance()
        
        return if (service != null) {
            if (service.performHome()) {
                "✅ الذهاب للشاشة الرئيسية"
            } else {
                "❌ فشل الذهاب للشاشة الرئيسية"
            }
        } else {
            "⚠️ يجب تفعيل خدمة إمكانية الوصول من الإعدادات"
        }
    }

    private fun performRecents(): String {
        val service = MyAccessibilityService.getInstance()
        
        return if (service != null) {
            if (service.performRecents()) {
                "✅ فتح التطبيقات الأخيرة"
            } else {
                "❌ فشل فتح التطبيقات الأخيرة"
            }
        } else {
            "⚠️ يجب تفعيل خدمة إمكانية الوصول من الإعدادات"
        }
    }

    private fun performNotifications(): String {
        val service = MyAccessibilityService.getInstance()
        
        return if (service != null) {
            if (service.performNotifications()) {
                "✅ فتح الإشعارات"
            } else {
                "❌ فشل فتح الإشعارات"
            }
        } else {
            "⚠️ يجب تفعيل خدمة إمكانية الوصول من الإعدادات"
        }
    }

    private fun toggleWifi(enable: Boolean): String {
        val service = MyAccessibilityService.getInstance()
        
        return if (service != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                service.toggleWifiFromQuickSettings()
            }
            if (enable) {
                "✅ فتح الإعدادات السريعة للواي فاي\nاضغط على زر الواي فاي لتشغيله"
            } else {
                "✅ فتح الإعدادات السريعة للواي فاي\nاضغط على زر الواي فاي لإطفائه"
            }
        } else {
            // Fallback للطريقة القديمة
            openWifiSettings(if (enable) "لتشغيل الواي فاي" else "لإطفاء الواي فاي")
        }
    }

    private fun toggleBluetooth(enable: Boolean): String {
        val service = MyAccessibilityService.getInstance()
        
        return if (service != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                service.toggleBluetoothFromQuickSettings()
            }
            if (enable) {
                "✅ فتح الإعدادات السريعة للبلوتوث\nاضغط على زر البلوتوث لتشغيله"
            } else {
                "✅ فتح الإعدادات السريعة للبلوتوث\nاضغط على زر البلوتوث لإطفائه"
            }
        } else {
            // Fallback للطريقة القديمة
            openBluetoothSettings(if (enable) "لتشغيل البلوتوث" else "لإطفاء البلوتوث")
        }
    }

    private fun readScreen(): String {
        val service = MyAccessibilityService.getInstance()
        
        return if (service != null) {
            val screenText = service.getScreenText()
            if (screenText.isNotBlank()) {
                "📖 محتوى الشاشة:\n\n$screenText"
            } else {
                "⚠️ لا يوجد نص في الشاشة الحالية"
            }
        } else {
            "⚠️ يجب تفعيل خدمة إمكانية الوصول من الإعدادات"
        }
    }

    private fun clickOnText(text: String): String {
        if (text.isBlank()) {
            return "على أي شيء تريد الضغط؟"
        }

        val service = MyAccessibilityService.getInstance()
        
        return if (service != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                service.clickByText(text)
            }
            "✅ جاري البحث والضغط على \"$text\""
        } else {
            "⚠️ يجب تفعيل خدمة إمكانية الوصول من الإعدادات"
        }
    }

    private fun listInstalledApps(): String {
        try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            
            val allApps = pm.queryIntentActivities(intent, 0)
                .map { it.loadLabel(pm).toString() }
                .sorted()
                .distinct()
            
            return if (allApps.isNotEmpty()) {
                val appCount = allApps.size
                val appList = allApps.take(20).joinToString("\n") { "• $it" }
                val more = if (appCount > 20) "\n\n... و ${appCount - 20} تطبيق آخر" else ""
                
                "📱 التطبيقات المثبتة (${appCount} تطبيق):\n\n$appList$more\n\n💡 استخدم: افتح [اسم التطبيق]"
            } else {
                "❌ لم أجد أي تطبيقات"
            }
        } catch (e: Exception) {
            return "❌ خطأ في عرض التطبيقات: ${e.message}"
        }
    }
}
