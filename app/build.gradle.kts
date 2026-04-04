plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.pickleball.video"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pickleball.video"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Firebase + API config from build
        buildConfigField("String", "API_BASE", "\"https://api.pickbase.asia/api\"")
        buildConfigField("String", "FIREBASE_DB_URL", "\"https://vdpr-45c0e-default-rtdb.asia-southeast1.firebasedatabase.app\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")

    // Android View (for SurfaceView used by RootEncoder)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // RootEncoder — RTMP streaming with camera
    implementation("com.github.pedroSG94.RootEncoder:rtplibrary:2.2.6")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-database-ktx")

    // Network
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Preferences (save settings)
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}
