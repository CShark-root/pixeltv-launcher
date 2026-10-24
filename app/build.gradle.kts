import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.pixelden.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pixelden.launcher"
        minSdk = 28
        targetSdk = 34
        versionCode = 10
        versionName = "0.9.0"
        // Откуда приходят обновления (GitHub Releases).
        buildConfigField("String", "UPDATE_REPO", "\"${providers.gradleProperty("pixeltv.repo").getOrElse("")}\"")
    }

    // Релизный ключ хранится вне репозитория: ~/.pixeltv/keystore.properties (или путь из PIXELTV_KEYSTORE).
    // Без него релиз подписывается debug-ключом — так проект собирается у любого.
    val keyProps = Properties().apply {
        val f = file(System.getenv("PIXELTV_KEYSTORE") ?: "${System.getProperty("user.home")}/.pixeltv/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    // Ставит готовые профили компиляции Compose — меньше подлагиваний после установки.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
}
