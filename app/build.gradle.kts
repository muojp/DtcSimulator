plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Function to read .env file
fun loadEnvFile(): Map<String, String> {
    val envFile = file("${project.rootDir}/.env")
    val envMap = mutableMapOf<String, String>()

    if (envFile.exists()) {
        envFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2) {
                    envMap[parts[0].trim()] = parts[1].trim()
                }
            }
        }
    }
    return envMap
}

val envVars = loadEnvFile()

android {
    namespace = "jp.muo.dtc_simulator"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "jp.muo.dtc_simulator"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Load server configuration from .env or use defaults
        buildConfigField("String", "DEFAULT_SERVER_ADDRESS", "\"${envVars["DEFAULT_SERVER_ADDRESS"] ?: "192.168.0.157:8000"}\"")
        buildConfigField("String", "DEFAULT_SERVER_SECRET", "\"${envVars["DEFAULT_SERVER_SECRET"] ?: "test"}\"")
        buildConfigField("int", "DEFAULT_UDP_ECHO_PORT", "${envVars["DEFAULT_UDP_ECHO_PORT"] ?: "22840"}")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // AppCompat and Material Components for XML layouts
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")

    // Compose (keeping for future use)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}