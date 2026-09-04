plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.netscript.meshchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.netscript.meshchat"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Nearby Connections API
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // DataStore (Preferences)
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // JSON
    implementation("org.json:json:20240303")
    //Splash - apenas para dar uma beleza ao Apk
    implementation("androidx.core:core-splashscreen:1.2.0")
    //Testes
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}
