package com.awab.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class SpeechRecognizer(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private val inputSize = 16000
    
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
    
    /**
     * التحقق من تحميل النموذج
     */
    fun isModelLoaded(): Boolean {
        return interpreter != null
    }

    /**
     * تحميل نموذج من ملف خارجي (من ذاكرة الهاتف)
     * هذه الطريقة الرئيسية الآن - المستخدم يختار الملف
     */
    fun loadModelFromFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "❌ الملف غير موجود: $filePath")
                listener?.onError("الملف غير موجود")
                return false
            }
            
            if (!file.name.endsWith(".tflite")) {
                Log.e(TAG, "❌ صيغة خاطئة: ${file.name}")
                listener?.onError("الملف يجب أن يكون بصيغة .tflite")
                return false
            }
            
            Log.d(TAG, "📂 محاولة تحميل: ${file.name} (${file.length()} bytes)")
            
            val modelBuffer = loadModelFromPath(file)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            
            Log.d(TAG, "🔧 إنشاء Interpreter...")
            interpreter = Interpreter(modelBuffer, options)
            
            Log.d(TAG, "✅ تم تحميل النموذج: ${file.name}")
            listener?.onModelLoaded(file.name)
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل تحميل النموذج: ${e.message}")
            Log.e(TAG, "Stack trace:", e)
            listener?.onError("فشل تحميل النموذج: ${e.message}")
            false
        }
    }
    
    /**
     * تحميل نموذج من assets (اختياري - للاختبار)
     */
    fun loadModelFromAssets(modelFileName: String = "speech_model.tflite"): Boolean {
        return try {
            val modelBuffer = loadModelFromAssetsInternal(modelFileName)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
            
            Log.d(TAG, "✅ تم تحميل النموذج من assets: $modelFileName")
            listener?.onModelLoaded(modelFileName)
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل تحميل النموذج من assets: ${e.message}")
            listener?.onError("لم يتم العثور على النموذج في assets")
            false
        }
    }

    private fun loadModelFromPath(file: File): MappedByteBuffer {
        val inputStream = FileInputStream(file)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
    }
    
    private fun loadModelFromAssetsInternal(modelFileName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun startRecording() {
        if (isRecording) {
            Log.w(TAG, "⚠️ التسجيل قيد العمل بالفعل")
            return
        }
        
        if (interpreter == null) {
            listener?.onError("يرجى تحميل النموذج أولاً")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                listener?.onError("فشل تهيئة التسجيل الصوتي")
                return
            }

            isRecording = true
            audioRecord?.startRecording()
            listener?.onRecordingStarted()
            
            Log.d(TAG, "🎤 بدأ التسجيل...")

            Thread {
                recordAndRecognize()
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ في بدء التسجيل: ${e.message}")
            listener?.onError("فشل بدء التسجيل: ${e.message}")
            isRecording = false
        }
    }

    fun stopRecording() {
        if (!isRecording) {
            return
        }

        isRecording = false
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            
            listener?.onRecordingStopped()
            Log.d(TAG, "🛑 توقف التسجيل")
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ في إيقاف التسجيل: ${e.message}")
        }
    }

    private fun recordAndRecognize() {
        val audioBuffer = ShortArray(bufferSize)
        val audioData = mutableListOf<Short>()
        
        // Get required audio length from model
        val inputShape = interpreter?.getInputTensor(0)?.shape() ?: intArrayOf(1, 1, 193)
        // Shape is [batch, sequence, features] - we need the LAST dimension for features
        val requiredSize = inputShape[inputShape.size - 1]
        val minSize = maxOf(requiredSize / 4, 32) // At least 32 samples minimum
        
        Log.d(TAG, "📊 بدء التسجيل - الحد الأدنى: ${minSize} samples، المطلوب: ${requiredSize} samples")
        
        var silenceCount = 0
        val silenceThreshold = 0.01f
        val silenceDuration = 10 // ~0.6 seconds of silence to auto-process
        
        try {
            while (isRecording) {
                val readSize = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                
                if (readSize > 0) {
                    val volume = calculateVolume(audioBuffer, readSize)
                    listener?.onVolumeChanged(volume)
                    
                    for (i in 0 until readSize) {
                        audioData.add(audioBuffer[i])
                    }
                    
                    val currentSize = audioData.size
                    Log.d(TAG, "📊 تم تسجيل: $currentSize/$requiredSize عينة")
                    
                    // Detect silence
                    if (volume < silenceThreshold) {
                        silenceCount++
                    } else {
                        silenceCount = 0
                    }
                    
                    // Three conditions to process:
                    // 1. Reached required size
                    // 2. Have minimum AND detected silence
                    // 3. User stopped recording
                    
                    val hasEnoughAudio = currentSize >= minSize
                    val detectedSilence = silenceCount >= silenceDuration
                    val reachedRequired = currentSize >= requiredSize
                    
                    if (reachedRequired || (hasEnoughAudio && detectedSilence)) {
                        if (reachedRequired) {
                            Log.d(TAG, "🎯 وصل إلى $requiredSize samples - بدء المعالجة...")
                        } else {
                            Log.d(TAG, "🎯 تم كشف سكوت بعد $currentSize samples - بدء المعالجة...")
                        }
                        
                        val audioArray = audioData.toShortArray()
                        val text = recognizeSpeech(audioArray)
                        
                        if (text.isNotBlank()) {
                            listener?.onTextRecognized(text)
                            Log.d(TAG, "✅ النتيجة: '$text'")
                        } else {
                            Log.w(TAG, "⚠️ نتيجة فارغة")
                        }
                        
                        // Clear buffer for next recognition
                        audioData.clear()
                        silenceCount = 0
                        Log.d(TAG, "🔄 تم مسح البيانات، جاهز للتسجيل التالي")
                    }
                } else {
                    Log.w(TAG, "⚠️ readSize <= 0: $readSize")
                }
            }
            
            // When user stops recording, process remaining audio if enough
            if (audioData.size >= minSize) {
                Log.d(TAG, "🎯 توقف التسجيل مع ${audioData.size} samples - معالجة نهائية...")
                
                val audioArray = audioData.toShortArray()
                val text = recognizeSpeech(audioArray)
                
                if (text.isNotBlank()) {
                    listener?.onTextRecognized(text)
                    Log.d(TAG, "✅ النتيجة النهائية: '$text'")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ أثناء التسجيل: ${e.message}")
            e.printStackTrace()
            listener?.onError("خطأ أثناء التسجيل")
        }
        
        Log.d(TAG, "🏁 انتهت حلقة التسجيل")
    }

    private fun calculateVolume(buffer: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) {
            sum += (buffer[i] * buffer[i]).toDouble()
        }
        val rms = sqrt(sum / size)
        return (rms / Short.MAX_VALUE).toFloat()
    }

    private fun recognizeSpeech(audioData: ShortArray): String {
        try {
            // Get input/output tensor info
            val inputTensor = interpreter?.getInputTensor(0)
            val outputTensor = interpreter?.getOutputTensor(0)
            
            val inputShape = inputTensor?.shape() ?: intArrayOf(1, 128000)
            val outputShape = outputTensor?.shape() ?: intArrayOf(1, 100)
            val outputType = outputTensor?.dataType()
            
            Log.d(TAG, "📊 Input shape: ${inputShape.contentToString()}")
            Log.d(TAG, "📊 Output shape: ${outputShape.contentToString()}")
            Log.d(TAG, "📊 Output type: $outputType")
            
            // Get the correct dimension for audio features
            // Shape is [batch, sequence, features] so we need the LAST dimension
            val requiredSize = inputShape[inputShape.size - 1]
            Log.d(TAG, "📊 Required: $requiredSize samples")
            
            // Normalize to [-1.0, 1.0] and pad if needed
            val normalized = FloatArray(requiredSize) { i ->
                if (i < audioData.size) {
                    audioData[i] / 32768.0f
                } else {
                    0.0f // Padding with zeros
                }
            }
            
            Log.d(TAG, "🔧 Normalized ${audioData.size} samples → $requiredSize")
            if (audioData.size < requiredSize) {
                Log.d(TAG, "   Padded with ${requiredSize - audioData.size} zeros")
            }
            
            // Create input buffer
            val inputBuffer = ByteBuffer.allocateDirect(requiredSize * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            normalized.forEach { inputBuffer.putFloat(it) }
            inputBuffer.rewind()
            
            // Prepare output buffer (Int32)
            val maxOutputLength = outputShape.getOrElse(1) { 100 }
            val outputBuffer = IntArray(maxOutputLength)
            
            Log.d(TAG, "🚀 Running inference...")
            interpreter?.run(inputBuffer, outputBuffer)
            Log.d(TAG, "✅ Inference completed")
            
            // Decode CTC output
            return decodeCTCOutput(outputBuffer)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in recognition: ${e.message}")
            e.printStackTrace()
            return ""
        }
    }
    
    /**
     * CTC Decoding for model output
     */
    private fun decodeCTCOutput(indices: IntArray): String {
        val vocabulary = loadVocabulary()
        val result = StringBuilder()
        var lastIdx = -1
        
        Log.d(TAG, "🔍 CTC Decoding...")
        Log.d(TAG, "🔍 Output indices (first 20): ${indices.take(20)}")
        Log.d(TAG, "🔍 Vocabulary size: ${vocabulary.size}")
        
        var validCount = 0
        for (idx in indices) {
            // Skip blank token (index 0)
            if (idx == 0) continue
            
            // Skip repeated characters (CTC rule)
            if (idx == lastIdx) continue
            
            // Valid character index
            if (idx > 0 && idx < vocabulary.size) {
                val char = vocabulary[idx]
                result.append(char)
                validCount++
                
                if (validCount <= 15) {
                    Log.d(TAG, "  [$validCount] idx=$idx → '$char'")
                }
            } else if (idx != 0) {
                Log.w(TAG, "  ⚠️ Invalid index: $idx (vocab size: ${vocabulary.size})")
            }
            
            lastIdx = idx
        }
        
        val decoded = result.toString()
        Log.d(TAG, "✅ CTC Result: '$decoded' (${validCount} chars)")
        
        return decoded
    }

    private fun loadVocabulary(): List<String> {
        return try {
            val vocabulary = mutableListOf<String>()
            context.assets.open("vocabulary.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    vocabulary.add(line.trim())
                }
            }
            Log.d(TAG, "📚 Loaded vocabulary: ${vocabulary.size} characters")
            vocabulary
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading vocabulary: ${e.message}")
            emptyList()
        }
    }

    fun cleanup() {
        stopRecording()
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "🧹 تم تنظيف الموارد")
    }

    companion object {
        private const val TAG = "SpeechRecognizer"
    }
}
