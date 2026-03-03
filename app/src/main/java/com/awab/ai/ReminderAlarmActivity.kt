package com.awab.ai

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ReminderAlarmActivity : AppCompatActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val autoStopHandler = Handler(Looper.getMainLooper())

    // يوقف التنبيه تلقائياً بعد 60 ثانية إذا لم يُوقفه المستخدم
    private val autoStopRunnable = Runnable { stopAlarmAndFinish() }

    companion object {
        const val EXTRA_TEXT = "reminder_text"

        fun start(context: Context, text: String) {
            val intent = Intent(context, ReminderAlarmActivity::class.java).apply {
                putExtra(EXTRA_TEXT, text)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // إظهار فوق شاشة القفل وتشغيل الشاشة
        setupWindowFlags()

        val reminderText = intent.getStringExtra(EXTRA_TEXT) ?: "تذكير!"

        // بناء الواجهة
        buildUI(reminderText)

        // تشغيل الصوت والاهتزاز
        startAlarm()

        // إيقاف تلقائي بعد 60 ثانية
        autoStopHandler.postDelayed(autoStopRunnable, 60_000)
    }

    private fun setupWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // رفع القفل مؤقتاً لإظهار الـ Activity
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, null)
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun buildUI(reminderText: String) {
        // الخلفية
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(48, 48, 48, 48)
        }

        // أيقونة الجرس
        val bellIcon = TextView(this).apply {
            text = "⏰"
            textSize = 72f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 32 }
        }

        // عنوان
        val title = TextView(this).apply {
            text = "تذكير"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
        }

        // نص التذكير
        val body = TextView(this).apply {
            text = reminderText
            textSize = 22f
            setTextColor(0xFFE0E0E0.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 64 }
        }

        // زر الإيقاف
        val stopBtn = TextView(this).apply {
            text = "إيقاف"
            textSize = 20f
            setTextColor(0xFF1A1A2E.toInt())
            gravity = Gravity.CENTER
            setPadding(64, 32, 64, 32)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFD700.toInt())
                cornerRadius = 50f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setOnClickListener { stopAlarmAndFinish() }
        }

        root.addView(bellIcon)
        root.addView(title)
        root.addView(body)
        root.addView(stopBtn)
        setContentView(root)
    }

    private fun startAlarm() {
        // الصوت — يستخدم نغمة المنبه الافتراضية
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@ReminderAlarmActivity, alarmUri)
                isLooping = true

                // رفع الصوت لمستوى المنبه
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)

                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // الاهتزاز المتكرر — نمط المنبه: طويل قصير طويل
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 800, 300, 800, 300, 800, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0)) // 0 = تكرار من البداية
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAlarmAndFinish() {
        autoStopHandler.removeCallbacks(autoStopRunnable)

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) { e.printStackTrace() }

        try {
            vibrator?.cancel()
        } catch (e: Exception) { e.printStackTrace() }

        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarmAndFinish()
    }

    // منع زر الرجوع من إغلاق التنبيه بدون إيقاف
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // لا نفعل شيئاً — المستخدم يجب يضغط "إيقاف"
    }
}
