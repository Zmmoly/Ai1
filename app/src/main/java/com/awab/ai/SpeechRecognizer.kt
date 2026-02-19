package com.awab.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import org.json.JSONObject

class SpeechRecognizer(private val context: Context) {

    private val apiKey = "bd345e01709fb47368c5d12e56a124f2465fdf8d"

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var isRecordingInternal = false
    private var recordingJob: Job? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    interface RecognitionListener {
        fun onTextRecognized(text: String)
        fun onError(error: String)
        fun onRecordingStarted()
        fun onRecordingStopped()
        fun onVolumeChanged(volume: Float)
        fun onModelLoaded(modelName: String)
    }

    private var listener: RecognitionListener? = null

    fun setListener(listener: RecognitionListener) {
        this.listener = listener
    }

    // دائماً true لأن Deepgram لا يحتاج نموذجاً محلياً
    fun isModelLoaded(): Boolean = true

    // هذه الدوال موجودة للتوافق مع الكود القديم فقط
    fun loadModelFromFile(filePath: String): Boolean {
        listener?.onModelLoaded("Deepgram API")
        return true
    }

    fun loadModelFromAssets(modelFileName: String = "speech_model.tflite"): Boolean {
        listener?.onModelLoaded("Deepgram API")
        return true
    }

    /**
     * بدء التسجيل والاتصال بـ Deepgram
     */
    fun startRecording() {
        if (isRecordingInternal) {
            Log.w(TAG, "⚠️ التسجيل قيد العمل بالفعل")
            return
        }

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            listener?.onError("صلاحية الميكروفون غير ممنوحة")
            return
        }

        connectWebSocket()
    }

    /**
     * إيقاف التسجيل
     */
    fun stopRecording() {
        isRecordingInternal = false
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        webSocket?.close(1000, "تم إنهاء التسميع")
        webSocket = null

        listener?.onRecordingStopped()
        Log.d(TAG, "🛑 توقف التسجيل")
    }

    /**
     * إنشاء اتصال WebSocket مع Deepgram
     */
    private fun connectWebSocket() {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val url = "wss://api.deepgram.com/v1/listen?" +
                "language=ar&" +
                "model=nova-3&" +
                "smart_format=false&" +
                "encoding=linear16&" +
                "sample_rate=16000&" +
                "channels=1"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ Deepgram WebSocket مفتوح بنجاح")
                listener?.onRecordingStarted()
                startAudioCapture()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📨 رسالة مستلمة: $text")
                handleTranscription(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // غير مستخدم
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errorMsg = "خطأ في الاتصال: ${t.message}"
                Log.e(TAG, "❌ WebSocket فشل: ${t.message}")
                Log.e(TAG, "Response: ${response?.code} - ${response?.message}")
                response?.body?.string()?.let { body ->
                    Log.e(TAG, "Response body: $body")
                }
                listener?.onError(errorMsg)
                stopRecording()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔄 WebSocket يُغلق: $code - $reason")
                stopRecording()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔒 WebSocket مغلق: $code - $reason")
            }
        })
    }

    /**
     * بدء التقاط الصوت وإرساله للـ WebSocket
     */
    private fun startAudioCapture() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize * 2
        )

        audioRecord?.startRecording()
        isRecordingInternal = true

        Log.d(TAG, "🎤 بدأ التسجيل...")

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(bufferSize)

            while (isActive && isRecordingInternal) {
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (readSize > 0) {
                    // حساب مستوى الصوت
                    val volume = computeVolume(buffer, readSize)
                    CoroutineScope(Dispatchers.Main).launch {
                        listener?.onVolumeChanged(volume)
                    }

                    // إرسال البيانات الصوتية
                    val byteArray = buffer.copyOfRange(0, readSize)
                    val byteString = ByteString.of(*byteArray)
                    webSocket?.send(byteString)
                }

                delay(10)
            }
        }
    }

    /**
     * معالجة النص المستلم من Deepgram
     */
    private fun handleTranscription(jsonText: String) {
        try {
            val json = JSONObject(jsonText)

            if (json.has("channel")) {
                val channel = json.getJSONObject("channel")
                val alternatives = channel.getJSONArray("alternatives")
                val isFinal = json.optBoolean("is_final", false)

                if (alternatives.length() > 0) {
                    val transcript = alternatives.getJSONObject(0).getString("transcript")

                    if (transcript.isNotEmpty()) {
                        if (isFinal) {
                            Log.d(TAG, "✅ نص نهائي: $transcript")
                            CoroutineScope(Dispatchers.Main).launch {
                                listener?.onTextRecognized(transcript)
                            }
                        } else {
                            Log.d(TAG, "⏳ نص مؤقت: $transcript")
                            CoroutineScope(Dispatchers.Main).launch {
                                listener?.onTextRecognized(transcript)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ في معالجة النص: ${e.message}", e)
        }
    }

    /**
     * حساب مستوى الصوت
     */
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

    fun cleanup() {
        stopRecording()
        Log.d(TAG, "🧹 تم تنظيف الموارد")
    }

    companion object {
        private const val TAG = "SpeechRecognizer"
    }
}
