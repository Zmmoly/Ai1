package com.awab.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private val permissionList = mutableListOf<String>()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach {
            val permissionName = it.key.substringAfterLast(".")
            val isGranted = it.value
            logStatus("${if (isGranted) "✓" else "✗"} $permissionName")
        }
        updatePermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        // عنوان الصفحة
        val titleText = TextView(this).apply {
            text = "⚙️ إعدادات الأذونات"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        }

        // معلومات Deepgram
        val deepgramInfoView = TextView(this).apply {
            text = "🎤 التعرف الصوتي\n\n✅ يعمل عبر Deepgram API\nلا يحتاج إلى نموذج محلي"
            textSize = 14f
            setTextColor(0xFF155724.toInt())
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFFD4EDDA.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }

        // حالة الأذونات
        statusTextView = TextView(this).apply {
            text = "جاري التحميل..."
            textSize = 14f
            setPadding(16, 16, 16, 32)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        val requestPermissionsButton = createStyledButton("طلب الأذونات العادية") {
            requestAllPermissionsInBatches()
        }

        val specialPermissionsButton = createStyledButton("طلب الأذونات الخاصة") {
            requestSpecialPermissions()
        }

        val accessibilityButton = createStyledButton("فتح إعدادات إمكانية الوصول") {
            openAccessibilitySettings()
        }

        val appNamesButton = createStyledButton("📝 أسماء التطبيقات المخصصة", 0xFF2196F3.toInt()) {
            startActivity(Intent(this, AppNamesActivity::class.java))
        }

        val backButton = createStyledButton("← رجوع للمحادثة", 0xFF6C757D.toInt()) {
            finish()
        }

        layout.addView(titleText)
        layout.addView(deepgramInfoView)
        layout.addView(statusTextView)
        layout.addView(requestPermissionsButton)
        layout.addView(specialPermissionsButton)
        layout.addView(accessibilityButton)
        layout.addView(appNamesButton)
        layout.addView(backButton)
        scrollView.addView(layout)
        setContentView(scrollView)

        setupPermissionsList()
        updatePermissionStatus()
    }

    private fun createStyledButton(text: String, bgColor: Int = 0xFF007BFF.toInt(), onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 16f
            setBackgroundColor(bgColor)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setupPermissionsList() {
        permissionList.clear()

        permissionList.add(Manifest.permission.READ_CALENDAR)
        permissionList.add(Manifest.permission.WRITE_CALENDAR)
        permissionList.add(Manifest.permission.CAMERA)
        permissionList.add(Manifest.permission.READ_CONTACTS)
        permissionList.add(Manifest.permission.WRITE_CONTACTS)
        permissionList.add(Manifest.permission.GET_ACCOUNTS)
        permissionList.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionList.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        permissionList.add(Manifest.permission.RECORD_AUDIO)
        permissionList.add(Manifest.permission.READ_PHONE_STATE)
        permissionList.add(Manifest.permission.CALL_PHONE)
        permissionList.add(Manifest.permission.READ_CALL_LOG)
        permissionList.add(Manifest.permission.WRITE_CALL_LOG)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissionList.add(Manifest.permission.READ_PHONE_NUMBERS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            permissionList.add(Manifest.permission.BODY_SENSORS)
        }

        permissionList.add(Manifest.permission.SEND_SMS)
        permissionList.add(Manifest.permission.RECEIVE_SMS)
        permissionList.add(Manifest.permission.READ_SMS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionList.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissionList.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissionList.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissionList.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissionList.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                permissionList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionList.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            permissionList.add(Manifest.permission.ACTIVITY_RECOGNITION)
            permissionList.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionList.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionList.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissionList.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
    }

    private fun requestAllPermissionsInBatches() {
        val permissionsToRequest = permissionList.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            Toast.makeText(this, "جميع الأذونات ممنوحة!", Toast.LENGTH_SHORT).show()
            updatePermissionStatus()
            return
        }

        val batches = permissionsToRequest.chunked(3)
        requestNextBatch(batches, 0)
    }

    private fun requestNextBatch(batches: List<List<String>>, index: Int) {
        if (index >= batches.size) {
            logStatus("\n✅ انتهى طلب الأذونات!")
            updatePermissionStatus()
            return
        }

        val batch = batches[index]
        logStatus("\n--- دفعة ${index + 1}/${batches.size} ---")

        requestPermissionsLauncher.launch(batch.toTypedArray())

        android.os.Handler(mainLooper).postDelayed({
            requestNextBatch(batches, index + 1)
        }, 2000)
    }

    private fun requestSpecialPermissions() {
        AlertDialog.Builder(this)
            .setTitle("الأذونات الخاصة")
            .setItems(arrayOf(
                "رسم فوق التطبيقات الأخرى",
                "تعديل إعدادات النظام",
                "إدارة جميع الملفات",
                "تثبيت الحزم",
                "تجاهل تحسين البطارية"
            )) { _, which ->
                when (which) {
                    0 -> requestOverlayPermission()
                    1 -> requestWriteSettingsPermission()
                    2 -> requestManageStoragePermission()
                    3 -> requestInstallPackagesPermission()
                    4 -> requestBatteryOptimizationPermission()
                }
            }
            .show()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                Toast.makeText(this, "الإذن ممنوح بالفعل", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                Toast.makeText(this, "الإذن ممنوح بالفعل", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }

    private fun requestInstallPackagesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                Toast.makeText(this, "الإذن ممنوح بالفعل", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestBatteryOptimizationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "قم بتفعيل خدمة أواب AI", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطأ في فتح الإعدادات", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePermissionStatus() {
        val sb = StringBuilder()
        var granted = 0
        var denied = 0

        permissionList.forEach { permission ->
            val isGranted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            if (isGranted) granted++ else denied++
        }

        sb.append("📊 إحصائيات الأذونات\n")
        sb.append("─".repeat(30))
        sb.append("\n\n")
        sb.append("✅ ممنوحة: $granted\n")
        sb.append("❌ مرفوضة: $denied\n")
        sb.append("📱 الإجمالي: ${permissionList.size}\n")

        statusTextView.text = sb.toString()
    }

    private fun logStatus(message: String) {
        runOnUiThread {
            statusTextView.append("$message\n")
        }
    }
}
