// Üst düzey yapılandırma: eklenti sürümleri burada sabitlenir, modüllerde uygulanır.
plugins {
    // AGP 8.6, compileSdk 35'i resmen destekler; Gradle 8.9 ile eşleşir.
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}
