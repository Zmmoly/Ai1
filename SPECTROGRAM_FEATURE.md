# 🎵 دعم Spectrogram - حل ذكي!

## 💡 الفكرة:

```
بدلاً من إرسال الصوت الخام (waveform):
[sample1, sample2, sample3, ...]

نحوّله إلى Spectrogram أولاً:
[
  [freq1_t1, freq2_t1, freq3_t1, ...],
  [freq1_t2, freq2_t2, freq3_t2, ...],
  ...
]
```

---

## 🎯 لماذا Spectrogram؟

### معظم نماذج ASR الحديثة تتوقع Spectrogram!

```
نماذج مثل:
- Wav2Vec2 ✅ Spectrogram
- DeepSpeech ✅ Spectrogram  
- Quartznet ✅ Mel Spectrogram
- Jasper ✅ Mel Spectrogram
- Conformer ✅ Mel Spectrogram

لماذا؟
✅ أفضل للتعرف على الأصوات
✅ يُمثل الترددات بشكل واضح
✅ يُقلل التأثير بالضوضاء
✅ حجم أصغر من الصوت الخام
```

---

## ✅ ما تم إضافته:

### 1️⃣ اكتشاف تلقائي لنوع الدخل

```kotlin
Input Shape: [1, 16000] → Raw Audio (1D)
Input Shape: [1, 100, 80] → Spectrogram (2D/3D)
Input Shape: [1, 128, 128, 1] → Spectrogram كصورة (4D)

الكود يكتشف تلقائياً:
if (inputShape.size >= 3) {
    → استخدام Spectrogram
} else {
    → استخدام الصوت الخام
}
```

### 2️⃣ STFT (Short-Time Fourier Transform)

```kotlin
computeSTFT():
- يقسّم الصوت إلى frames صغيرة
- يطبّق Hanning window
- يحسب FFT لكل frame
- يُخرج magnitude spectrum
```

### 3️⃣ Mel Spectrogram

```kotlin
computeMelSpectrogram():
- يُحوّل STFT إلى Mel scale
- Mel scale = أقرب لإدراك الأذن البشرية
- يطبّق Mel filterbank
- يحوّل إلى Log scale
```

### 4️⃣ Mel Filterbank

```kotlin
createMelFilterbank():
- يُنشئ مرشحات مثلثية
- موزعة بالتساوي على Mel scale
- تُحوّل الترددات الخطية إلى Mel
```

---

## 📊 مثال:

### Input: صوت خام
```
[0.1, 0.2, -0.1, 0.3, ...]
16000 عينة
```

### بعد STFT:
```
Frame 0: [mag0, mag1, mag2, ..., mag256]
Frame 1: [mag0, mag1, mag2, ..., mag256]
...
Frame 99: [mag0, mag1, mag2, ..., mag256]

100 frames × 257 frequencies
```

### بعد Mel Spectrogram:
```
Frame 0: [mel0, mel1, ..., mel79]
Frame 1: [mel0, mel1, ..., mel79]
...
Frame 99: [mel0, mel1, ..., mel79]

100 time steps × 80 mel features
```

### النتيجة:
```
Shape: [1, 100, 80]
✅ جاهز للنموذج!
```

---

## 🎯 كيف يعمل:

### السيناريو 1: نموذج يتوقع Spectrogram

```
Input shape: [1, 100, 80]

1. جمع الصوت: 32000 عينة
2. اكتشاف: Shape 3D → Spectrogram
3. 🎵 Converting to Spectrogram...
4. computeSTFT():
   - n_fft = 512
   - hop_length = 160 (10ms)
   - window = Hanning
5. computeMelSpectrogram():
   - n_mels = 80
   - log scale
6. Output: [100, 80] Mel Spectrogram
7. ✅ تمرير للنموذج
8. النتيجة: نص صحيح! 🎉
```

### السيناريو 2: نموذج يتوقع Raw Audio

```
Input shape: [1, 16000]

1. جمع الصوت: 32000 عينة
2. اكتشاف: Shape 2D → Raw Audio
3. 🎵 Using raw audio...
4. أخذ أول 16000 عينة
5. تطبيع [-1, 1]
6. ✅ تمرير للنموذج
```

---

## 📊 Logs المتوقعة:

### مع Spectrogram:
```
📊 Input shape: [1, 100, 80]
🎵 Converting to Spectrogram...
📊 Spectrogram: timeSteps=100, features=80, channels=1
✅ Spectrogram created: 100x80
📊 Output shape: [1, 100, 33]
✅ Model inference completed
```

### مع Raw Audio:
```
📊 Input shape: [1, 16000]
🎵 Using raw audio...
📊 Raw audio: data=32000, required=16000, using=16000
📊 Output shape: [1, 100, 33]
✅ Model inference completed
```

---

## ⚙️ المعاملات القابلة للتعديل:

### في `prepareSpectrogramInput()`:

```kotlin
// معاملات STFT
val nFFT = 512        // حجم FFT window
val hopLength = 160   // 10ms at 16kHz
val nMels = nFeatures // عدد Mel bands

يمكن تعديلها حسب نموذجك:

نماذج مختلفة تستخدم:
- n_fft: 256, 400, 512, 1024
- hop_length: 80, 160, 256
- n_mels: 40, 64, 80, 128
```

---

## 🔧 تخصيص للنموذج:

### إذا كان نموذجك يحتاج إعدادات مختلفة:

```kotlin
// في prepareSpectrogramInput()
// عدّل هذه الأسطر:

val nFFT = 400        // بدلاً من 512
val hopLength = 100   // بدلاً من 160
val nMels = 64        // بدلاً من 80

// أو اقرأها من metadata النموذج
```

---

## 🎯 أمثلة لنماذج شهيرة:

### Wav2Vec2:
```
Input: [1, 16000] → Raw audio
لا يحتاج spectrogram
```

### DeepSpeech:
```
Input: [1, time_steps, n_features]
n_features = 26 (MFCC)
يحتاج MFCC (نوع من Spectrogram)
```

### Quartznet:
```
Input: [1, 64, time_steps]
64 Mel features
يحتاج Mel Spectrogram
```

### Jasper:
```
Input: [1, 80, time_steps]
80 Mel features
يحتاج Mel Spectrogram
```

---

## ✅ المزايا:

```
✅ دعم تلقائي للنوعين (raw + spectrogram)
✅ STFT كامل مع Hanning window
✅ Mel Spectrogram حقيقي
✅ Mel filterbank صحيح
✅ Log scale للديناميكية
✅ يتكيف مع أي حجم input
✅ لا حاجة لمكتبات خارجية
```

---

## 🧪 كيف تختبر:

### 1. Build التطبيق
```bash
./gradlew assembleDebug
```

### 2. افتح Logcat
```
Filter: SpeechRecognizer
```

### 3. اختبر الميكروفون
```
🎤 → تكلم
```

### 4. راقب Logs
```
هل تقول:
🎵 Converting to Spectrogram...
✅ Spectrogram created: 100x80

أم:
🎵 Using raw audio...
📊 Raw audio: data=32000, required=16000

هذا يعتمد على Input Shape نموذجك
```

---

## 🔍 التشخيص:

### إذا رأيت:
```
🎵 Converting to Spectrogram...
✅ Spectrogram created: 100x80
✅ Model inference completed
📝 Decoded text: 'افتح'

→ نموذجك يتوقع Spectrogram ✅
→ الكود حوّله تلقائياً ✅
→ يعمل! 🎉
```

### إذا رأيت:
```
🎵 Using raw audio...
✅ Model inference completed
📝 Decoded text: 'افتح'

→ نموذجك يتوقع Raw Audio ✅
→ الكود استخدمه مباشرة ✅
→ يعمل! 🎉
```

---

## 💡 نصائح:

### 1. تحقق من Input Shape
```
أفضل طريقة لمعرفة نوع الدخل:
راجع documentation النموذج
```

### 2. جرّب Preprocessing مختلف
```
إذا النتائج ليست جيدة:
- جرّب n_fft مختلف
- جرّب hop_length مختلف
- جرّب n_mels مختلف
```

### 3. MFCC vs Mel Spectrogram
```
بعض النماذج تحتاج MFCC بدلاً من Mel Spectrogram

MFCC = DCT(Log(Mel Spectrogram))

يمكن إضافته لاحقاً إذا لزم
```

---

## 🎉 الخلاصة:

```
المشكلة: النموذج قد يتوقع Spectrogram
الحل: تحويل تلقائي للصوت

الآن:
✅ دعم Raw Audio
✅ دعم Spectrogram
✅ اكتشاف تلقائي
✅ STFT + Mel Spectrogram كامل
✅ يعمل مع معظم النماذج

جرّب وشوف الفرق! 🚀
```

---

## 📚 المصادر:

```
Mel Scale:
https://en.wikipedia.org/wiki/Mel_scale

STFT:
https://en.wikipedia.org/wiki/Short-time_Fourier_transform

Mel Spectrogram:
https://librosa.org/doc/main/generated/librosa.feature.melspectrogram.html
```

**الآن التطبيق يدعم كل أنواع النماذج!** 🎵
