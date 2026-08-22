plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "vn.vdpr.video"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.vdpr.video"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "1.1.10"

        // Firebase + API config from build
        buildConfigField("String", "API_BASE", "\"https://backend.vdpr.vn/api\"")
        buildConfigField("String", "FIREBASE_DB_URL", "\"https://vdpr-live-2b3b5-default-rtdb.asia-southeast1.firebasedatabase.app\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("../pickbase-release.jks")   // đường dẫn tới keystore
            storePassword = System.getenv("KEYSTORE_PASS") ?: ""
            keyAlias = "pickbase"
            keyPassword = System.getenv("KEY_PASS") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")

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
