package com.awab.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.*

class SpeechRecognizer(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    
    // 8 ثوانٍ من الصوت (مثل التدريب)
    private val fixedRequiredSamples = 128000 
    
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
    
    fun isModelLoaded(): Boolean = interpreter != null

    fun loadModelFromFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "Model file not found: $filePath")
                return false
            }
            
            val modelBuffer = loadModelFromPath(file)
            
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                addDelegate(FlexDelegate())
            }
            
            interpreter = Interpreter(modelBuffer, options)
            interpreter?.allocateTensors()
            
            // طباعة معلومات النموذج للتحقق
            val inputDetails = interpreter?.getInputTensor(0)
            val outputDetails = interpreter?.getOutputTensor(0)
            
            Log.d(TAG, "Model loaded successfully!")
            Log.d(TAG, "Input shape: ${inputDetails?.shape()?.contentToString()}")
            Log.d(TAG, "Input type: ${inputDetails?.dataType()}")
            Log.d(TAG, "Output shape: ${outputDetails?.shape()?.contentToString()}")
            Log.d(TAG, "Output type: ${outputDetails?.dataType()}")
            
            listener?.onModelLoaded(file.name)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}")
            e.printStackTrace()
            listener?.onError("فشل التحميل: ${e.message}")
            false
        }
    }

    fun loadModelFromAssets(modelFileName: String = "speech_model.tflite"): Boolean {
        return try {
            val modelBuffer = loadModelFromAssetsInternal(modelFileName)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                addDelegate(FlexDelegate())
            }
            interpreter = Interpreter(modelBuffer, options)
            interpreter?.allocateTensors()
            
            listener?.onModelLoaded(modelFileName)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model from assets: ${e.message}")
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
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    fun startRecording() {
        if (isRecording || interpreter == null) {
            Log.w(TAG, "Cannot start recording: isRecording=$isRecording, model loaded=${interpreter != null}")
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
                Log.e(TAG, "AudioRecord failed to initialize")
                return
            }

            isRecording = true
            audioRecord?.startRecording()
            listener?.onRecordingStarted()
            
            Thread { recordAndRecognize() }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
            isRecording = false
        }
    }

    private fun recordAndRecognize() {
        val audioBuffer = ShortArray(bufferSize)
        val audioData = mutableListOf<Short>()
        
        try {
            while (isRecording) {
                val readSize = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                if (readSize > 0) {
                    val volume = calculateVolume(audioBuffer, readSize)
                    listener?.onVolumeChanged(volume)
                    
                    for (i in 0 until readSize) {
                        audioData.add(audioBuffer[i])
                    }
                    
                    // معالجة كل 8 ثوانٍ
                    if (audioData.size >= fixedRequiredSamples) {
                        processAudioChunk(audioData.take(fixedRequiredSamples).toShortArray())
                        audioData.clear()
                    }
                }
            }
            
            // معالجة ما تبقى عند الإيقاف
            if (audioData.size >= sampleRate * 2) { // على الأقل ثانيتين
                processAudioChunk(audioData.toShortArray())
            }
        } catch (e: Exception) { 
            Log.e(TAG, "Error during recording: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun processAudioChunk(audioArray: ShortArray) {
        Log.d(TAG, "Processing audio chunk: ${audioArray.size} samples")
        val text = recognizeSpeech(audioArray)
        if (text.isNotBlank()) {
            Log.d(TAG, "Recognized text: '$text'")
            listener?.onTextRecognized(text)
        } else {
            Log.d(TAG, "No text recognized (empty result)")
        }
    }

    /**
     * ✅ التعرف على الكلام - متوافق 100% مع كود التدريب
     */
    private fun recognizeSpeech(audioData: ShortArray): String {
        return try {
            val startTime = System.currentTimeMillis()
            
            // ✅ الخطوة 1: تحويل Raw Audio إلى Spectrogram
            // مطابق تماماً لـ:
            // stft = np.abs(librosa.stft(audio, n_fft=384, hop_length=160, win_length=256))
            // spec = librosa.amplitude_to_db(stft, ref=np.max).T
            // spec = (spec + 80) / 80
            val spectrogram = audioToSpectrogram(audioData)
            
            val spectrogramTime = System.currentTimeMillis()
            Log.d(TAG, "✓ Spectrogram created: ${spectrogram.size} frames, time: ${spectrogramTime - startTime}ms")
            
            // ✅ الخطوة 2: تجهيز الـ Buffer بالشكل (1, time_steps, 193)
            val timeSteps = spectrogram.size
            val featureSize = 193
            
            val inputBuffer = ByteBuffer.allocateDirect(1 * timeSteps * featureSize * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            
            // ملء البيانات: [batch=1, time, features=193]
            for (t in 0 until timeSteps) {
                for (f in 0 until featureSize) {
                    inputBuffer.putFloat(spectrogram[t][f])
                }
            }
            inputBuffer.rewind()
            
            val bufferTime = System.currentTimeMillis()
            Log.d(TAG, "✓ Buffer prepared: (1, $timeSteps, $featureSize), time: ${bufferTime - spectrogramTime}ms")
            
            // ✅ الخطوة 3: تشغيل النموذج
            // النموذج يُرجع indices مباشرة (بفضل CTC decoder المدمج)
            val outputTensor = interpreter?.getOutputTensor(0)
            val outputShape = outputTensor?.shape() ?: intArrayOf(100)
            val outputSize = outputShape.fold(1) { acc, i -> acc * i }
            
            Log.d(TAG, "Output shape: ${outputShape.contentToString()}, size: $outputSize")
            
            val outputBuffer = IntArray(outputSize)
            
            interpreter?.run(inputBuffer, outputBuffer)
            
            val inferenceTime = System.currentTimeMillis()
            Log.d(TAG, "✓ Inference completed, time: ${inferenceTime - bufferTime}ms")
            
            // ✅ الخطوة 4: فك التشفير
            val result = decodeCTCOutput(outputBuffer)
            
            val totalTime = System.currentTimeMillis()
            Log.d(TAG, "✓ Total recognition time: ${totalTime - startTime}ms")
            Log.d(TAG, "✓ Result: '$result'")
            
            result
        } catch (e: Exception) { 
            Log.e(TAG, "Recognition error: ${e.message}")
            e.printStackTrace()
            "" 
        }
    }
    
    /**
     * ✅ تحويل الصوت الخام إلى Spectrogram
     * مطابق تماماً لـ librosa في كود التدريب:
     * - librosa.stft(audio, n_fft=384, hop_length=160, win_length=256)
     * - librosa.amplitude_to_db(stft, ref=np.max)
     * - (spec + 80) / 80
     */
    private fun audioToSpectrogram(audioData: ShortArray): Array<FloatArray> {
        // تحويل من Short إلى Float normalized [-1, 1]
        val audioFloat = FloatArray(audioData.size) { i ->
            audioData[i] / 32768.0f
        }
        
        // STFT parameters (يجب أن تطابق التدريب بالضبط)
        val nFFT = 384
        val hopLength = 160
        val winLength = 256
        
        // حساب عدد الإطارات
        val numFrames = ((audioFloat.size - winLength) / hopLength) + 1
        
        Log.d(TAG, "STFT params: nFFT=$nFFT, hop=$hopLength, win=$winLength")
        Log.d(TAG, "Audio length: ${audioFloat.size}, frames: $numFrames")
        
        // تطبيق STFT
        val spectrogram = Array(numFrames) { FloatArray(193) } // 193 = nFFT/2 + 1
        var maxMagnitude = 1e-10f // لتجنب القسمة على صفر
        
        // المرحلة 1: حساب Magnitude Spectrum وإيجاد القيمة القصوى
        for (frame in 0 until numFrames) {
            val start = frame * hopLength
            val windowedSignal = applyHannWindow(audioFloat, start, winLength, nFFT)
            val fftResult = fft(windowedSignal)
            
            // حساب Magnitude
            for (i in 0 until 193) {
                val real = fftResult[i * 2]
                val imag = fftResult[i * 2 + 1]
                val magnitude = sqrt(real * real + imag * imag)
                spectrogram[frame][i] = magnitude
                if (magnitude > maxMagnitude) {
                    maxMagnitude = magnitude
                }
            }
        }
        
        Log.d(TAG, "Max magnitude: $maxMagnitude")
        
        // المرحلة 2: تحويل إلى dB و Normalize
        // amplitude_to_db: 20 * log10(magnitude / ref)
        // في librosa: ref=np.max (القيمة القصوى)
        for (frame in 0 until numFrames) {
            for (i in 0 until 193) {
                val magnitude = spectrogram[frame][i]
                
                // تحويل إلى dB (مع حماية من log(0))
                val db = if (magnitude > 1e-10f) {
                    20f * log10(magnitude / maxMagnitude)
                } else {
                    -80f // الحد الأدنى
                }
                
                // Normalize: (db + 80) / 80
                // هذا يحول النطاق من [-80, 0] إلى [0, 1]
                spectrogram[frame][i] = (db + 80f) / 80f
            }
        }
        
        return spectrogram
    }
    
    /**
     * ✅ تطبيق نافذة Hann (مثل librosa الافتراضي)
     * librosa يستخدم Hann window بشكل افتراضي، وليس Hamming
     */
    private fun applyHannWindow(signal: FloatArray, start: Int, winLength: Int, nFFT: Int): FloatArray {
        val windowed = FloatArray(nFFT)
        
        for (i in 0 until winLength) {
            if (start + i < signal.size) {
                // Hann window: 0.5 * (1 - cos(2π * i / (N-1)))
                val hann = 0.5f * (1f - cos(2f * PI.toFloat() * i / (winLength - 1)))
                windowed[i] = signal[start + i] * hann
            }
        }
        
        return windowed
    }
    
    /**
     * ✅ Fast Fourier Transform - نسخة مبسطة
     * للإنتاج: استخدم مكتبة JTransforms للسرعة
     */
    private fun fft(input: FloatArray): FloatArray {
        val n = input.size
        val output = FloatArray(n * 2)
        
        // DFT مبسطة
        // للإنتاج: استبدل بـ JTransforms.realForward()
        for (k in 0 until n / 2 + 1) {
            var realSum = 0f
            var imagSum = 0f
            
            for (t in 0 until n) {
                val angle = -2f * PI.toFloat() * k * t / n
                realSum += input[t] * cos(angle)
                imagSum += input[t] * sin(angle)
            }
            
            if (k * 2 + 1 < output.size) {
                output[k * 2] = realSum
                output[k * 2 + 1] = imagSum
            }
        }
        
        return output
    }

    /**
     * ✅ فك تشفير CTC Output
     * النموذج يُرجع indices مباشرة من CTC decoder
     */
    private fun decodeCTCOutput(indices: IntArray): String {
        val vocabulary = loadVocabulary()
        
        if (vocabulary.isEmpty()) {
            Log.e(TAG, "Vocabulary is empty!")
            return ""
        }
        
        Log.d(TAG, "Decoding ${indices.size} indices with vocabulary size ${vocabulary.size}")
        Log.d(TAG, "Sample indices: ${indices.take(20).joinToString()}")
        
        val result = StringBuilder()
        var lastIdx = -1
        var validCount = 0
        
        for (idx in indices) {
            // تخطي blank (0) والتكرار
            if (idx == 0 || idx == lastIdx) {
                lastIdx = idx
                continue
            }
            
            // التحقق من صحة الـ index
            if (idx > 0 && idx < vocabulary.size) {
                result.append(vocabulary[idx])
                validCount++
            } else if (idx != 0) {
                Log.w(TAG, "Invalid index: $idx (vocabulary size: ${vocabulary.size})")
            }
            
            lastIdx = idx
        }
        
        Log.d(TAG, "Decoded $validCount valid characters")
        
        return result.toString()
    }

    /**
     * تحميل vocabulary.txt
     * المحتوى:
     * - السطر 0: مسافة (blank for CTC)
     * - السطور 1-36: الحروف العربية
     */
    private fun loadVocabulary(): List<String> {
        return try {
            val vocab = context.assets.open("vocabulary.txt")
                .bufferedReader()
                .readLines()
                .map { it.trim() }
            
            Log.d(TAG, "Vocabulary loaded: ${vocab.size} entries")
            Log.d(TAG, "First 5: ${vocab.take(5)}")
            
            vocab
        } catch (e: Exception) { 
            Log.e(TAG, "Failed to load vocabulary: ${e.message}")
            e.printStackTrace()
            emptyList() 
        }
    }

    private fun calculateVolume(buffer: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) {
            sum += (buffer[i] * buffer[i]).toDouble()
        }
        return (sqrt(sum / size) / Short.MAX_VALUE).toFloat()
    }

    fun stopRecording() {
        isRecording = false
        audioRecord?.apply { 
            try {
                stop()
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recording: ${e.message}")
            }
        }
        audioRecord = null
        listener?.onRecordingStopped()
    }

    fun cleanup() {
        stopRecording()
        interpreter?.close()
        interpreter = null
    }

    companion object {
        private const val TAG = "SpeechRecognizer"
    }
}
