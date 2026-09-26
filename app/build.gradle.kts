import java.util.Properties

// Alignment server settings. The API key must NOT be committed: put it in the untracked
// secrets.properties (alignment.apiKey=...) or the TWINBOOKS_API_KEY environment variable.
val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val alignmentBaseUrl: String = secrets.getProperty("alignment.baseUrl")
    ?: System.getenv("TWINBOOKS_BASE_URL")
    ?: "https://twinbooks.duckdns.org/api/v1/"
val alignmentApiKey: String = secrets.getProperty("alignment.apiKey")
    ?: System.getenv("TWINBOOKS_API_KEY")
    ?: ""

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.ecos.logic.twinbooks"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.ecos.logic.twinbooks"
        minSdk = 30
        targetSdk = 37
        versionCode = 3
        versionName = "2.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ALIGNMENT_BASE_URL", "\"$alignmentBaseUrl\"")
        buildConfigField("String", "ALIGNMENT_API_KEY", "\"$alignmentApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    // MediaSession + media notification (TTS controls on the lock screen)
    implementation(libs.androidx.media)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // EPUB
    implementation(libs.epub4j.core) {
        exclude(group = "xmlpull")
    }

    // ML Kit Translation (on-device, offline)
    implementation(libs.translate)
    
    // Play Services Tasks (for await() extension)
    implementation(libs.play.services.tasks)
    implementation(libs.kotlinx.coroutines.play.services)

    // MediaPipe Text Embedder (semantic similarity for sync)
    implementation(libs.tasks.text)

    // Network (Retrofit + OkHttp + Moshi)
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.moshi.adapters)
    implementation(libs.kotlinx.serialization.json)

    }
