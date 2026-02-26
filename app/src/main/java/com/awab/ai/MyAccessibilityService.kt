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

    /**
     * يسجّل مهمة انتظار جديدة
     * يُرجع ID المهمة (لإلغائها إذا لزم)
     */
    fun registerWaitTask(
        targetText: String,
        waitForShow: Boolean = true,
        timeoutMs: Long = 10_000L,
        onFound: () -> Unit,
        onTimeout: () -> Unit = {}
    ): Int {
        val id = nextTaskId++
        val task = WaitTask(id, targetText, waitForShow, timeoutMs, onFound, onTimeout)

        synchronized(waitTasks) { waitTasks.add(task) }

        // ضبط timeout إذا كانت المهلة محددة
        if (timeoutMs > 0) {
            mainHandler.postDelayed({
                val removed = synchronized(waitTasks) { waitTasks.removeAll { it.id == id } }
                if (removed) {
                    Log.d(TAG, "⏰ انتهت مهلة انتظار: \"$targetText\"")
                    onTimeout()
                }
            }, timeoutMs)
        }

        Log.d(TAG, "⏳ مهمة انتظار #$id: \"$targetText\" (${if (waitForShow) "ظهور" else "اختفاء"})")
        return id
    }

    /** إلغاء مهمة انتظار بالـ ID */
    fun cancelWaitTask(id: Int) {
        synchronized(waitTasks) { waitTasks.removeAll { it.id == id } }
    }

    /** إلغاء جميع مهام الانتظار */
    fun cancelAllWaitTasks() {
        synchronized(waitTasks) { waitTasks.clear() }
    }

    // ===== معالجة أحداث الشاشة =====

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val event = event ?: return

        // نستمع فقط لتغييرات المحتوى وظهور نوافذ جديدة
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // إذا لا توجد مهام انتظار نتجاهل الحدث فوراً
        val tasks = synchronized(waitTasks) { waitTasks.toList() }
        if (tasks.isEmpty()) return

        // نقرأ نص الشاشة مرة واحدة ونفحص كل المهام
        val screenText = getScreenText().lowercase()

        val toRemove = mutableListOf<WaitTask>()

        for (task in tasks) {
            val found = screenText.contains(task.targetText.lowercase())
            val conditionMet = if (task.waitForShow) found else !found

            if (conditionMet) {
                toRemove.add(task)
                Log.d(TAG, "✅ تحقق الشرط #${task.id}: \"${task.targetText}\"")
                mainHandler.post { task.onFound() }
            }
        }

        if (toRemove.isNotEmpty()) {
            synchronized(waitTasks) { waitTasks.removeAll(toRemove.toSet()) }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
        cancelAllWaitTasks()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
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
    fun closeCurrentApp(): Boolean {
        // الطريقة 1: زر الرجوع عدة مرات
        var success = true
        repeat(3) {
            success = success && performBack()
            Thread.sleep(200)
        }
        
        // الطريقة 2: إذا لم ينجح، افتح Recent واسحب لإغلاق
        if (!success) {
            performRecents()
        }
        
        return success
    }

    /**
     * البحث عن تطبيق معين وإغلاقه من Recent Apps
     */
    fun closeAppByName(appName: String): Boolean {
        // فتح Recent Apps
        if (!performRecents()) return false
        
        Thread.sleep(500) // انتظر حتى تفتح
        
        // البحث عن التطبيق
        val rootNode = rootInActiveWindow ?: return false
        val appNode = findNodeByText(rootNode, appName)
        
        if (appNode != null) {
            // السحب لأعلى لإغلاق التطبيق
            val bounds = Rect()
            appNode.getBoundsInScreen(bounds)
            
            performSwipe(
                bounds.centerX().toFloat(),
                bounds.centerY().toFloat(),
                bounds.centerX().toFloat(),
                0f,
                300
            )
            
            appNode.recycle()
            rootNode.recycle()
            return true
        }
        
        rootNode.recycle()
        return false
    }

    /**
     * تشغيل/إيقاف الواي فاي من الإعدادات السريعة
     */
    fun toggleWifiFromQuickSettings(): Boolean {
        if (!performQuickSettings()) return false
        
        Thread.sleep(500)
        
        return clickByText("Wi-Fi") || clickByText("واي فاي") || clickByText("WLAN")
    }

    /**
     * تشغيل/إيقاف البلوتوث من الإعدادات السريعة
     */
    fun toggleBluetoothFromQuickSettings(): Boolean {
        if (!performQuickSettings()) return false
        
        Thread.sleep(500)
        
        return clickByText("Bluetooth") || clickByText("بلوتوث")
    }

    companion object {
        private const val TAG = "AwabAccessibility"
        
        @Volatile
        private var instance: MyAccessibilityService? = null

        fun getInstance(): MyAccessibilityService? = instance
        
        fun isEnabled(): Boolean = instance != null
    }
}
