package com.awab.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MyAccessibilityService : AccessibilityService() {

    // ===== نظام مهام الانتظار =====

    /**
     * مهمة انتظار حدث على الشاشة
     * @param targetText   النص المطلوب ظهوره أو اختفاؤه
     * @param waitForShow  true = انتظر ظهور / false = انتظر اختفاء
     * @param timeoutMs    مهلة الانتظار بالميلي ثانية (0 = بلا حدود)
     * @param onFound      يُستدعى عند تحقق الشرط
     * @param onTimeout    يُستدعى عند انتهاء المهلة بدون تحقق
     */
    data class WaitTask(
        val id: Int,
        val targetText: String,
        val waitForShow: Boolean,
        val timeoutMs: Long,
        val onFound: () -> Unit,
        val onTimeout: () -> Unit
    )

    private val waitTasks = mutableListOf<WaitTask>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var nextTaskId = 0

    // ===== نظام المراقبة المستمرة =====

    /**
     * مهمة مراقبة مستمرة لتطبيق معين
     * تنتظر فتح التطبيق → تبحث عن نص → تنفذ إجراء → تكرر
     *
     * @param packageName  الحزمة المراقبة
     * @param targetText   النص المنتظر
     * @param repeatCount  عدد مرات التنفيذ (-1 = بلا حدود)
     * @param onTrigger    الإجراء عند العثور على النص
     */
    data class WatchTask(
        val id: Int,
        val packageName: String,
        val targetText: String,
        var remainingCount: Int,        // -1 = بلا حدود
        val onTrigger: () -> Unit,
        var appIsOpen: Boolean = false  // هل التطبيق مفتوح حالياً
    )

    private val watchTasks = mutableListOf<WatchTask>()
    private val hasWatchTasks = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * يسجّل مهمة انتظار جديدة
     * يُرجع ID المهمة (لإلغائها إذا لزم)
     */
    fun registerWaitTask(
        targetText: String,
        waitForShow: Boolean = true,
        timeoutMs: Long = 10_000L,
        packageName: String? = null,   // null = التطبيق الحالي تلقائياً
        onFound: () -> Unit,
        onTimeout: () -> Unit = {}
    ): Int {
        val id = nextTaskId++
        val task = WaitTask(id, targetText, waitForShow, timeoutMs, onFound, onTimeout)

        synchronized(waitTasks) {
            waitTasks.add(task)
            hasActiveTasks.set(true)
        }

        // استخدم packageName المحدد أو التطبيق الحالي تلقائياً
        val pkg = packageName ?: rootInActiveWindow?.packageName?.toString() ?: ""
        setListenPackage(pkg)

        // ضبط timeout إذا كانت المهلة محددة
        if (timeoutMs > 0) {
            mainHandler.postDelayed({
                val removed = synchronized(waitTasks) {
                    val r = waitTasks.removeAll { it.id == id }
                    hasActiveTasks.set(waitTasks.isNotEmpty())
                    if (waitTasks.isEmpty()) setListenPackage(null)
                    r
                }
                if (removed) {
                    Log.d(TAG, "⏰ انتهت مهلة انتظار: \"$targetText\"")
                    onTimeout()
                }
            }, timeoutMs)
        }

        Log.d(TAG, "⏳ مهمة انتظار #$id: \"$targetText\" في $pkg")
        return id
    }

    /** إلغاء مهمة انتظار بالـ ID */
    fun cancelWaitTask(id: Int) {
        synchronized(waitTasks) {
            waitTasks.removeAll { it.id == id }
            hasActiveTasks.set(waitTasks.isNotEmpty())
        }
    }

    /** إلغاء جميع مهام الانتظار */
    fun cancelAllWaitTasks() {
        synchronized(waitTasks) {
            waitTasks.clear()
            hasActiveTasks.set(false)
        }
    }

    /**
     * يسجّل مهمة مراقبة مستمرة
     * يُرجع ID المهمة
     */
    fun registerWatchTask(
        packageName: String,
        targetText: String,
        repeatCount: Int = 1,
        onTrigger: () -> Unit
    ): Int {
        val id = nextTaskId++
        val task = WatchTask(
            id            = id,
            packageName   = packageName,
            targetText    = targetText,
            remainingCount = repeatCount,
            onTrigger     = onTrigger
        )
        synchronized(watchTasks) {
            watchTasks.add(task)
            hasWatchTasks.set(true)
        }
        updateListenPackages()
        Log.d(TAG, "👁️ مراقبة #$id: [$targetText] في $packageName × $repeatCount")
        return id
    }

    /** إلغاء مهمة مراقبة بالـ ID */
    fun cancelWatchTask(id: Int) {
        synchronized(watchTasks) {
            watchTasks.removeAll { it.id == id }
            hasWatchTasks.set(watchTasks.isNotEmpty())
        }
        updateListenPackages()
        Log.d(TAG, "🛑 إلغاء مراقبة #$id")
    }

    /** إلغاء كل مهام مراقبة تطبيق معين */
    fun cancelWatchTasksByPackage(packageName: String): Int {
        var count = 0
        synchronized(watchTasks) {
            count = watchTasks.count { it.packageName == packageName }
            watchTasks.removeAll { it.packageName == packageName }
            hasWatchTasks.set(watchTasks.isNotEmpty())
        }
        updateListenPackages()
        return count
    }

    /** قائمة المراقبات النشطة */
    fun getActiveWatchTasks(): List<WatchTask> {
        return synchronized(watchTasks) { watchTasks.toList() }
    }

    /**
     * يحدّث قائمة الحزم المستمَع لها بناءً على المهام النشطة
     * يجمع حزم WaitTasks و WatchTasks معاً
     */
    private fun updateListenPackages() {
        val info = serviceInfo ?: return
        val allPackages = mutableSetOf<String>()

        synchronized(waitTasks) {
            // نأخذ packageNames من serviceInfo الحالي (WaitTasks تضبطه)
        }
        synchronized(watchTasks) {
            watchTasks.forEach { allPackages.add(it.packageName) }
        }

        // نبقي الاستماع فعالاً دائماً حتى تكون rootInActiveWindow و windows
        // محدَّثتين في أي وقت تُستدعى فيه getScreenText()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                          AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (allPackages.isEmpty() && !hasActiveTasks.get()) {
            info.packageNames = null  // null = استمع لكل التطبيقات
        } else {
            info.packageNames = allPackages.toTypedArray()
        }
        serviceInfo = info
    }

    // ===== معالجة أحداث الشاشة =====

    private val hasActiveTasks = java.util.concurrent.atomic.AtomicBoolean(false)

    // ===== كشف فتح التطبيقات =====
    private var lastOpenedPackage: String = ""
    var onAppOpened: ((packageName: String) -> Unit)? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return

        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val root = rootInActiveWindow ?: return
        val currentPkg = root.packageName?.toString() ?: return

        // ===== كشف فتح تطبيق جديد =====
        if (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            currentPkg != lastOpenedPackage &&
            currentPkg != "com.awab.ai") {
            lastOpenedPackage = currentPkg
            mainHandler.post { onAppOpened?.invoke(currentPkg) }
        }

        // ===== معالجة WaitTasks =====
        if (hasActiveTasks.get()) {
            val tasks = synchronized(waitTasks) { waitTasks.toList() }
            val toRemove = mutableListOf<WaitTask>()

            for (task in tasks) {
                val nodes = root.findAccessibilityNodeInfosByText(task.targetText)
                val found = !nodes.isNullOrEmpty()
                when {
                    task.waitForShow && found -> {
                        toRemove.add(task)
                        Log.d(TAG, "✅ ظهر #${task.id}: \"${task.targetText}\"")
                        mainHandler.post { task.onFound() }
                    }
                    !task.waitForShow && !found -> {
                        toRemove.add(task)
                        Log.d(TAG, "✅ اختفى #${task.id}: \"${task.targetText}\"")
                        mainHandler.post { task.onFound() }
                    }
                }
                nodes?.forEach { it.recycle() }
            }

            if (toRemove.isNotEmpty()) {
                synchronized(waitTasks) {
                    waitTasks.removeAll(toRemove.toSet())
                    hasActiveTasks.set(waitTasks.isNotEmpty())
                }
                updateListenPackages()
            }
        }

        // ===== معالجة WatchTasks =====
        if (hasWatchTasks.get()) {
            val watches = synchronized(watchTasks) { watchTasks.toList() }
            val toRemove = mutableListOf<WatchTask>()

            for (watch in watches) {
                // تحديث حالة التطبيق (مفتوح/مغلق)
                if (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    watch.appIsOpen = (currentPkg == watch.packageName)
                }

                // لا نبحث إلا إذا التطبيق مفتوح
                if (!watch.appIsOpen && currentPkg != watch.packageName) continue

                watch.appIsOpen = true

                val nodes = root.findAccessibilityNodeInfosByText(watch.targetText)
                val found = !nodes.isNullOrEmpty()
                nodes?.forEach { it.recycle() }

                if (found) {
                    Log.d(TAG, "👁️ مراقبة #${watch.id}: وجد [${watch.targetText}] في ${watch.packageName}")
                    mainHandler.post { watch.onTrigger() }

                    if (watch.remainingCount > 0) {
                        watch.remainingCount--
                        if (watch.remainingCount == 0) {
                            toRemove.add(watch)
                            Log.d(TAG, "👁️ مراقبة #${watch.id}: اكتمل العدد")
                        }
                    }
                    // remainingCount = -1 → تكرار بلا حدود، لا نحذف
                }
            }

            if (toRemove.isNotEmpty()) {
                synchronized(watchTasks) {
                    watchTasks.removeAll(toRemove.toSet())
                    hasWatchTasks.set(watchTasks.isNotEmpty())
                }
                updateListenPackages()
            }
        }
    }

    /**
     * يضبط التطبيق الذي نستمع له
     * null = لا نستمع لأحد (وضع صامت)
     */
    private fun setListenPackage(packageName: String?) {
        // نستدعي updateListenPackages لضمان دمج WaitTasks و WatchTasks
        updateListenPackages()
        Log.d(TAG, if (packageName != null) "👂 أستمع لـ $packageName" else "🔇 وضع صامت")
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
        cancelAllWaitTasks()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        context = applicationContext
        setListenPackage(null)  // ابدأ في وضع صامت
        Log.d(TAG, "Accessibility Service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAllWaitTasks()
        instance = null
        Log.d(TAG, "Accessibility Service destroyed")
    }

    // ===== وظائف متقدمة =====

    /**
     * الضغط على عنصر بناءً على النص — بإحداثيات عشوائية داخل العنصر
     */
    fun clickByText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val targetNode = findNodeByText(rootNode, text) ?: return false

        val bounds = Rect()
        targetNode.getBoundsInScreen(bounds)
        targetNode.recycle()

        if (bounds.isEmpty) return false

        // إحداثيات عشوائية داخل حدود العنصر (مع هامش 20% من الحواف)
        val marginX = (bounds.width() * 0.2f).toInt().coerceAtLeast(2)
        val marginY = (bounds.height() * 0.2f).toInt().coerceAtLeast(2)

        val randomX = (bounds.left + marginX + (Math.random() * (bounds.width() - marginX * 2))).toFloat()
        val randomY = (bounds.top + marginY + (Math.random() * (bounds.height() - marginY * 2))).toFloat()

        return performClick(randomX, randomY)
    }

    /**
     * البحث عن عنصر بالنص
     */
    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        // البحث في العقدة الحالية
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }

        // البحث في العناصر الأبناء
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByText(child, text)
            if (result != null) {
                // وجدنا النتيجة — أعد recycle للـ child إن لم يكن هو النتيجة
                if (result != child) child.recycle()
                return result
            }
            child.recycle()
        }

        return null
    }

    /**
     * قراءة نص من حقل نصي
     */
    fun readTextField(fieldId: String): String {
        val rootNode = rootInActiveWindow ?: return ""
        val targetNode = findNodeById(rootNode, fieldId)
        
        return if (targetNode != null) {
            val text = targetNode.text?.toString() ?: ""
            targetNode.recycle()
            text
        } else {
            ""
        }
    }

    /**
     * البحث عن عنصر بالـ ID
     */
    private fun findNodeById(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.contains(id) == true) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeById(child, id)
            if (result != null) {
                if (result != child) child.recycle()
                return result
            }
            child.recycle()
        }

        return null
    }

    /**
     * البحث عن كل العناصر من نوع معين (مثل Button, EditText, ImageView)
     * تُرجع قائمة — المستدعي مسؤول عن recycle لكل عنصر فيها
     */
    fun findAllByClass(node: AccessibilityNodeInfo, className: String): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()

        if (node.className?.contains(className, ignoreCase = true) == true) {
            results.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childResults = findAllByClass(child, className)
            results.addAll(childResults)
            // أعد recycle للـ child فقط إذا لم يكن ضمن النتائج
            if (!childResults.contains(child)) child.recycle()
        }

        return results
    }

    /**
     * البحث عن أول عنصر يحقق شرطاً معيناً
     * مثال: findByProperty(root) { it.isClickable && it.isEnabled }
     */
    fun findByProperty(
        node: AccessibilityNodeInfo,
        check: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (check(node)) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findByProperty(child, check)
            if (result != null) {
                if (result != child) child.recycle()
                return result
            }
            child.recycle()
        }

        return null
    }

    /**
     * البحث عن العنصر الموجود عند إحداثيات معينة على الشاشة
     * يُرجع أدق عنصر (الأصغر) يحتوي النقطة المطلوبة
     */
    fun findByPosition(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // إذا كانت الإحداثيات خارج هذا العنصر — تجاهله
        if (!bounds.contains(x, y)) return null

        // ابحث في الأبناء أولاً — الابن أدق من الأب
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findByPosition(child, x, y)
            if (result != null) {
                if (result != child) child.recycle()
                return result
            }
            child.recycle()
        }

        // لم يوجد ابن أدق — هذا العنصر هو الأدق
        return node
    }

    /**
     * البحث عن عنصر بالـ ContentDescription (وصف الأيقونات للمكفوفين)
     * مفيد للأيقونات التي ليس لها نص ظاهر
     */
    fun findByDescription(
        node: AccessibilityNodeInfo,
        description: String
    ): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString()?.contains(description, ignoreCase = true) == true) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findByDescription(child, description)
            if (result != null) {
                if (result != child) child.recycle()
                return result
            }
            child.recycle()
        }

        return null
    }


    /**
     * كتابة نص في الحقل النشط حالياً في أي تطبيق
     * الطبقة 1: ACTION_SET_TEXT (سريع ونظيف)
     * الطبقة 2: Clipboard + Paste (يعمل في واتساب وتيليغرام وكل التطبيقات)
     */
    fun typeTextInFocusedField(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        // ابحث عن الحقل النشط (focused EditText)
        val focusedNode = findFocusedEditText(rootNode)

        return if (focusedNode != null) {
            // الطبقة 1: ACTION_SET_TEXT
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            focusedNode.recycle()

            if (success) {
                Log.d(TAG, "✅ كتابة النص بـ ACTION_SET_TEXT")
                true
            } else {
                // الطبقة 2: Clipboard + Paste
                Log.d(TAG, "⚠️ ACTION_SET_TEXT فشل، جرب Clipboard+Paste")
                pasteTextViaClipboard(text)
            }
        } else {
            // لا يوجد حقل focused — جرب مباشرة عبر Clipboard+Paste
            Log.d(TAG, "⚠️ لا يوجد حقل نشط، جرب Clipboard+Paste")
            pasteTextViaClipboard(text)
        }
    }

    /**
     * البحث عن أول حقل نصي نشط (focused) في الشجرة
     */
    private fun findFocusedEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // أولاً: جرب findFocus الأسرع
        val focused = node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) return focused

        // ثانياً: ابحث يدوياً في الشجرة
        return findByProperty(node) { n ->
            n.isFocused && (
                n.className?.contains("EditText", ignoreCase = true) == true ||
                n.isEditable
            )
        }
    }

    /**
     * الطبقة 2: نسخ النص للـ Clipboard ثم لصقه في الحقل النشط
     * يعمل في واتساب وتيليغرام وكل التطبيقات
     */
    fun pasteTextViaClipboard(text: String): Boolean {
        return try {
            val clipboard = context?.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return false
            val clip = android.content.ClipData.newPlainText("text", text)
            clipboard.setPrimaryClip(clip)

            // لصق عبر Accessibility
            val root = rootInActiveWindow ?: return false
            val focused = findFocusedEditText(root)

            val result = if (focused != null) {
                val ok = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                focused.recycle()
                ok
            } else {
                false
            }

            root.recycle()
            Log.d(TAG, if (result) "✅ لصق النص بنجاح عبر Clipboard" else "❌ فشل اللصق")
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ في pasteTextViaClipboard: ${e.message}")
            false
        }
    }

    // Context مطلوب لـ ClipboardManager
    private var context: android.content.Context? = null

    /**
     * الكتابة في حقل نصي بالـ ID (الدالة القديمة — محتفظ بها للتوافق)
     */
    fun writeToField(fieldId: String, text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val targetNode = findNodeById(rootNode, fieldId)
        
        return if (targetNode != null) {
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            targetNode.recycle()
            success
        } else {
            false
        }
    }

    /**
     * الحصول على كل النصوص في الشاشة
     * يفعّل الاستماع مؤقتاً قبل القراءة لضمان الحصول على snapshot حديث
     */
    fun getScreenText(): String {
        // ── فعّل الاستماع مؤقتاً حتى يتحدث rootInActiveWindow و windows ──
        val info = serviceInfo
        val savedPackages = info?.packageNames?.copyOf()
        val savedEventTypes = info?.eventTypes ?: 0
        if (info != null) {
            info.packageNames = null   // استمع لكل التطبيقات
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                              AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            serviceInfo = info
        }

        // انتظر لحظة قصيرة حتى يتحدث النظام الـ window tree
        Thread.sleep(150)

        val texts = mutableListOf<String>()

        // الطريقة الأولى: اقرأ النافذة النشطة في الأمام فقط (TYPE_APPLICATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val activeWin = windows
                    ?.filter { it.isActive && it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                    ?.maxByOrNull { it.layer }
                if (activeWin != null) {
                    val root = activeWin.root
                    if (root != null) {
                        collectTexts(root, texts)
                        root.recycle()
                    }
                }
            } catch (_: Exception) {}
        }

        // الطريقة الثانية: fallback — rootInActiveWindow
        if (texts.isEmpty()) {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                collectTexts(rootNode, texts)
                rootNode.recycle()
            }
        }

        // ── أعد تعطيل الاستماع للحفاظ على البطارية ──
        if (info != null) {
            info.packageNames = savedPackages
            info.eventTypes = savedEventTypes
            serviceInfo = info
        }

        return texts.joinToString("\n")
    }

    /**
     * جمع النصوص من الشجرة
     */
    private fun collectTexts(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        // تجاهل العناصر المخفية كلياً (مثل visibility=GONE)
        // لكن لا نعتمد على isVisibleToUser وحده لأنه يحجب contentDescription في بعض التطبيقات

        val text = node.text?.toString()
        if (!text.isNullOrBlank() && node.isVisibleToUser) {
            texts.add(text)
        }

        // contentDescription: نتحقق من أن العنصر داخل حدود الشاشة فعلاً
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank() && desc != text) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            // العنصر مرئي إذا كانت له مساحة حقيقية على الشاشة
            if (!bounds.isEmpty && bounds.top >= 0 && bounds.width() > 0 && bounds.height() > 0) {
                texts.add(desc)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, texts)
            child.recycle()
        }
    }

    /**
     * السحب على الشاشة (Swipe)
     */
    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gestureBuilder = GestureDescription.Builder()
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, duration)
        gestureBuilder.addStroke(strokeDescription)

        return dispatchGesture(gestureBuilder.build(), null, null)
    }

    /**
     * تمرير طبيعي يشبه الإصبع الحقيقي
     */
    fun performNaturalScroll(direction: String, screenWidth: Int, screenHeight: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val rand = java.util.Random()
        val centerX = screenWidth * (0.45f + rand.nextFloat() * 0.10f)
        val randomOffset = screenHeight * (rand.nextFloat() * 0.05f)

        val (startX, startY, endX, endY) = when (direction) {
            "up"    -> arrayOf(
                centerX,
                screenHeight * 0.75f + randomOffset,
                centerX + (rand.nextFloat() - 0.5f) * screenWidth * 0.04f,
                screenHeight * 0.25f - randomOffset
            )
            "down"  -> arrayOf(
                centerX,
                screenHeight * 0.25f - randomOffset,
                centerX + (rand.nextFloat() - 0.5f) * screenWidth * 0.04f,
                screenHeight * 0.75f + randomOffset
            )
            "right" -> arrayOf(
                screenWidth  * 0.15f + randomOffset,
                screenHeight * (0.45f + rand.nextFloat() * 0.10f),
                screenWidth  * 0.85f - randomOffset,
                screenHeight * (0.45f + rand.nextFloat() * 0.10f)
            )
            "left"  -> arrayOf(
                screenWidth  * 0.85f - randomOffset,
                screenHeight * (0.45f + rand.nextFloat() * 0.10f),
                screenWidth  * 0.15f + randomOffset,
                screenHeight * (0.45f + rand.nextFloat() * 0.10f)
            )
            else -> return false
        }

        val midX = (startX + endX) / 2f + (rand.nextFloat() - 0.5f) * screenWidth * 0.03f
        val midY = (startY + endY) / 2f + (rand.nextFloat() - 0.5f) * screenHeight * 0.03f

        val path = Path().apply {
            moveTo(startX, startY)
            quadTo(midX, midY, endX, endY)
        }

        val dur = (250 + rand.nextInt(200)).toLong()
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, dur))
        return dispatchGesture(gestureBuilder.build(), null, null)
    }

    /**
     * الضغط على إحداثيات معينة
     */
    fun performClick(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path().apply {
            moveTo(x, y)
        }

        val gestureBuilder = GestureDescription.Builder()
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, 100)
        gestureBuilder.addStroke(strokeDescription)

        return dispatchGesture(gestureBuilder.build(), null, null)
    }

    /**
     * أخذ لقطة شاشة (Android 11+)
     */
    fun takeScreenshot(callback: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                { runnable -> runnable.run() },
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        callback(true)
                        Log.d(TAG, "Screenshot taken successfully")
                    }

                    override fun onFailure(errorCode: Int) {
                        callback(false)
                        Log.e(TAG, "Screenshot failed: $errorCode")
                    }
                }
            )
        } else {
            callback(false)
        }
    }

    /**
     * الرجوع (Back button)
     */
    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * الذهاب للشاشة الرئيسية
     */
    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * فتح Recent Apps
     */
    fun performRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    /**
     * فتح الإشعارات
     */
    fun performNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    /**
     * فتح الإعدادات السريعة
     */
    fun performQuickSettings(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    }

    /**
     * إغلاق التطبيق الحالي
     */
    fun closeCurrentApp() {
        // الرجوع 3 مرات بفاصل 200ms بين كل ضغطة
        var count = 0
        fun doBack() {
            if (count >= 3) return
            performBack()
            count++
            mainHandler.postDelayed(::doBack, 200)
        }
        doBack()
    }

    /**
     * البحث عن تطبيق معين وإغلاقه من Recent Apps
     */
    fun closeAppByName(appName: String) {
        if (!performRecents()) return

        // انتظر 500ms حتى تفتح Recent Apps ثم ابحث
        mainHandler.postDelayed({
            val rootNode = rootInActiveWindow ?: return@postDelayed
            val appNode = findNodeByText(rootNode, appName)

            if (appNode != null) {
                val bounds = Rect()
                appNode.getBoundsInScreen(bounds)
                appNode.recycle()

                performSwipe(
                    bounds.centerX().toFloat(),
                    bounds.centerY().toFloat(),
                    bounds.centerX().toFloat(),
                    0f,
                    300
                )
                Log.d(TAG, "✅ تم إغلاق $appName")
            } else {
                Log.d(TAG, "⚠️ لم يُوجد $appName في Recent Apps")
            }
            rootNode.recycle()
        }, 500)
    }

    /**
     * تشغيل/إيقاف الواي فاي من الإعدادات السريعة
     */
    fun toggleWifiFromQuickSettings() {
        if (!performQuickSettings()) return

        mainHandler.postDelayed({
            clickByText("Wi-Fi") || clickByText("واي فاي") || clickByText("WLAN")
        }, 500)
    }

    /**
     * تشغيل/إيقاف البلوتوث من الإعدادات السريعة
     */
    fun toggleBluetoothFromQuickSettings() {
        if (!performQuickSettings()) return

        mainHandler.postDelayed({
            clickByText("Bluetooth") || clickByText("بلوتوث")
        }, 500)
    }

    companion object {
        private const val TAG = "AwabAccessibility"
        
        @Volatile
        private var instance: MyAccessibilityService? = null

        fun getInstance(): MyAccessibilityService? = instance
        
        fun isEnabled(): Boolean = instance != null
    }
}
 
