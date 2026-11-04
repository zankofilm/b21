plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val apiBaseUrl = (providers.gradleProperty("BAZR_API_BASE_URL").orNull ?: "https://example.invalid/api/")
require(apiBaseUrl.startsWith("https://") && apiBaseUrl.endsWith("/")) {
    "BAZR_API_BASE_URL must be an HTTPS URL ending in /"
}
android {
    namespace = "ir.bazr.inspection"
    compileSdk = 35
    defaultConfig {
        applicationId = "ir.bazr.inspection"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0-official-ui"
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
}
