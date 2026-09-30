plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.carassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.carassistant"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "4.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.media3:media3-session:1.8.1")
    implementation("androidx.media3:media3-exoplayer:1.8.1")
}
