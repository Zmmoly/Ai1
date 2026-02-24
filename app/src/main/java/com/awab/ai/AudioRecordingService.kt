package com.awab.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * خدمة التسجيل الصوتي في الخلفية
 * تعمل كـ Foreground Service لتجنب الإيقاف من نظام الأندرويد
 * مع تحسينات لتوفير البطارية:
 * 1. تسجيل فقط عند وجود صوت (VAD - Voice Activity Detection)
 * 2. تقليل معدل أخذ العينات عند عدم وجود نشاط
 * 3. استخدام WakeLock بحد أدنى
 */
class AudioRecordingService : Service() {

    companion object {
        private const val TAG = "AudioRecordingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_recording_channel"

        const val ACTION_START = "ACTION_START_RECORDING"
        const val ACTION_STOP = "ACTION_STOP_RECORDING"

        // إشارة Broadcast لإرسال النص المعرف للـ Activity
        const val ACTION_TEXT_RECOGNIZED = "com.awab.ai.TEXT_RECOGNIZED"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_ERROR = "extra_error"
        const val ACTION_RECORDING_STARTED = "com.awab.ai.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.awab.ai.RECORDING_STOPPED"
        const val ACTION_VOLUME_CHANGED = "com.awab.ai.VOLUME_CHANGED"
        const val EXTRA_VOLUME = "extra_volume"
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): AudioRecordingService = this@AudioRecordingService
    }

    // ---- إعدادات الصوت ----
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // ---- حد اكتشاف الصوت (VAD) لتوفير البطارية ----
    // إذا كان مستوى الصوت أقل من هذا الحد → لا نرسل للـ API
    private val silenceThreshold = 0.01f
    // عدد الإطارات الصامتة المتتالية قبل تقليل المعالجة
    private val silenceFramesBeforeIdle = 50 // ~2.5 ثانية
    private var silenceFrameCount = 0
    private var isIdleMode = false

    private val apiKey = "bd345e01709fb47368c5d12e56a124f2465fdf8d"
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var isRecordingInternal = false
    private var recordingJob: Job? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // WakeLock لمنع النوم أثناء التسجيل فقط عند الحاجة
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecordingInBackground()
            ACTION_STOP -> stopRecordingAndSelf()
        }
        return START_STICKY // إعادة التشغيل التلقائي إذا أُوقف النظام الخدمة
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingInternal()
        serviceScope.cancel()
        releaseWakeLock()
    }

    // ============================
    // إدارة التسجيل
    // ============================

    fun startRecordingInBackground() {
        if (isRecordingInternal) return

        // تشغيل الخدمة في المقدمة مع إشعار
        startForeground(NOTIFICATION_ID, buildNotification("🎤 جاري التسجيل في الخلفية..."))

        // WakeLock خفيف (PARTIAL) فقط لضمان عمل المعالج
        acquireWakeLock()

        connectWebSocket()
    }

    fun stopRecordingAndSelf() {
        stopRecordingInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopRecordingInternal() {
        isRecordingInternal = false
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        webSocket?.close(1000, "إيقاف التسجيل")
        webSocket = null

        releaseWakeLock()
        sendBroadcast(Intent(ACTION_RECORDING_STOPPED))
        Log.d(TAG, "🛑 توقف التسجيل في الخلفية")
    }

    // ============================
    // WebSocket + التقاط الصوت
    // ============================

    private fun connectWebSocket() {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val url = "wss://api.deepgram.com/v1/listen?" +
                "language=ar&" +
                "model=nova-3&" +
                "smart_format=false&" +
                "encoding=linear16&" +
                "sample_rate=16000&" +
                "channels=1&" +
                "vad_events=true&" +       // تفعيل اكتشاف الصوت من الخادم
                "endpointing=300"           // 300ms صمت = نهاية جملة

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WebSocket مفتوح")
                sendBroadcast(Intent(ACTION_RECORDING_STARTED))
                updateNotification("🎤 يستمع في الخلفية...")
                startAudioCapture()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleTranscription(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ فشل WebSocket: ${t.message}")
                val intent = Intent(ACTION_TEXT_RECOGNIZED).apply {
                    putExtra(EXTRA_ERROR, "خطأ في الاتصال: ${t.message}")
                }
                sendBroadcast(intent)
                // إعادة الاتصال بعد 3 ثوانٍ
                serviceScope.launch {
                    delay(3000)
                    if (isRecordingInternal) connectWebSocket()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔄 WebSocket يُغلق: $code")
                if (isRecordingInternal) {
                    // إعادة الاتصال تلقائياً
                    serviceScope.launch {
                        delay(1000)
                        connectWebSocket()
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔒 WebSocket مغلق")
            }
        })
    }

    private fun startAudioCapture() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize * 4 // بافر أكبر لتقليل عمليات القراءة
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "❌ فشل تهيئة AudioRecord")
            return
        }

        audioRecord?.startRecording()
        isRecordingInternal = true

        recordingJob = serviceScope.launch {
            val buffer = ByteArray(bufferSize * 2) // بافر مضاعف للكفاءة

            while (isActive && isRecordingInternal) {
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (readSize > 0) {
                    val volume = computeVolume(buffer, readSize)

                    // إرسال مستوى الصوت للـ UI
                    sendVolumeUpdate(volume)

                    // *** تحسين البطارية: VAD محلي ***
                    if (volume > silenceThreshold) {
                        // يوجد صوت → إرسال البيانات
                        silenceFrameCount = 0
                        if (isIdleMode) {
                            isIdleMode = false
                            Log.d(TAG, "🔊 اكتُشف صوت، استئناف الإرسال")
                        }
                        val byteString = ByteString.of(*buffer.copyOfRange(0, readSize))
                        webSocket?.send(byteString)
                    } else {
                        // صمت
                        silenceFrameCount++
                        if (silenceFrameCount >= silenceFramesBeforeIdle && !isIdleMode) {
                            isIdleMode = true
                            Log.d(TAG, "🔇 وضع الخمول - تقليل الاستهلاك")
                            // نرسل إشارة صمت خفيفة للخادم كل فترة فقط
                        }

                        // في وضع الخمول: أرسل إطار فارغ كل 10 إطارات فقط للحفاظ على الاتصال
                        if (!isIdleMode || silenceFrameCount % 10 == 0) {
                            val byteString = ByteString.of(*buffer.copyOfRange(0, readSize))
                            webSocket?.send(byteString)
                        }

                        // تأخير أطول في وضع الخمول لتوفير البطارية
                        if (isIdleMode) delay(50) else delay(10)
                    }
                }
            }
        }
    }

    private fun handleTranscription(jsonText: String) {
        try {
            val json = JSONObject(jsonText)

            // تجاهل أحداث VAD من الخادم (speech_started, utterance_end)
            val type = json.optString("type")
            if (type == "SpeechStarted" || type == "UtteranceEnd") return

            if (json.has("channel")) {
                val channel = json.getJSONObject("channel")
                val alternatives = channel.getJSONArray("alternatives")
                val isFinal = json.optBoolean("is_final", false)

                if (alternatives.length() > 0) {
                    val transcript = alternatives.getJSONObject(0).getString("transcript")
                    if (transcript.isNotEmpty() && isFinal) {
                        Log.d(TAG, "✅ نص: $transcript")
                        val intent = Intent(ACTION_TEXT_RECOGNIZED).apply {
                            putExtra(EXTRA_TEXT, transcript)
                        }
                        sendBroadcast(intent)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في معالجة النص: ${e.message}")
        }
    }

    private fun computeVolume(buffer: ByteArray, size: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < size) {
            val sample = (buffer[i + 1].toInt() shl 8 or (buffer[i].toInt() and 0xFF)).toShort()
            sum += (sample * sample).toDouble()
            i += 2
        }
        val rms = kotlin.math.sqrt(sum / (size / 2))
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    private fun sendVolumeUpdate(volume: Float) {
        val intent = Intent(ACTION_VOLUME_CHANGED).apply {
            putExtra(EXTRA_VOLUME, volume)
        }
        sendBroadcast(intent)
    }

    // ============================
    // WakeLock
    // ============================

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, // أخف نوع - يبقي المعالج يعمل فقط
            "AwabAI::AudioRecordingWakeLock"
        )
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(10 * 60 * 1000L) // 10 دقائق كحد أقصى
        Log.d(TAG, "🔒 WakeLock مفعّل")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "🔓 WakeLock محرَّر")
        }
        wakeLock = null
    }

    // ============================
    // الإشعار
    // ============================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "التسجيل الصوتي",
                NotificationManager.IMPORTANCE_LOW // منخفض = لا صوت ولا اهتزاز
            ).apply {
                description = "إشعار التسجيل في الخلفية"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, AudioRecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("أواب AI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_delete, "إيقاف", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
