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

        if (allPackages.isEmpty() && !hasActiveTasks.get()) {
            info.eventTypes = 0
            info.packageNames = arrayOf("com.awab.ai")
        } else {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                              AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.packageNames = allPackages.toTypedArray()
        }
        serviceInfo = info
    }

    // ===== معالجة أحداث الشاشة =====

    private val hasActiveTasks = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return

        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val root = rootInActiveWindow ?: return
        val currentPkg = root.packageName?.toString() ?: return

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
     * الكتابة في حقل نصي
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
     */
    fun getScreenText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val texts = mutableListOf<String>()
        collectTexts(rootNode, texts)
        rootNode.recycle()
        return texts.joinToString("\n")
    }

    /**
     * جمع النصوص من الشجرة
     */
    private fun collectTexts(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            texts.add(text)
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
 
