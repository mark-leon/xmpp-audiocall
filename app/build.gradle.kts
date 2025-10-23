plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.xmppaudiocall"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.xmppaudiocall"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("org.igniterealtime.smack:smack-android:4.4.6") {
        exclude(group = "xpp3")
    }


    implementation("org.igniterealtime.smack:smack-tcp:4.4.6") {
        exclude(group = "xpp3")
    }
    implementation("org.igniterealtime.smack:smack-im:4.4.6") {
        exclude(group = "xpp3")
    }
    implementation("org.igniterealtime.smack:smack-extensions:4.4.6") {
        exclude(group = "xpp3")
    }

    implementation ("org.igniterealtime.smack:smack-experimental:4.4.6")

    // WebRTC
    implementation ("io.getstream:stream-webrtc-android:1.3.8")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
}