plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.quantapp.trader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.quantapp.trader"
        minSdk = 26
        targetSdk = 34
        versionCode = 99
        versionName = "2.31.1"
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
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/quant.jks")
            storePassword = "Quant@2026#New"
            keyAlias = "quant"
            keyPassword = "Quant@2026#New"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.6.1")
}

// Sandbox egress: Robolectric fetches android-all jars on its own, so the test JVM
// must also go through the sandbox HTTP proxy.
tasks.withType<Test>().configureEach {
    systemProperty("http.proxyHost", "127.0.0.1")
    systemProperty("http.proxyPort", "18080")
    systemProperty("https.proxyHost", "127.0.0.1")
    systemProperty("https.proxyPort", "18080")
    systemProperty("http.nonProxyHosts", "localhost|127.0.0.1")
    systemProperty("https.nonProxyHosts", "localhost|127.0.0.1")
}