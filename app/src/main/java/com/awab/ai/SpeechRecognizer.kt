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
import kotlin.math.log10
import kotlin.math.sqrt

class SpeechRecognizer(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    
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
    fun setListener(listener: RecognitionListener) { this.listener = listener }

    fun isModelLoaded(): Boolean = interpreter != null

    fun loadModelFromFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            val modelBuffer = loadModelBuffer(file)
            val options = Interpreter.Options().apply { setNumThreads(4) }
            interpreter = Interpreter(modelBuffer, options)
            listener?.onModelLoaded(file.name)
            true
        } catch (e: Exception) {
            listener?.onError("فشل التحميل: ${e.message}")
            false
        }
    }

    private fun loadModelBuffer(file: File): MappedByteBuffer {
        val inputStream = FileInputStream(file)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
    }

    fun startRecording() {
        if (isRecording || interpreter == null) return
        try {
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
            isRecording = true
            audioRecord?.startRecording()
            listener?.onRecordingStarted()
            Thread { recordAndRecognize() }.start()
        } catch (e: Exception) {
            listener?.onError("خطأ: ${e.message}")
        }
    }

    fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        listener?.onRecordingStopped()
    }

    private fun recordAndRecognize() {
        val audioBuffer = ShortArray(bufferSize)
        val audioData = mutableListOf<Short>()
        
        while (isRecording) {
            val readSize = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
            if (readSize > 0) {
                listener?.onVolumeChanged(computeVolume(audioBuffer, readSize))
                for (i in 0 until readSize) audioData.add(audioBuffer[i])
                
                // نقوم بالتعرف عند اكتمال نافذة صوتية (مثلاً 2 ثانية)
                if (audioData.size >= sampleRate * 2) {
                    val windowData = audioData.take(sampleRate * 2).toShortArray()
                    val text = recognizeSpeech(windowData)
                    if (text.isNotEmpty()) listener?.onTextRecognized(text)
                    audioData.clear() 
                }
            }
        }
    }

    // --- الجزء المعدل ليتوافق مع كود الاختبار (المدخلات فقط) ---
    
    private fun recognizeSpeech(audioData: ShortArray): String {
        return try {
            val inputBuffer = prepareInputBuffer(audioData)
            
            // الموديل في كود الاختبار يخرج مصفوفة Indices مباشرة
            val outputDetails = interpreter!!.getOutputTensor(0)
            val outputShape = outputDetails.shape() 
            val outputBuffer = IntArray(outputShape[1]) 
            
            interpreter?.run(inputBuffer, outputBuffer)
            
            processIndices(outputBuffer)
        } catch (e: Exception) { "" }
    }

    private fun prepareInputBuffer(audioData: ShortArray): ByteBuffer {
        // 1. التطبيع (Normalize)
        val floats = FloatArray(audioData.size) { audioData[it].toFloat() / 32768f }
        
        // 2. تحويل STFT (نفس إعدادات كاجل: 384, 160, 256)
        val stft = computeSTFT(floats)
        
        val timeSteps = stft.size
        val nFreqs = stft[0].size
        val buffer = ByteBuffer.allocateDirect(timeSteps * nFreqs * 4)
        buffer.order(ByteOrder.nativeOrder())
        
        // 3. التحويل لـ dB والتطبيع النهائي: (spec + 80) / 80
        for (t in 0 until timeSteps) {
            for (f in 0 until nFreqs) {
                val db = 20f * log10(stft[t][f] + 1e-10f)
                val normalized = (db + 80f) / 80f
                buffer.putFloat(normalized.coerceIn(0f, 1f))
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun computeSTFT(audio: FloatArray): Array<FloatArray> {
        val nFFT = 384
        val hopLength = 160
        val winLength = 256
        val numFrames = (audio.size - nFFT) / hopLength + 1
        val fftSize = nFFT / 2 + 1
        val spec = Array(numFrames) { FloatArray(fftSize) }
        
        for (frame in 0 until numFrames) {
            val start = frame * hopLength
            for (k in 0 until fftSize) {
                var real = 0f
                var imag = 0f
                for (n in 0 until winLength) {
                    if (start + n < audio.size) {
                        val angle = -2.0 * Math.PI * k * n / nFFT
                        val window = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * n / (winLength - 1)))
                        val sample = audio[start + n] * window
                        real += (sample * Math.cos(angle)).toFloat()
                        imag += (sample * Math.sin(angle)).toFloat()
                    }
                }
                spec[frame][k] = sqrt(real * real + imag * imag)
            }
        }
        return spec
    }

    private fun processIndices(indices: IntArray): String {
        val vocabulary = loadVocabulary()
        val result = StringBuilder()
        for (idx in indices) {
            // منطق كود الاختبار: char_list[idx-1]
            if (idx > 0 && (idx - 1) < vocabulary.size) {
                result.append(vocabulary[idx - 1])
            }
        }
        return result.toString()
    }

    private fun loadVocabulary(): List<String> {
        return listOf(
            " ", "أ", "ب", "ت", "ث", "ج", "ح", "خ", "د", "ذ", "ر", "ز", "س", "ش", "ص", "ض", "ط", "ظ", "ع", "غ", "ف", "ق", "ك", "ل", "م", "ن", "هـ", "و", "ي", "ة", "ى", "ئ", "ء", "ؤ", "آ", "لا"
        )
    }

    private fun computeVolume(buffer: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) sum += (buffer[i] * buffer[i]).toDouble()
        return (sqrt(sum / size) / Short.MAX_VALUE).toFloat()
    }

    fun release() {
        stopRecording()
        interpreter?.close()
    }
}
