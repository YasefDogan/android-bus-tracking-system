plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

    android {
        namespace = "com.example.otobustest"
        compileSdk = 36

        defaultConfig {
            applicationId = "com.example.otobustest"
            minSdk = 24
            targetSdk = 36
            versionCode = 1
            versionName = "1.0"

            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

            // 🗺️ Mapbox PUBLIC token
            manifestPlaceholders["MAPBOX_ACCESS_TOKEN"] =
                "ENTER_YOUR_MAPBOX_KEY_HERE"
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
            compose = true
        }

        composeOptions {
            kotlinCompilerExtensionVersion = "1.5.15"
        }
    }

    dependencies {
        // 🗺️ Mapbox SDK
        implementation("com.mapbox.maps:android:11.4.0")

        // 📍 Google Location Services
        implementation("com.google.android.gms:play-services-location:21.1.0")

        // 🌐 Ağ istekleri - OkHttp
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

        // 📦 JSON
        implementation("org.json:json:20231013")

        // 🔄 Coroutines
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

        // ♻️ Lifecycle
        implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
        implementation("androidx.lifecycle:lifecycle-service:2.7.0")

        // ⚙️ AndroidX temel kütüphaneler
        implementation("androidx.core:core-ktx:1.12.0")
        implementation("androidx.appcompat:appcompat:1.6.1")
        implementation("com.google.android.material:material:1.11.0")
        implementation("androidx.constraintlayout:constraintlayout:2.1.4")

        // 🎨 Jetpack Compose kütüphaneleri
        implementation(platform("androidx.compose:compose-bom:2024.09.00"))
        implementation("androidx.activity:activity-compose:1.9.2")
        implementation("androidx.compose.ui:ui")
        implementation("androidx.compose.ui:ui-graphics")
        implementation("androidx.compose.ui:ui-tooling-preview")
        implementation("androidx.compose.material3:material3")

        // 🧪 Test kütüphaneleri
        testImplementation("junit:junit:4.13.2")
        androidTestImplementation("androidx.test.ext:junit:1.1.5")
        androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
        androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
        androidTestImplementation("androidx.compose.ui:ui-test-junit4")

        // 🧩 Debug için Compose araçları
        debugImplementation("androidx.compose.ui:ui-tooling")
        debugImplementation("androidx.compose.ui:ui-test-manifest")
    }
