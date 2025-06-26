plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.myapplication" // Keeping your original namespace
    compileSdk = 35 // Typically matches targetSdk for Android 14

    defaultConfig {
        applicationId = "com.example.myapplication" // Keeping your original application ID
        minSdk = 26 // Common minimum for Wear OS apps (Wear OS 2.0 and above)
        targetSdk = 35 // Targeting Android 14 (API 34)
        versionCode = 1
        versionName = "1.0"
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
        sourceCompatibility = JavaVersion.VERSION_1_8 // Keeping 1.8 as per your original, though 11 or 17 is more common with Kotlin 2.0
        targetCompatibility = JavaVersion.VERSION_1_8 // Keeping 1.8
    }

    kotlinOptions {
        jvmTarget = "1.8" // Keeping 1.8, but consider matching source/targetCompatibility to 11 or 17
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // For Kotlin 2.0.21 and Compose BOM 2024.09.00,
        // the recommended Kotlin Compiler Extension Version is often 1.6.0
        // Double-check the official Compose Kotlin Compatibility Map for the exact match.
        // As of 2024.09.00 BOM, a compiler version around 1.6.0 is typical for Kotlin 2.0.x
        kotlinCompilerExtensionVersion = "1.6.0" // This is crucial for compatibility
    }
}

dependencies {
    // AndroidX Core & Activity (adding core-ktx, as it's fundamental)
    implementation(libs.androidx.core.ktx) // Assuming you'll add this to your toml
    implementation(libs.activity.compose)

    // Google Play Services Wearable
    implementation(libs.play.services.wearable)

    // Compose BOM for general Compose libraries
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)

    // Wear OS Compose Libraries (using the aliases you provided, which correctly map to androidx.wear.compose)
    implementation(libs.compose.material) // This is androidx.wear.compose:compose-material
    implementation(libs.compose.foundation) // This is androidx.wear.compose:compose-foundation
    implementation(libs.wear.tooling.preview)

    // Wear Tiles Libraries
    implementation(libs.tiles)
    implementation(libs.tiles.material)
    implementation(libs.tiles.tooling.preview) // This is a tooling dependency, consider if it's debug or implementation

    // Horologist Libraries
    implementation(libs.horologist.compose.tools)
    implementation(libs.horologist.tiles)

    // Watchface Complications
    implementation(libs.watchface.complications.data.source.ktx)

    // Splash Screen
    implementation(libs.core.splashscreen)
    implementation(libs.androidx.material3.android)

    // Testing dependencies
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)

    // Debugging dependencies
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.tiles.tooling) // For tiles debugging
}