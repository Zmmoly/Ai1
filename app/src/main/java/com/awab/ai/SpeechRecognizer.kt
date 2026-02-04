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
import kotlin.math.ln
import kotlin.math.cos

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
            
            val modelBuffer = loadModelBuffer(file)
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
            val modelBuffer = loadModelFromAssetsBuffer(modelFileName)
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

    private fun loadModelBuffer(file: File): MappedByteBuffer {
        val inputStream = FileInputStream(file)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
    }
    
    private fun loadModelFromAssetsBuffer(modelFileName: String): MappedByteBuffer {
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
        
        val recognizedText = StringBuilder()
        var silenceCount = 0
        val silenceThreshold = 0.01f
        
        // نافذة أصغر للكتابة المباشرة - 0.5 ثانية فقط
        val windowDuration = 0.5f
        val windowSize = (sampleRate * windowDuration).toInt()
        
        // overlap للحصول على نتائج أفضل
        val overlapRatio = 0.5f
        val hopSize = (windowSize * (1 - overlapRatio)).toInt()
        
        Log.d(TAG, "📊 بدء حلقة التسجيل - windowSize: $windowSize, hopSize: $hopSize, sampleRate: $sampleRate")
        
        try {
            while (isRecording) {
                val readSize = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                
                if (readSize > 0) {
                    val volume = computeVolume(audioBuffer, readSize)
                    listener?.onVolumeChanged(volume)
                    
                    val isSilent = volume < silenceThreshold
                    
                    if (isSilent) {
                        silenceCount++
                        // إذا صمت طويل، أضف مسافة
                        if (silenceCount > 10 && recognizedText.isNotEmpty()) {
                            if (recognizedText.last() != ' ') {
                                recognizedText.append(" ")
                                listener?.onTextRecognized(recognizedText.toString())
                            }
                        }
                    } else {
                        silenceCount = 0
                    }
                    
                    // إضافة البيانات
                    for (i in 0 until readSize) {
                        audioData.add(audioBuffer[i])
                    }
                    
                    // معالجة فورية عند وصول النافذة الصغيرة
                    while (audioData.size >= windowSize) {
                        val windowData = audioData.take(windowSize).toShortArray()
                        
                        // التعرف مباشرة إذا لم يكن صمت
                        if (!isSilent) {
                            val text = recognizeSpeech(windowData)
                            
                            if (text.isNotBlank()) {
                                recognizedText.append(text)
                                Log.d(TAG, "🔤 تم التعرف مباشرة: $text")
                                listener?.onTextRecognized(recognizedText.toString())
                            }
                        }
                        
                        // إزالة البيانات المعالجة مع الحفاظ على overlap
                        val toRemove = kotlin.math.min(hopSize, audioData.size)
                        repeat(toRemove) { audioData.removeAt(0) }
                    }
                }
            }
            
            // معالجة أي بيانات متبقية
            if (audioData.size >= windowSize / 2 && recognizedText.isNotEmpty()) {
                val remainingData = audioData.toShortArray()
                val text = recognizeSpeech(remainingData)
                if (text.isNotBlank()) {
                    recognizedText.append(text)
                }
            }
            
            // إرسال النص النهائي
            if (recognizedText.isNotEmpty()) {
                val finalText = recognizedText.toString().trim()
                Log.d(TAG, "📝 نص نهائي عند التوقف: $finalText")
                listener?.onTextRecognized(finalText)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ أثناء التسجيل: ${e.message}")
            listener?.onError("خطأ أثناء التسجيل")
        }
    }

    private fun computeVolume(buffer: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) {
            sum += (buffer[i] * buffer[i]).toDouble()
        }
        val rms = sqrt(sum / size)
        return (rms / Short.MAX_VALUE).toFloat()
    }

    // ========== المعالجة الصوتية - مطابقة للكود الجديد ==========
    
    private fun recognizeSpeech(audioData: ShortArray): String {
        try {
            // 1. معالجة الصوت مثل prepare_audio في Python
            val features = prepareAudioLikeLibrosa(audioData)
            
            // 2. Resize tensor
            val inputDetails = interpreter?.getInputTensor(0)
            interpreter?.resizeInput(0, features.shape)
            interpreter?.allocateTensors()
            
            // 3. تحويل لـ ByteBuffer
            val inputBuffer = createInputBuffer(features)
            
            // 4. تحضير المخرجات
            val outputDetails = interpreter?.getOutputTensor(0)
            val outputShape = outputDetails?.shape()
            
            if (outputShape == null || outputShape.size < 3) {
                Log.e(TAG, "❌ شكل المخرجات غير صحيح")
                return ""
            }
            
            // المخرج: [batch, time, vocab_size]
            val batchSize = outputShape[0]
            val timeSteps = outputShape[1]
            val vocabSize = outputShape[2]
            
            // تحضير مصفوفة المخرجات
            val outputArray = Array(batchSize) { Array(timeSteps) { FloatArray(vocabSize) } }
            
            // 5. تشغيل النموذج
            interpreter?.run(inputBuffer, outputArray)
            
            // 6. فك التشفير - CTC Decode مثل Python
            val text = ctcDecodeGreedy(outputArray[0])
            
            Log.d(TAG, "📝 Decoded text: $text")
            
            return text
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ في التعرف: ${e.message}", e)
            return ""
        }
    }
    
    /**
     * معالجة الصوت - مطابقة لـ prepare_audio في Python:
     * - librosa.load(sr=16000)
     * - librosa.util.normalize(audio)
     * - librosa.stft(n_fft=512, hop_length=128, win_length=400)
     * - librosa.amplitude_to_db()
     * - (spec + 80) / 80
     */
    private fun prepareAudioLikeLibrosa(audioData: ShortArray): ProcessedAudio {
        // 1. تطبيع الصوت (librosa.util.normalize)
        val audio = normalizeAudio(audioData)
        
        // 2. STFT - معاملات جديدة
        val nFFT = 512
        val hopLength = 128
        val winLength = 400
        val stft = computeSTFT(audio, nFFT, hopLength, winLength)
        
        // 3. amplitude_to_db
        val specDB = amplitudeToDb(stft)
        
        // 4. التطبيع: (spec + 80) / 80
        val normalizedSpec = Array(specDB.size) { t ->
            FloatArray(specDB[t].size) { f ->
                (specDB[t][f] + 80f) / 80f
            }
        }
        
        // Shape: [1, time, freq]
        return ProcessedAudio(
            data = normalizedSpec,
            shape = intArrayOf(1, normalizedSpec.size, normalizedSpec[0].size)
        )
    }
    
    private fun normalizeAudio(audioData: ShortArray): FloatArray {
        val floatData = FloatArray(audioData.size) { i ->
            audioData[i].toFloat() / Short.MAX_VALUE
        }
        
        // librosa.util.normalize: audio / max(abs(audio))
        val maxAbs = floatData.maxOf { kotlin.math.abs(it) }
        return if (maxAbs > 0f) {
            FloatArray(floatData.size) { i -> floatData[i] / maxAbs }
        } else {
            floatData
        }
    }
    
    private fun computeSTFT(audio: FloatArray, nFFT: Int, hopLength: Int, winLength: Int): Array<FloatArray> {
        val numFrames = (audio.size - nFFT) / hopLength + 1
        val fftSize = nFFT / 2 + 1
        
        val stft = Array(numFrames) { FloatArray(fftSize) }
        
        // Hann window
        val window = FloatArray(winLength) { i ->
            0.5f * (1f - cos(2f * Math.PI.toFloat() * i / (winLength - 1)))
        }
        
        for (frame in 0 until numFrames) {
            val start = frame * hopLength
            val fftInput = FloatArray(nFFT) { 0f }
            
            // تطبيق النافذة
            for (i in 0 until kotlin.math.min(winLength, audio.size - start)) {
                fftInput[i] = audio[start + i] * window[i]
            }
            
            // FFT - Magnitude only
            for (k in 0 until fftSize) {
                var real = 0f
                var imag = 0f
                
                for (n in 0 until nFFT) {
                    val angle = -2f * Math.PI.toFloat() * k * n / nFFT
                    real += fftInput[n] * cos(angle)
                    imag += fftInput[n] * kotlin.math.sin(angle)
                }
                
                stft[frame][k] = kotlin.math.sqrt(real * real + imag * imag)
            }
        }
        
        return stft
    }
    
    private fun amplitudeToDb(stft: Array<FloatArray>): Array<FloatArray> {
        val refValue = stft.maxOf { frame -> frame.maxOrNull() ?: 0f }
        
        return Array(stft.size) { t ->
            FloatArray(stft[t].size) { f ->
                val magnitude = stft[t][f]
                20f * ln((magnitude + 1e-10f) / (refValue + 1e-10f)) / ln(10f)
            }
        }
    }
    
    /**
     * فك التشفير CTC - مطابق لكود Python الجديد:
     * predictions = np.argmax(logits, axis=-1)[0]
     * ثم حذف المكرر والـ blank
     */
    private fun ctcDecodeGreedy(logits: Array<FloatArray>): String {
        val vocabulary = loadVocabulary()
        val blankIndex = vocabulary.size // الـ blank يكون في النهاية
        
        val result = StringBuilder()
        var lastChar = -1
        
        // argmax على كل timestep
        for (t in logits.indices) {
            val probs = logits[t]
            
            // إيجاد الـ index الأكبر
            var maxIdx = 0
            var maxProb = Float.MIN_VALUE
            
            for (i in probs.indices) {
                if (probs[i] > maxProb) {
                    maxProb = probs[i]
                    maxIdx = i
                }
            }
            
            // CTC rules: حذف المكرر وحذف الـ blank
            if (maxIdx != lastChar && maxIdx != blankIndex) {
                if (maxIdx < vocabulary.size) {
                    result.append(vocabulary[maxIdx])
                }
            }
            
            lastChar = maxIdx
        }
        
        return result.toString().trim()
    }
    
    private fun createInputBuffer(features: ProcessedAudio): ByteBuffer {
        val totalSize = features.data.sumOf { it.size }
        val buffer = ByteBuffer.allocateDirect(totalSize * 4)
        buffer.order(ByteOrder.nativeOrder())
        
        for (row in features.data) {
            for (value in row) {
                buffer.putFloat(value)
            }
        }
        
        buffer.rewind()
        return buffer
    }

    private fun loadVocabulary(): List<String> {
        return try {
            context.assets.open("vocabulary.txt").bufferedReader().readLines()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ استخدام قائمة افتراضية")
            // القائمة الجديدة - مطابقة لـ char_list في Python
            listOf(
                " ", "ا", "ب", "ت", "ث", "ج", "ح", "خ", "د", "ذ", 
                "ر", "ز", "س", "ش", "ص", "ض", "ط", "ظ", "ع", "غ", 
                "ف", "ق", "ك", "ل", "م", "ن", "هـ", "و", "ي", 
                "ى", "ئ", "ؤ"
            )
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
    
    private data class ProcessedAudio(
        val data: Array<FloatArray>,
        val shape: IntArray
    )
}
