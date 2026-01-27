# إعداد المشروع للبناء

## المشكلة
ملف `gradle/wrapper/gradle-wrapper.jar` غير موجود في المستودع.

## الحل التلقائي (موصى به)
GitHub Actions workflow تم إعداده ليقوم تلقائياً بتحميل gradle wrapper.
فقط ارفع الملفات إلى GitHub وسيعمل تلقائياً!

## الحل اليدوي (للبناء المحلي)

إذا أردت البناء محلياً على جهازك:

### الطريقة 1: استخدام gradle لتوليد wrapper
```bash
# إذا كان لديك gradle مثبت على النظام
gradle wrapper --gradle-version 8.2
```

### الطريقة 2: تحميل gradle-wrapper.jar يدوياً
```bash
# Linux/Mac
curl -L -o gradle/wrapper/gradle-wrapper.jar \
  https://raw.githubusercontent.com/gradle/gradle/v8.2.1/gradle/wrapper/gradle-wrapper.jar

# Windows (PowerShell)
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/gradle/gradle/v8.2.1/gradle/wrapper/gradle-wrapper.jar" `
  -OutFile "gradle/wrapper/gradle-wrapper.jar"
```

### الطريقة 3: استخدام Android Studio
1. افتح المشروع في Android Studio
2. سيقوم Android Studio تلقائياً بتحميل gradle wrapper
3. انتظر حتى ينتهي Gradle من التهيئة

## بعد ذلك
يمكنك البناء باستخدام:
```bash
./gradlew assembleRelease
```
