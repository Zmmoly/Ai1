# API خدمة إمكانية الوصول

## الوصول إلى الخدمة

```kotlin
val service = MyAccessibilityService.getInstance()
if (service != null) {
    // الخدمة مفعلة وجاهزة
} else {
    // الخدمة غير مفعلة - اطلب من المستخدم تفعيلها
}
```

## الوظائف المتاحة

### 1. النقر على إحداثيات
```kotlin
service?.performClick(x = 500f, y = 1000f)
```

### 2. السحب (Swipe)
```kotlin
service?.performSwipe(
    startX = 500f, 
    startY = 1500f,
    endX = 500f,
    endY = 500f
)
```

### 3. قراءة النص من الشاشة
```kotlin
val text = service?.getScreenText()
```

### 4. النقر على عنصر بالنص
```kotlin
service?.clickByText("موافق")
```

### 5. أخذ لقطة شاشة
```kotlin
service?.takeScreenshot()
```

### 6. قراءة حقل نصي
```kotlin
val value = service?.readTextField("com.example:id/username")
```

### 7. الكتابة في حقل
```kotlin
service?.writeToField("com.example:id/username", "test@example.com")
```

## الصلاحيات المتاحة

✅ `typeAllMask` - جميع الأحداث
✅ `canPerformGestures` - تنفيذ الإيماءات (نقر، سحب)
✅ `canRetrieveWindowContent` - قراءة محتوى الشاشة
✅ `canTakeScreenshot` - أخذ لقطات شاشة (Android 11+)
✅ `canRequestTouchExplorationMode` - وضع اللمس الاستكشافي
✅ `canRequestEnhancedWebAccessibility` - دعم محسن للويب
✅ `canRequestFilterKeyEvents` - فلترة أحداث لوحة المفاتيح
✅ `canControlMagnification` - التحكم في التكبير
✅ `canRequestFingerprint` - طلب بصمة الإصبع
✅ `flagReportViewIds` - الحصول على IDs العناصر
✅ `flagRetrieveInteractiveWindows` - الوصول للنوافذ التفاعلية

## مثال: إضافة زر في MainActivity

```kotlin
val testButton = Button(this).apply {
    text = "نقرة تلقائية"
    setOnClickListener {
        val service = MyAccessibilityService.getInstance()
        if (service != null) {
            // نفذ نقرة على منتصف الشاشة
            service.performClick(540f, 960f)
            Toast.makeText(this@MainActivity, "تم النقر", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this@MainActivity, 
                "يجب تفعيل خدمة إمكانية الوصول أولاً",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
layout.addView(testButton)
```

## التحقق من حالة الخدمة

```kotlin
fun isAccessibilityServiceEnabled(): Boolean {
    val service = MyAccessibilityService.getInstance()
    return service != null
}
```

## ملاحظات مهمة

⚠️ **الخدمة حالياً جاهزة للاستخدام لكن الوظائف تحتاج للتنفيذ**

كل وظيفة مكتوبة بـ `TODO` يمكنك تنفيذها حسب الحاجة:
- `performClick()` - استخدم `GestureDescription`
- `performSwipe()` - استخدم `GestureDescription` مع `Path`
- `getScreenText()` - استخدم `rootInActiveWindow`
- `takeScreenshot()` - استخدم `takeScreenshot()` API (Android 11+)

## الأمان

⚠️ هذه الصلاحيات قوية جداً:
- يمكنها قراءة كل شيء على الشاشة
- يمكنها النقر على أي شيء
- يمكنها التحكم بالتطبيقات الأخرى

**استخدمها بمسؤولية!**
