plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.secured"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.secured"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.5.3"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("JARVIS_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("JARVIS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("JARVIS_KEY_ALIAS")
                keyPassword = System.getenv("JARVIS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
