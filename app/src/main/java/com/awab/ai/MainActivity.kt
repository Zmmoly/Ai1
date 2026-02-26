package com.awab.ai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var scrollView: ScrollView
    private lateinit var rootLayout: LinearLayout
    private lateinit var commandHandler: CommandHandler
    private lateinit var memoryManager: MemoryManager
    private lateinit var micButton: TextView

    private var isRecording = false
    private val RECORD_AUDIO_PERMISSION_CODE = 200
    private val CUSTOM_COMMANDS_REQUEST_CODE = 300

    // مستقبل البث من الخدمة الخلفية
    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AudioRecordingService.ACTION_TEXT_RECOGNIZED -> {
                    val text = intent.getStringExtra(AudioRecordingService.EXTRA_TEXT)
                    val error = intent.getStringExtra(AudioRecordingService.EXTRA_ERROR)
                    if (text != null) {
                        val current = inputField.text.toString()
                        val newText = if (current.isBlank()) text else "$current $text"
                        inputField.setText(newText)
                        inputField.setSelection(newText.length)
                        Toast.makeText(this@MainActivity, "✅ $text", Toast.LENGTH_SHORT).show()
                    } else if (error != null) {
                        Toast.makeText(this@MainActivity, "❌ $error", Toast.LENGTH_SHORT).show()
                        stopRecordingUI()
                    }
                }
                AudioRecordingService.ACTION_RECORDING_STARTED -> runOnUiThread { startRecordingUI() }
                AudioRecordingService.ACTION_RECORDING_STOPPED -> runOnUiThread { stopRecordingUI() }
                AudioRecordingService.ACTION_VOLUME_CHANGED -> {
                    val volume = intent.getFloatExtra(AudioRecordingService.EXTRA_VOLUME, 0f)
                    runOnUiThread { micButton.alpha = (0.5f + volume * 0.5f).coerceIn(0.5f, 1f) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        commandHandler = CommandHandler(this)
        memoryManager = MemoryManager(this)
        ReminderManager.createNotificationChannel(this)

        // تسجيل مستقبل البث
        val filter = IntentFilter().apply {
            addAction(AudioRecordingService.ACTION_TEXT_RECOGNIZED)
            addAction(AudioRecordingService.ACTION_RECORDING_STARTED)
            addAction(AudioRecordingService.ACTION_RECORDING_STOPPED)
            addAction(AudioRecordingService.ACTION_VOLUME_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(recordingReceiver, filter)
        }

        supportActionBar?.hide()

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFFF0F2F5.toInt())
            fitsSystemWindows = true
        }

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isScrollbarFadingEnabled = false
        }

        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(16, 48, 16, 16)
        }

        scrollView.addView(chatContainer)
        rootLayout.addView(scrollView)
        rootLayout.addView(createInputArea())
        setContentView(rootLayout)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemInsets.left, systemInsets.top, systemInsets.right, imeInsets.bottom)
            if (imeInsets.bottom > 0) rootLayout.post { scrollToBottom() }
            WindowInsetsCompat.CONSUMED
        }

        addBotMessage("مرحباً! أنا أواب AI 🤖\n\nكيف يمكني مساعدتك اليوم؟\n\n⚡ يمكنك إنشاء أوامر مخصصة متسلسلة من زر ⚡")
    }

    private fun createInputArea(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(16, 16, 16, 16)
            gravity = Gravity.CENTER_VERTICAL

            inputField = EditText(this@MainActivity).apply {
                hint = "اكتب رسالتك هنا..."
                textSize = 16f
                setPadding(20, 16, 20, 16)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                background = createRoundedBackground(0xFFF0F2F5.toInt(), 24f)
            }
            addView(inputField)

            micButton = TextView(this@MainActivity).apply {
                text = "🎤"
                textSize = 24f
                setTextColor(0xFF075E54.toInt())
                setPadding(16, 0, 0, 0)
                setOnClickListener { toggleRecording() }
            }
            addView(micButton)

            addView(TextView(this@MainActivity).apply {
                text = "➤"
                textSize = 28f
                setTextColor(0xFF075E54.toInt())
                setPadding(16, 0, 0, 0)
                setOnClickListener { sendMessage() }
            })

            addView(TextView(this@MainActivity).apply {
                text = "⚡"
                textSize = 24f
                setTextColor(0xFF075E54.toInt())
                setPadding(16, 0, 0, 0)
                setOnClickListener { openCustomCommands() }
            })

            addView(TextView(this@MainActivity).apply {
                text = "⚙️"
                textSize = 24f
                setTextColor(0xFF075E54.toInt())
                setPadding(16, 0, 0, 0)
                setOnClickListener { openSettings() }
            })
        }
    }

    private fun sendMessage() {
        val message = inputField.text.toString().trim()
        if (message.isEmpty()) return
        addUserMessage(message)
        inputField.text.clear()

        // أبلغ الخدمة بالنص النهائي لتسمية ملف التسجيل
        if (isRecording) {
            val intent = Intent(this, AudioRecordingService::class.java).apply {
                action = AudioRecordingService.ACTION_RENAME_LAST
                putExtra(AudioRecordingService.EXTRA_FINAL_TEXT, message)
            }
            startService(intent)
        }

        android.os.Handler(mainLooper).postDelayed({ handleBotResponse(message) }, 500)
    }

    private fun handleBotResponse(userMessage: String) {
        val lower = userMessage.lowercase().trim()

        // ===== فحص الأوامر المخصصة أولاً =====
        val customCmd = CustomCommandsManager.findByTrigger(this, userMessage)
        if (customCmd != null) {
            addBotMessage("⚡ تنفيذ الأمر المخصص: \"${customCmd.name}\"\n${customCmd.steps.size} خطوات...")
            executeCustomCommand(customCmd, 0)
            return
        }

        // ===== التذكيرات =====

        // عرض التذكيرات المعلقة
        if (lower.contains("اعرض التذكيرات") || lower.contains("كل التذكيرات") ||
            lower.contains("ماهي التذكيرات") || lower.contains("التذكيرات المعلقة")) {
            val pending = ReminderManager.getPendingReminders(this)
            if (pending.isEmpty()) {
                addBotMessage("📭 لا توجد تذكيرات معلقة حالياً.")
            } else {
                val sb = StringBuilder("⏰ التذكيرات المعلقة (${pending.size}):\n\n")
                val now = System.currentTimeMillis()
                pending.forEachIndexed { i, r ->
                    val remaining = r.triggerTimeMs - now
                    sb.appendLine("${i + 1}. 📌 ${r.text}")
                    sb.appendLine("   ⏳ بعد ${ReminderManager.formatDuration(remaining)}")
                }
                addBotMessage(sb.toString().trimEnd())
            }
            return
        }

        // حذف تذكير
        if (lower.startsWith("احذف التذكير") || lower.startsWith("امسح التذكير")) {
            val pending = ReminderManager.getPendingReminders(this)
            if (pending.isEmpty()) {
                addBotMessage("📭 لا توجد تذكيرات لحذفها.")
            } else {
                pending.forEach { ReminderManager.deleteReminder(this, it.id) }
                addBotMessage("🗑️ تم حذف جميع التذكيرات (${pending.size}).")
            }
            return
        }

        // إنشاء تذكير جديد
        val reminderTriggers = listOf("ذكرني", "تذكيرني", "نبهني", "خلني أتذكر")
        if (reminderTriggers.any { lower.startsWith(it) }) {
            val parsed = ReminderManager.parseReminder(userMessage)
            if (parsed != null) {
                val (text, triggerTimeMs) = parsed
                ReminderManager.addReminder(this, text, triggerTimeMs)
                val diff = triggerTimeMs - System.currentTimeMillis()
                addBotMessage(
                    "✅ تم ضبط التذكير!\n\n" +
                    "📌 التذكير: $text\n" +
                    "🗓️ الموعد: ${ReminderManager.formatTriggerTime(triggerTimeMs)}\n\n" +
                    "سأرسل لك إشعاراً عند انتهاء الوقت 🔔"
                )
            } else {
                addBotMessage(
                    "⚠️ لم أفهم الوقت أو التاريخ.\n\n" +
                    "📅 بتاريخ محدد:\n" +
                    "• ذكرني في 25/6 الساعة 9 صباحاً باجتماع\n" +
                    "• ذكرني يوم الجمعة الساعة 3 عصراً بالدواء\n" +
                    "• ذكرني غداً الساعة 8:30 صباحاً\n" +
                    "• ذكرني اليوم الساعة 10 مساءً\n\n" +
                    "⏳ بعد مدة:\n" +
                    "• ذكرني بعد 5 دقائق بشرب الماء\n" +
                    "• ذكرني بعد ساعة باجتماع\n" +
                    "• ذكرني بعد يومين بالدواء"
                )
            }
            return
        }

        // ===== نظام التسوق =====

        // تحديد الميزانية: "ميزانيتي 500" / "عندي 300 للسوق"
        val budgetPatterns = listOf(
            Regex("(?:ميزانيتي|ميزانيت|معي|عندي|بجيبي)\\s+(\\d+(?:\\.\\d+)?)(?:\\s+(?:ر|ريال|ريالات|للسوق))?", RegexOption.IGNORE_CASE),
            Regex("(?:بدي|بدأ|ابدأ)\\s+(?:قايمة|قائمة|تسوق)\\s+ب(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        )
        for (bp in budgetPatterns) {
            val bm = bp.find(userMessage) ?: continue
            val amount = bm.groupValues[1].toDoubleOrNull() ?: continue
            ShoppingManager.saveBudget(this, amount)
            val items = ShoppingManager.loadItems(this)
            val total = ShoppingManager.getTotal(this)
            val remaining = amount - total
            addBotMessage(
                "💼 تم تحديد الميزانية: ${ShoppingManager.formatNum(amount)} ر\n" +
                if (items.isNotEmpty()) "💰 المصروف حتى الآن: ${ShoppingManager.formatNum(total)} ر\n✅ الباقي: ${ShoppingManager.formatNum(remaining)} ر"
                else "🛒 القائمة فارغة — ابدأ بإضافة مشترياتك"
            )
            return
        }

        // إضافة مشتريات: "اشتريت ..."
        val shoppingTriggers = listOf("اشتريت", "أخذت", "اخذت", "جبت", "حصلت على", "شريت")
        if (shoppingTriggers.any { lower.startsWith(it) }) {
            val parsed = ShoppingManager.parsePurchase(userMessage)
            if (parsed != null) {
                // البحث عن السعر في الذاكرة
                val memPriceStr = memoryManager.get("سعر ${parsed.itemName}")
                    ?: memoryManager.get(parsed.itemName)
                val memPrice = memPriceStr?.replace(Regex("[^\\d.]"), "")?.toDoubleOrNull()

                val item = ShoppingManager.buildItem(parsed, memPrice)

                if (item != null) {
                    ShoppingManager.addItem(this, item)
                    val total     = ShoppingManager.getTotal(this)
                    val budget    = ShoppingManager.loadBudget(this)
                    val remaining = if (budget > 0) budget - total else -1.0

                    val qtyStr = if (item.quantity != 1.0)
                        " × ${ShoppingManager.formatNum(item.quantity)}" else ""
                    val sourceStr = if (item.priceSource == "ذاكرة") " (من الذاكرة 🧠)" else ""

                    val sb = StringBuilder()
                    sb.appendLine("✅ تمت الإضافة!")
                    sb.appendLine("🛍️ ${item.name}$qtyStr = ${ShoppingManager.formatNum(item.total)} ر$sourceStr")
                    sb.appendLine()
                    sb.appendLine("💰 الإجمالي الآن: ${ShoppingManager.formatNum(total)} ر")
                    if (remaining >= 0) {
                        sb.appendLine("✅ الباقي: ${ShoppingManager.formatNum(remaining)} ر")
                    } else if (budget > 0) {
                        sb.appendLine("⚠️ تجاوزت الميزانية بـ ${ShoppingManager.formatNum(-remaining)} ر")
                    }
                    addBotMessage(sb.toString().trimEnd())
                } else {
                    // السعر غير موجود — نسأل
                    addBotMessage(
                        "❓ ما سعر ${parsed.itemName}؟\n\n" +
                        "يمكنك قول:\n" +
                        "• اشتريت ${parsed.itemName} بـ [السعر]\n" +
                        "• أو احفظ السعر: سعر ${parsed.itemName} هو [السعر]"
                    )
                }
            } else {
                addBotMessage("⚠️ لم أفهم ماذا اشتريت. جرب: اشتريت تفاح بـ 10")
            }
            return
        }

        // عرض مشتريات بتاريخ محدد
        val historyTriggers = listOf("ماذا اشتريت", "ايش اشتريت", "إيش اشتريت", "وش اشتريت", "شريت إيش", "قايمة")
        if (historyTriggers.any { lower.contains(it) }) {
            // استخرج التاريخ من الجملة
            val dateRange = ShoppingManager.parseDate(userMessage)
            if (dateRange != null) {
                val (start, end) = dateRange
                val items = ShoppingManager.getItemsByDate(this, start, end)

                // استخرج التسمية من الجملة
                val dateLabel = when {
                    lower.contains("اليوم")                          -> "اليوم"
                    lower.contains("امس") || lower.contains("أمس")  -> "أمس"
                    lower.contains("أول امس") || lower.contains("اول امس") -> "أول أمس"
                    lower.contains("الجمعة")   -> "يوم الجمعة"
                    lower.contains("الخميس")   -> "يوم الخميس"
                    lower.contains("الأربعاء") || lower.contains("الاربعاء") -> "يوم الأربعاء"
                    lower.contains("الثلاثاء") -> "يوم الثلاثاء"
                    lower.contains("الاثنين")  -> "يوم الاثنين"
                    lower.contains("الأحد") || lower.contains("الاحد") -> "يوم الأحد"
                    lower.contains("السبت")    -> "يوم السبت"
                    else -> Regex("\\d{1,2}/\\d{1,2}").find(userMessage)?.value ?: "ذلك اليوم"
                }

                addBotMessage(ShoppingManager.formatDateReceipt(this, items, dateLabel))
                return
            }
        }
            lower.contains("اعرض السوق") || lower.contains("الفاتورة") || lower.contains("الحساب")) {
            addBotMessage(ShoppingManager.formatReceipt(this))
            return
        }

        // مسح قائمة التسوق
        if (lower.contains("امسح القايمة") || lower.contains("امسح القائمة") ||
            lower.contains("ابدأ من جديد") || lower.contains("مسح المشتريات")) {
            ShoppingManager.clearItems(this)
            addBotMessage("🗑️ تم مسح قائمة التسوق. جاهز لقائمة جديدة!")
            return
        }

        // الباقي / الإجمالي
        if (lower.contains("كم الباقي") || lower.contains("كم تبقى") || lower.contains("كم صرفت")) {
            val total   = ShoppingManager.getTotal(this)
            val budget  = ShoppingManager.loadBudget(this)
            val items   = ShoppingManager.loadItems(this)
            if (items.isEmpty()) {
                addBotMessage("🛒 لم تشتري أي شيء بعد.")
                return
            }
            val sb = StringBuilder()
            sb.appendLine("💰 الإجمالي المصروف: ${ShoppingManager.formatNum(total)} ر")
            if (budget > 0) {
                val rem = budget - total
                if (rem >= 0) sb.appendLine("✅ الباقي: ${ShoppingManager.formatNum(rem)} ر")
                else sb.appendLine("⚠️ تجاوزت بـ ${ShoppingManager.formatNum(-rem)} ر")
            }
            addBotMessage(sb.toString().trimEnd())
            return
        }

        // ===== نظام الذاكرة =====

        // حفظ معلومة: "تذكر أن ..." / "احفظ أن ..." / "سعر X هو Y"
        val savePatterns = listOf(
            Regex("تذكر(?:\\s+أن|\\s+ان)?\\s+(.+?)\\s+(?:هو|هي|=|يساوي|بسعر|ب)\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("احفظ(?:\\s+أن|\\s+ان)?\\s+(.+?)\\s+(?:هو|هي|=|يساوي|بسعر|ب)\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("سعر\\s+(.+?)\\s+(?:هو|=|يساوي|ب)\\s+(.+)", RegexOption.IGNORE_CASE)
        )

        for (pattern in savePatterns) {
            val match = pattern.find(userMessage)
            if (match != null) {
                val key = match.groupValues[1].trim()
                val value = match.groupValues[2].trim()
                memoryManager.save(key, value)
                addBotMessage("✅ تم الحفظ!\n\n🔑 $key\n💾 $value\n\nيمكنك سؤالي عنه لاحقاً.")
                return
            }
        }

        // استرجاع معلومة: "كم سعر X" / "ما X" / "ذكرني بـ X"
        val getPatterns = listOf(
            Regex("(?:كم|ما|ماهو|ما هو|ماهي|ما هي)\\s+(?:سعر|ثمن|قيمة)?\\s*(.+)", RegexOption.IGNORE_CASE),
            Regex("(?:ذكرني|ذكرني بـ|ذكرني ب|تذكر)\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("(?:اخبرني|أخبرني)\\s+(?:عن|عن سعر)?\\s*(.+)", RegexOption.IGNORE_CASE)
        )

        for (pattern in getPatterns) {
            val match = pattern.find(userMessage)
            if (match != null) {
                val key = match.groupValues[1].trim()
                val value = memoryManager.get(key)
                if (value != null) {
                    addBotMessage("🧠 من الذاكرة:\n\n🔑 $key\n💾 $value")
                    return
                }
            }
        }

        // حذف معلومة: "امسح/احذف X"
        if (lower.startsWith("امسح ") || lower.startsWith("احذف ") || lower.startsWith("امسح معلومة")) {
            val key = userMessage.substringAfter(" ").trim()
            if (memoryManager.delete(key)) {
                addBotMessage("🗑️ تم حذف \"$key\" من الذاكرة.")
            } else {
                addBotMessage("⚠️ لم أجد \"$key\" في الذاكرة.")
            }
            return
        }

        // عرض كل الذاكرة: "ماذا تتذكر" / "اعرض الذاكرة"
        if (lower.contains("ماذا تتذكر") || lower.contains("اعرض الذاكرة") || lower.contains("كل المحفوظات")) {
            val all = memoryManager.getAll()
            if (all.length() == 0) {
                addBotMessage("🧠 الذاكرة فارغة حالياً.\n\nيمكنك قول مثلاً:\n• \"تذكر أن سعر الهاتف هو 500\"\n• \"اشتريت تلفاز بسعر 1200\"")
            } else {
                val sb = StringBuilder("🧠 كل ما أتذكره:\n\n")
                val keys = all.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    sb.append("🔑 $k\n💾 ${all.getString(k)}\n\n")
                }
                addBotMessage(sb.toString().trimEnd())
            }
            return
        }

        // مسح كل الذاكرة
        if (lower.contains("امسح كل الذاكرة") || lower.contains("احذف كل المحفوظات")) {
            memoryManager.clearAll()
            addBotMessage("🗑️ تم مسح كل الذاكرة.")
            return
        }

        // ===== باقي الأوامر الأصلية =====

        val extractedCommands = extractCommandsFromText(userMessage)
        if (extractedCommands.isNotEmpty()) {
            if (extractedCommands.size > 1) {
                addBotMessage("🔄 وجدت ${extractedCommands.size} أوامر، سأنفذها بالترتيب...")
                executeMultipleCommands(extractedCommands, 0)
            } else {
                val response = commandHandler.handleCommand(extractedCommands[0])
                if (response != null) addBotMessage(response)
            }
            return
        }

        val response = when {
            lower.contains("مرحبا") || lower.contains("السلام") || lower.contains("هلا") ->
                "مرحباً بك! 👋\n\nأنا مساعدك الذكي. يمكنني:\n\n🧠 الذاكرة:\n• \"تذكر أن سعر الهاتف هو 500\"\n• \"اشتريت تلفاز بسعر 1200\"\n• \"كم سعر الهاتف؟\"\n• \"ماذا تتذكر؟\"\n\n📱 فتح التطبيقات:\n• افتح [اسم أي تطبيق]\n\n📞 الاتصال:\n• اتصل [اسم أو رقم]"

            lower.contains("كيف") || lower.contains("ساعد") || lower.contains("أوامر") ->
                "📋 الأوامر المتاحة:\n\n🧠 الذاكرة:\n• تذكر أن [شيء] هو [قيمة]\n• اشتريت [شيء] بسعر [قيمة]\n• كم سعر [شيء]؟\n• ذكرني بـ [شيء]\n• ماذا تتذكر؟\n• امسح [شيء]\n\n📱 التطبيقات:\n• افتح [اسم التطبيق]\n• أقفل [اسم التطبيق] ⭐\n\n📞 الاتصال:\n• اتصل ب[اسم]\n• اضرب ل[اسم]\n\n⚙️ الإعدادات:\n• شغل الواي فاي ⭐\n• سكرين شوت ⭐\n\n⭐ = يحتاج Accessibility"

            lower.contains("إعدادات") || lower.contains("settings") -> {
                openSettings()
                "سأفتح لك صفحة الإعدادات..."
            }

            else ->
                "لم أفهم 🤔\n\nجرب:\n• \"أوامر\" - لرؤية كل الأوامر\n• \"افتح واتساب\"\n• \"تذكر أن سعر X هو Y\"\n• \"كم سعر X؟\""
        }

        addBotMessage(response)
    }

    private fun extractCommandsFromText(text: String): List<String> {
        val commands = mutableListOf<String>()
        val commandPatterns = mapOf(
            "open_app" to Regex("(?:افتح|شغل|فتح)\\s+([^،,\\n]+?)(?=\\s*(?:[،,\\n]|ثم|و(?=\\s)|$))", RegexOption.IGNORE_CASE),
            "close_app" to Regex("(?:أقفل|اقفل|سكر)\\s+([^،,\\n]+?)(?=\\s*(?:[،,\\n]|ثم|و(?=\\s)|$))", RegexOption.IGNORE_CASE),
            "call" to Regex("(?:اتصل\\s+ب|اضرب\\s+ل|اتصل|كلم)\\s+([^،,\\n]+?)(?=\\s*(?:[،,\\n]|ثم|و(?=\\s)|$))", RegexOption.IGNORE_CASE),
            "volume" to Regex("(على\\s+الصوت|خفض\\s+الصوت|كتم\\s+الصوت)", RegexOption.IGNORE_CASE),
            "settings" to Regex("(شغل\\s+الواي\\s+فاي|اطفي\\s+الواي\\s+فاي|شغل\\s+البلوتوث|اطفي\\s+البلوتوث)", RegexOption.IGNORE_CASE),
            "system" to Regex("(رجوع|ارجع|back|هوم|home|الشاشة\\s+الرئيسية|recent|التطبيقات\\s+الأخيرة)", RegexOption.IGNORE_CASE),
            "screenshot" to Regex("(سكرين\\s+شوت|لقطة\\s+شاشة|screenshot)", RegexOption.IGNORE_CASE),
            "read_screen" to Regex("(اقرا\\s+الشاشة|ماذا\\s+في\\s+الشاشة)", RegexOption.IGNORE_CASE),
            "click" to Regex("(?:اضغط\\s+على|انقر\\s+على)\\s+(.+?)(?=\\s*(?:[،,\\n]|ثم|و(?=\\s)|$))", RegexOption.IGNORE_CASE),
            "notifications" to Regex("(?:افتح\\s+)?(?:الإشعارات|الاشعارات)", RegexOption.IGNORE_CASE)
        )

        for ((type, pattern) in commandPatterns) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                val fullMatch = match.value.trim()
                val command = when (type) {
                    "open_app" -> "افتح ${match.groupValues.getOrNull(1)?.trim() ?: ""}"
                    "close_app" -> "أقفل ${match.groupValues.getOrNull(1)?.trim() ?: ""}"
                    "call" -> {
                        val contact = match.groupValues.getOrNull(1)?.trim() ?: ""
                        if (fullMatch.contains("اتصل ب", ignoreCase = true)) "اتصل ب$contact"
                        else if (fullMatch.contains("اضرب ل", ignoreCase = true)) "اضرب ل$contact"
                        else fullMatch
                    }
                    "click" -> "اضغط على ${match.groupValues.getOrNull(1)?.trim() ?: ""}"
                    else -> fullMatch
                }
                val response = commandHandler.handleCommand(command)
                if (response != null && !response.contains("لم أفهم الأمر")) {
                    commands.add(command)
                }
            }
        }
        return commands.distinct()
    }

    private fun addUserMessage(message: String) {
        chatContainer.addView(createMessageBubble(message, isUser = true))
        scrollToBottom()
    }

    private fun addBotMessage(message: String) {
        chatContainer.addView(createMessageBubble(message, isUser = false))
        scrollToBottom()
    }

    private fun createMessageBubble(message: String, isUser: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
            gravity = if (isUser) Gravity.END else Gravity.START
            addView(TextView(this@MainActivity).apply {
                text = message
                textSize = 16f
                setPadding(20, 16, 20, 16)
                setTextColor(if (isUser) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
                background = createRoundedBackground(if (isUser) 0xFF075E54.toInt() else 0xFFFFFFFF.toInt(), 16f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                maxWidth = (resources.displayMetrics.widthPixels * 0.75).toInt()
            })
        }
    }

    private fun createRoundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun executeMultipleCommands(commands: List<String>, currentIndex: Int) {
        if (currentIndex >= commands.size) {
            addBotMessage("✅ تم تنفيذ جميع الأوامر!")
            return
        }
        val command = commands[currentIndex]
        addBotMessage("▶️ الأمر ${currentIndex + 1}/${commands.size}: \"$command\"")
        android.os.Handler(mainLooper).postDelayed({
            val response = commandHandler.handleCommand(command)
            addBotMessage(response ?: "⚠️ لم أفهم الأمر: \"$command\"")
            android.os.Handler(mainLooper).postDelayed({
                executeMultipleCommands(commands, currentIndex + 1)
            }, 1500)
        }, 500)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openCustomCommands() {
        val intent = Intent(this, CustomCommandsActivity::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, CUSTOM_COMMANDS_REQUEST_CODE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CUSTOM_COMMANDS_REQUEST_CODE && resultCode == RESULT_OK) {
            val cmdName = data?.getStringExtra("run_custom_command")
            if (!cmdName.isNullOrBlank()) {
                inputField.setText(cmdName)
                sendMessage()
            }
        }
    }

    /**
     * تنفيذ أمر مخصص — يدعم الشروط والحلقات المتداخلة بلا حدود
     */
    private fun executeCustomCommand(cmd: CustomCommand, stepIndex: Int) {
        if (stepIndex >= cmd.steps.size) {
            addBotMessage("✅ تم تنفيذ \"${cmd.name}\" بالكامل!")
            return
        }
        val rawStep = cmd.steps[stepIndex]
        val step = StepEngine.parse(rawStep)
        val delayMs = cmd.delaySeconds * 1000L

        executeStep(step, delayMs) {
            android.os.Handler(mainLooper).postDelayed({
                executeCustomCommand(cmd, stepIndex + 1)
            }, delayMs)
        }
    }

    /**
     * تنفيذ خطوة واحدة (أي نوع) ثم استدعاء onDone عند الانتهاء
     * هذا هو قلب المحرك — يعمل بشكل متكرر بلا حدود
     */
    private fun executeStep(step: Step, delayMs: Long, onDone: () -> Unit) {
        when (step) {

            // ── خطوة عادية ──────────────────────────
            is Step.Normal -> {
                addBotMessage("▶️ ${step.command}")
                android.os.Handler(mainLooper).postDelayed({
                    val result = commandHandler.handleCommand(step.command)
                    addBotMessage(result ?: "⚠️ لم أفهم: \"${step.command}\"")
                    android.os.Handler(mainLooper).postDelayed(onDone, delayMs)
                }, 400)
            }

            // ── سلسلة شروط (بلا حدود) ───────────────
            is Step.IfChain -> {
                evaluateIfChain(step, delayMs, onDone)
            }

            // ── حلقة ────────────────────────────────
            is Step.Loop -> {
                addBotMessage("🔁 حلقة تكرار × ${step.times}")
                executeLoopBody(step.body, step.times, 0, delayMs, onDone)
            }

            // ── انتظار حدث ──────────────────────────
            is Step.Wait -> {
                val dir = if (step.waitForShow) "ظهور" else "اختفاء"
                addBotMessage("⏳ انتظار $dir [${step.targetText}] (مهلة: ${step.timeoutSec}ث)...")

                val service = MyAccessibilityService.getInstance()
                if (service == null) {
                    addBotMessage("⚠️ خدمة إمكانية الوصول غير مفعّلة")
                    onDone()
                    return
                }

                // حل اسم التطبيق: أسماء مخصصة أولاً ثم الخريطة الثابتة
                val resolvedPackage = step.packageName?.let { resolveAppPackage(it) }

                service.registerWaitTask(
                    targetText  = step.targetText,
                    waitForShow = step.waitForShow,
                    timeoutMs   = step.timeoutSec * 1000L,
                    packageName = resolvedPackage,
                    onFound = {
                        addBotMessage("✅ ظهر [${step.targetText}]")
                        if (step.onFound != null) {
                            val foundStep = StepEngine.parse(step.onFound)
                            executeStep(foundStep, delayMs) {
                                android.os.Handler(mainLooper).postDelayed(onDone, delayMs)
                            }
                        } else {
                            android.os.Handler(mainLooper).postDelayed(onDone, delayMs)
                        }
                    },
                    onTimeout = {
                        addBotMessage("⏰ انتهت المهلة بدون ظهور [${step.targetText}]")
                        if (step.onTimeout != null) {
                            val timeoutStep = StepEngine.parse(step.onTimeout)
                            executeStep(timeoutStep, delayMs) {
                                android.os.Handler(mainLooper).postDelayed(onDone, delayMs)
                            }
                        } else {
                            onDone()
                        }
                    }
                )
            }
        }
    }

    /**
     * يحل اسم التطبيق إلى package name
     * يبحث في الأسماء المخصصة أولاً ثم الخريطة الثابتة في StepEngine
     */
    private fun resolveAppPackage(nameOrPackage: String): String {
        val lower = nameOrPackage.lowercase().trim()

        // 1) إذا يبدو كـ package name فعلي (يحتوي نقطة) → استخدمه مباشرة
        if (lower.contains(".")) return nameOrPackage

        // 2) ابحث في الأسماء المخصصة (packageName → [أسماء])
        val customNames = AppNamesActivity.getCustomNames(this)
        for ((pkg, names) in customNames) {
            if (names.any { it.lowercase() == lower }) return pkg
        }

        // 3) ابحث في الخريطة الثابتة
        return StepEngine.resolvePackage(nameOrPackage)
    }

    /**
     * تقييم سلسلة الشروط:
     * يتحقق من كل فرع بالترتيب حتى يجد شرطاً صحيحاً
     * ثم ينفّذ خطواته ← recursively
     */
    private fun evaluateIfChain(chain: Step.IfChain, delayMs: Long, onDone: () -> Unit) {
        evaluateBranches(chain.branches, 0, chain.elseBranch, delayMs, onDone)
    }

    private fun evaluateBranches(
        branches: List<Step.IfChain.Branch>,
        index: Int,
        elseBranch: List<Step>?,
        delayMs: Long,
        onDone: () -> Unit
    ) {
        // لا يوجد فرع مطابق → نفّذ وإلا أو تخطّ
        if (index >= branches.size) {
            if (elseBranch != null) {
                addBotMessage("↩ وإلا")
                executeStepList(elseBranch, delayMs, onDone)
            } else {
                addBotMessage("⏭️ لا شرط تحقق، تخطي...")
                onDone()
            }
            return
        }

        val branch = branches[index]
        android.os.Handler(mainLooper).postDelayed({
            val result = StepEngine.evaluateCondition(branch.condition)
            if (result) {
                addBotMessage("✅ تحقق: [${branch.condition}]")
                executeStepList(branch.steps, delayMs, onDone)
            } else {
                addBotMessage("❌ لم يتحقق: [${branch.condition}]")
                // جرّب الفرع التالي
                android.os.Handler(mainLooper).postDelayed({
                    evaluateBranches(branches, index + 1, elseBranch, delayMs, onDone)
                }, 300)
            }
        }, 400)
    }

    /**
     * تنفيذ قائمة خطوات بالتسلسل ثم استدعاء onDone
     */
    private fun executeStepList(steps: List<Step>, delayMs: Long, onDone: () -> Unit) {
        fun next(i: Int) {
            if (i >= steps.size) { onDone(); return }
            executeStep(steps[i], delayMs) {
                android.os.Handler(mainLooper).postDelayed({ next(i + 1) }, delayMs)
            }
        }
        next(0)
    }

    /**
     * تنفيذ جسم الحلقة N مرة
     */
    private fun executeLoopBody(
        body: List<Step>, total: Int, current: Int, delayMs: Long, onDone: () -> Unit
    ) {
        if (current >= total) {
            addBotMessage("✅ انتهت الحلقة ($total مرات)")
            onDone()
            return
        }
        addBotMessage("🔁 تكرار ${current + 1}/$total")
        executeStepList(body, delayMs) {
            android.os.Handler(mainLooper).postDelayed({
                executeLoopBody(body, total, current + 1, delayMs, onDone)
            }, delayMs)
        }
    }

    // ============================
    // إدارة التسجيل عبر الخدمة الخلفية
    // ============================

    private fun toggleRecording() {
        if (isRecording) stopRecordingService() else startRecordingService()
    }

    private fun startRecordingService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
            return
        }
        val intent = Intent(this, AudioRecordingService::class.java).apply {
            action = AudioRecordingService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopRecordingService() {
        startService(Intent(this, AudioRecordingService::class.java).apply {
            action = AudioRecordingService.ACTION_STOP
        })
    }

    private fun startRecordingUI() {
        isRecording = true
        micButton.text = "⏹️"
        micButton.setTextColor(0xFFDC3545.toInt())
        inputField.hint = "🎤 يستمع في الخلفية..."
    }

    private fun stopRecordingUI() {
        isRecording = false
        micButton.text = "🎤"
        micButton.setTextColor(0xFF075E54.toInt())
        micButton.alpha = 1f
        inputField.hint = "اكتب رسالتك هنا..."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecordingService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(recordingReceiver) } catch (e: Exception) { /* تجاهل */ }
        // الخدمة تبقى تعمل في الخلفية حتى يضغط المستخدم إيقاف
    }
}
