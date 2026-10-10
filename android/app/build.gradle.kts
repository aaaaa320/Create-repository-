plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "temizweb.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "temizweb.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // İmza yapılandırması bilinerek boş bırakıldı: bu depo herkese açık bir
            // başlangıç projesidir, anahtar dosyası depoya girmez. Yayın derlemesi için
            // `signingConfig` ekleyin veya `assembleDebug` çıktısını yan yükleyin.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Alan adı listeleri derleme çıktısına olduğu gibi kopyalanır; sıkıştırılmaz.
    androidResources {
        noCompress += "txt"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Test hataları CI günlüğünde beklenen/gerçekleşen değerleriyle görünsün.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
