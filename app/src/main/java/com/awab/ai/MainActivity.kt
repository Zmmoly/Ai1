package com.awab.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
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
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var memoryManager: MemoryManager
    private lateinit var micButton: TextView

    private var isRecording = false
    private val RECORD_AUDIO_PERMISSION_CODE = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        commandHandler = CommandHandler(this)
        speechRecognizer = SpeechRecognizer(this)
        memoryManager = MemoryManager(this)
        setupSpeechRecognizer()

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

        addBotMessage("مرحباً! أنا أواب AI 🤖\n\nكيف يمكني مساعدتك اليوم؟")
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
        android.os.Handler(mainLooper).postDelayed({ handleBotResponse(message) }, 500)
    }

    private fun handleBotResponse(userMessage: String) {
        val lower = userMessage.lowercase().trim()

        // ===== نظام الذاكرة =====

        // حفظ معلومة: "تذكر أن ..." / "احفظ أن ..." / "سعر X هو Y"
        val savePatterns = listOf(
            Regex("تذكر(?:\\s+أن|\\s+ان)?\\s+(.+?)\\s+(?:هو|هي|=|يساوي|بسعر|ب)\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("احفظ(?:\\s+أن|\\s+ان)?\\s+(.+?)\\s+(?:هو|هي|=|يساوي|بسعر|ب)\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("سعر\\s+(.+?)\\s+(?:هو|=|يساوي|ب)\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("اشتريت\\s+(.+?)\\s+(?:ب|بسعر)\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("دفعت\\s+(.+?)\\s+(?:على|لـ|ل)\\s+(.+)", RegexOption.IGNORE_CASE)
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

    // ========== Speech Recognition ==========

    private fun setupSpeechRecognizer() {
        speechRecognizer.setListener(object : SpeechRecognizer.RecognitionListener {
            override fun onTextRecognized(text: String) {
                runOnUiThread {
                    val current = inputField.text.toString()
                    val newText = if (current.isBlank()) text else "$current $text"
                    inputField.setText(newText)
                    inputField.setSelection(newText.length)
                    Toast.makeText(this@MainActivity, "✅ تم التعرف: $text", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "❌ $error", Toast.LENGTH_SHORT).show()
                    stopRecordingUI()
                }
            }
            override fun onRecordingStarted() { runOnUiThread { startRecordingUI() } }
            override fun onRecordingStopped() { runOnUiThread { stopRecordingUI() } }
            override fun onVolumeChanged(volume: Float) {
                runOnUiThread { micButton.alpha = (0.5f + volume * 0.5f).coerceIn(0.5f, 1f) }
            }
            override fun onModelLoaded(modelName: String) {}
        })
    }

    private fun toggleRecording() {
        if (isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
            return
        }
        speechRecognizer.startRecording()
    }

    private fun stopRecording() { speechRecognizer.stopRecording() }

    private fun startRecordingUI() {
        isRecording = true
        micButton.text = "⏹️"
        micButton.setTextColor(0xFFDC3545.toInt())
        inputField.hint = "🎤 جاري التسجيل..."
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
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.cleanup()
    }
}
