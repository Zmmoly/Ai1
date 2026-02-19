plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.awab.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.awab.ai"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // تقليل حجم التطبيق - استهداف معمارية arm64 فقط (أغلب الأجهزة الحديثة)
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }

    signingConfigs {
        create("release") {
            // ملاحظة: تأكد من وجود ملف debug.keystore في مجلد المشروع
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true      // إزالة الكود غير المستخدم
            isShrinkResources = true    // إزالة الموارد (الصور/الملفات) غير المستخدمة
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
    
    buildFeatures {
        viewBinding = true
        mlModelBinding = true
    }
}

dependencies {
    // AndroidX & UI
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
    
    // TensorFlow Lite - مكتبات الذكاء الاصطناعي
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.14.0")

    // OkHttp - للاتصال بـ Deepgram عبر WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Kotlin Coroutines - للتسجيل الصوتي غير المتزامن
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```
