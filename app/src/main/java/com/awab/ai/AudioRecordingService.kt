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
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * خدمة التسجيل الصوتي في الخلفية
 * - تبث الصوت لـ Deepgram للتحويل لنص
 * - تحفظ نسخة WAV محلية في نفس الوقت
 * - عند استقبال النص النهائي تُعيد تسمية الملف بنص الرسالة
 * - تقطع الصمت من نهاية الملف تلقائياً
 */
class AudioRecordingService : Service() {

    companion object {
        private const val TAG = "AudioRecordingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_recording_channel"

        const val ACTION_START = "ACTION_START_RECORDING"
        const val ACTION_STOP  = "ACTION_STOP_RECORDING"
        const val ACTION_RENAME_LAST   = "com.awab.ai.RENAME_LAST"
        const val EXTRA_FINAL_TEXT     = "extra_final_text"

        const val ACTION_TEXT_RECOGNIZED   = "com.awab.ai.TEXT_RECOGNIZED"
        const val EXTRA_TEXT               = "extra_text"
        const val EXTRA_ERROR              = "extra_error"
        const val ACTION_RECORDING_STARTED = "com.awab.ai.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.awab.ai.RECORDING_STOPPED"
        const val ACTION_VOLUME_CHANGED    = "com.awab.ai.VOLUME_CHANGED"
        const val EXTRA_VOLUME             = "extra_volume"

        /** مجلد حفظ التسجيلات */
        fun getRecordingsDir(context: Context): File {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "AwabAI_Recordings"
            )
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): AudioRecordingService = this@AudioRecordingService
    }

    // ---- إعدادات الصوت ----
    private val sampleRate    = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat   = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize    = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // ---- VAD ----
    private val silenceFramesBeforeIdle = 50
    private var silenceFrameCount       = 0
    private var isIdleMode              = false

    // ---- Adaptive Noise Floor ----
    // يتتبع مستوى الضجيج الطبيعي في البيئة ويحدّثه أثناء فترات الصمت
    private var noiseFloor  = 0.01f   // يبدأ بقيمة افتراضية آمنة
    private val noiseAlpha  = 0.05f   // سرعة التكيف (بطيء = أكثر استقراراً)

    private val apiKey = "bd345e01709fb47368c5d12e56a124f2465fdf8d"
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var isRecordingInternal = false
    private var recordingJob: Job?   = null
    private var serviceScope         = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    // ---- التسجيل المحلي ----
    private var currentWavFile: File?          = null
    private var wavOutputStream: FileOutputStream? = null
    private var totalPcmBytes: Int             = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START       -> startRecordingInBackground()
            ACTION_STOP        -> stopRecordingAndSelf()
            ACTION_RENAME_LAST -> {
                val text = intent.getStringExtra(EXTRA_FINAL_TEXT) ?: return START_STICKY
                finalizeWavFile(text)
                openNewWavFile()
            }
        }
        return START_STICKY
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
        startForeground(NOTIFICATION_ID, buildNotification("🎤 جاري التسجيل في الخلفية..."))
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

        closeWavFile()

        releaseWakeLock()
        sendBroadcast(Intent(ACTION_RECORDING_STOPPED))
        Log.d(TAG, "🛑 توقف التسجيل")
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
                "language=ar&model=nova-3&smart_format=false&" +
                "encoding=linear16&sample_rate=16000&channels=1&" +
                "vad_events=true&endpointing=300"

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
                sendBroadcast(Intent(ACTION_TEXT_RECOGNIZED).apply {
                    putExtra(EXTRA_ERROR, "خطأ في الاتصال: ${t.message}")
                })
                serviceScope.launch {
                    delay(3000)
                    if (isRecordingInternal) connectWebSocket()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (isRecordingInternal) {
                    serviceScope.launch { delay(1000); connectWebSocket() }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {}
        })
    }

    private fun startAudioCapture() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // أفضل من MIC لتقليل الضجيج
            sampleRate, channelConfig, audioFormat,
            bufferSize * 4
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "❌ فشل تهيئة AudioRecord")
            return
        }

        openNewWavFile()

        audioRecord?.startRecording()
        isRecordingInternal = true

        recordingJob = serviceScope.launch {
            val buffer = ByteArray(bufferSize * 2)

            while (isActive && isRecordingInternal) {
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (readSize > 0) {
                    val volume = computeVolume(buffer, readSize)
                    sendVolumeUpdate(volume)

                    // احفظ PCM محلياً دائماً
                    saveRawPcm(buffer, readSize)

                    // حساب العتبة الديناميكية بناءً على الضجيج
                    val dynamicThreshold = noiseFloor * 3f

                    if (volume > dynamicThreshold) {
                        // كلام حقيقي — لا تحدّث noiseFloor
                        silenceFrameCount = 0
                        isIdleMode = false
                        webSocket?.send(ByteString.of(*buffer.copyOfRange(0, readSize)))
                    } else {
                        // صمت أو ضجيج — حدّث noiseFloor تدريجياً
                        noiseFloor = noiseAlpha * volume + (1 - noiseAlpha) * noiseFloor

                        silenceFrameCount++
                        if (silenceFrameCount >= silenceFramesBeforeIdle && !isIdleMode) {
                            isIdleMode = true
                        }
                        if (!isIdleMode || silenceFrameCount % 10 == 0) {
                            webSocket?.send(ByteString.of(*buffer.copyOfRange(0, readSize)))
                        }
                        if (isIdleMode) delay(50) else delay(10)
                    }
                }
            }
        }
    }

    private fun handleTranscription(jsonText: String) {
        try {
            val json = JSONObject(jsonText)
            val type = json.optString("type")
            if (type == "SpeechStarted" || type == "UtteranceEnd") return

            if (json.has("channel")) {
                val alternatives = json.getJSONObject("channel").getJSONArray("alternatives")
                val isFinal      = json.optBoolean("is_final", false)

                if (alternatives.length() > 0) {
                    val transcript = alternatives.getJSONObject(0).getString("transcript")
                    if (transcript.isNotEmpty() && isFinal) {
                        Log.d(TAG, "✅ نص: $transcript")
                        sendBroadcast(Intent(ACTION_TEXT_RECOGNIZED).apply {
                            putExtra(EXTRA_TEXT, transcript)
                        })
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في معالجة النص: ${e.message}")
        }
    }

    // ============================
    // إدارة ملف WAV
    // ============================

    private fun openNewWavFile() {
        try {
            closeWavFile()
            val dir  = getRecordingsDir(this)
            val temp = File(dir, "recording_${System.currentTimeMillis()}.tmp")
            wavOutputStream = FileOutputStream(temp)
            wavOutputStream?.write(ByteArray(44)) // حجز مكان WAV header
            currentWavFile = temp
            totalPcmBytes  = 0
            // أعد تهيئة noiseFloor لكل تسجيل جديد
            noiseFloor = 0.01f
            Log.d(TAG, "📂 ملف مؤقت: ${temp.name}")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ فتح ملف: ${e.message}")
        }
    }

    private fun saveRawPcm(buffer: ByteArray, size: Int) {
        try {
            wavOutputStream?.write(buffer, 0, size)
            totalPcmBytes += size
        } catch (e: Exception) {
            Log.e(TAG, "خطأ حفظ PCM: ${e.message}")
        }
    }

    private fun closeWavFile() {
        try {
            wavOutputStream?.flush()
            wavOutputStream?.close()
            wavOutputStream = null
        } catch (e: Exception) { /* تجاهل */ }
    }

    private fun finalizeWavFile(transcript: String) {
        val file = currentWavFile ?: return
        closeWavFile()

        if (totalPcmBytes == 0) {
            file.delete()
            currentWavFile = null
            return
        }

        try {
            // ✂️ احسب نهاية الكلام الحقيقية وتجاهل الصمت بعده
            val trimmedBytes = trimSilenceFromEnd(file, totalPcmBytes)

            // اكتب WAV header بالحجم المقطوع
            val raf = RandomAccessFile(file, "rw")
            raf.seek(0)
            raf.write(buildWavHeader(trimmedBytes))
            // اقطع الملف عند نهاية الكلام الحقيقية
            raf.setLength((trimmedBytes + 44).toLong())
            raf.close()

            // نظّف اسم الملف
            val safeName = transcript
                .replace(Regex("[\\\\/:*?\"<>|]"), "")
                .replace(Regex("\\s+"), "_")
                .take(80)
                .ifBlank { "recording_${System.currentTimeMillis()}" }

            val dir     = getRecordingsDir(this)
            val wavFile = File(dir, "$safeName.wav")

            val finalFile = if (wavFile.exists()) {
                File(dir, "${safeName}_${System.currentTimeMillis()}.wav")
            } else wavFile

            file.renameTo(finalFile)
            currentWavFile = null
            Log.d(TAG, "💾 تم الحفظ: ${finalFile.name}")

        } catch (e: Exception) {
            Log.e(TAG, "خطأ إنهاء WAV: ${e.message}")
            file.delete()
            currentWavFile = null
        }
    }

    /**
     * يمسح من البداية للنهاية ويتذكر آخر موضع فيه كلام حقيقي
     * ثم يُضيف 300ms هامش بعده ويحذف ما تبقى
     */
    private fun trimSilenceFromEnd(file: File, totalPcmBytes: Int): Int {
        val threshold      = (noiseFloor * 3f).coerceAtLeast(0.01f)
        val keepSilenceMs  = 300  // ms نحتفظ بها بعد آخر كلمة
        val keepSilenceBytes = sampleRate * 2 * keepSilenceMs / 1000
        val frameSizeBytes = 320  // ~10ms عند 16000Hz

        val raf = RandomAccessFile(file, "r")
        val buffer = ByteArray(frameSizeBytes)
        var lastSpeechPos = 44 // بداية بيانات PCM (بعد الـ header)
        var pos = 44

        // امشِ من البداية للنهاية وسجّل آخر إطار فيه كلام
        while (pos + frameSizeBytes <= totalPcmBytes + 44) {
            raf.seek(pos.toLong())
            val read = raf.read(buffer)
            if (read <= 0) break

            val volume = computeVolume(buffer, read)
            if (volume > threshold) {
                lastSpeechPos = pos + read
            }
            pos += frameSizeBytes
        }

        raf.close()

        // أضف هامش 300ms بعد آخر كلام
        val trimmedPcm = ((lastSpeechPos - 44) + keepSilenceBytes)
            .coerceIn(0, totalPcmBytes)

        val savedMs = ((totalPcmBytes - trimmedPcm).toFloat() / (sampleRate * 2)) * 1000
        Log.d(TAG, "✂️ حُذف ${savedMs.toInt()} ms من الصمت (threshold: $threshold)")

        return trimmedPcm
    }

    /** يبني WAV header بحجم 44 بايت */
    private fun buildWavHeader(pcmBytes: Int): ByteArray {
        val totalDataLen = pcmBytes + 36
        val byteRate     = sampleRate * 1 * 16 / 8
        val blockAlign   = 1 * 16 / 8

        return ByteArray(44).also { h ->
            h[0]='R'.code.toByte(); h[1]='I'.code.toByte()
            h[2]='F'.code.toByte(); h[3]='F'.code.toByte()
            putInt(h, 4, totalDataLen)
            h[8]='W'.code.toByte();  h[9]='A'.code.toByte()
            h[10]='V'.code.toByte(); h[11]='E'.code.toByte()
            h[12]='f'.code.toByte(); h[13]='m'.code.toByte()
            h[14]='t'.code.toByte(); h[15]=' '.code.toByte()
            putInt(h, 16, 16)
            putShort(h, 20, 1)
            putShort(h, 22, 1)
            putInt(h, 24, sampleRate)
            putInt(h, 28, byteRate)
            putShort(h, 32, blockAlign)
            putShort(h, 34, 16)
            h[36]='d'.code.toByte(); h[37]='a'.code.toByte()
            h[38]='t'.code.toByte(); h[39]='a'.code.toByte()
            putInt(h, 40, pcmBytes)
        }
    }

    private fun putInt(arr: ByteArray, offset: Int, value: Int) {
        arr[offset]     = (value and 0xFF).toByte()
        arr[offset + 1] = (value shr 8  and 0xFF).toByte()
        arr[offset + 2] = (value shr 16 and 0xFF).toByte()
        arr[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun putShort(arr: ByteArray, offset: Int, value: Int) {
        arr[offset]     = (value and 0xFF).toByte()
        arr[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    // ============================
    // مستوى الصوت
    // ============================

    private fun computeVolume(buffer: ByteArray, size: Int): Float {
        var sum = 0.0; var i = 0
        while (i + 1 < size) {
            val sample = (buffer[i + 1].toInt() shl 8 or (buffer[i].toInt() and 0xFF)).toShort()
            sum += (sample * sample).toDouble(); i += 2
        }
        return (kotlin.math.sqrt(sum / (size / 2)) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    private fun sendVolumeUpdate(volume: Float) {
        sendBroadcast(Intent(ACTION_VOLUME_CHANGED).apply { putExtra(EXTRA_VOLUME, volume) })
    }

    // ============================
    // WakeLock
    // ============================

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AwabAI::AudioRecordingWakeLock")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(10 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    // ============================
    // الإشعار
    // ============================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "التسجيل الصوتي",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار التسجيل في الخلفية"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AudioRecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("أواب AI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "إيقاف", stopIntent)
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
