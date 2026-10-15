import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("rust")
}

val tauriProperties = Properties().apply {
    val propFile = file("tauri.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

val signing = Properties().apply {
    val props = file("../../../../signing/keystore.properties")
    if (props.exists()) props.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String) = signing.getProperty(key) ?: System.getenv(env)
val releaseStore = signingValue("storeFile", "RELEASE_STORE_FILE")

android {
    compileSdk = 36
    namespace = "ca.tariq_sekhri.time_tracker"
    defaultConfig {
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        applicationId = "ca.tariq_sekhri.time_tracker"
        minSdk = 33
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = tauriProperties.getProperty("tauri.android.versionCode", "1").toInt()
        versionName = tauriProperties.getProperty("tauri.android.versionName", "1.0")
    }
    sourceSets {
        getByName("main") {
            java.srcDir("../../../../app/src/main/java")
            res.setSrcDirs(listOf("../../../../app/src/main/res"))
            manifest.srcFile("../../../../app/src/main/AndroidManifest.xml")
        }
        getByName("test").java.srcDir("../../../../app/src/test/java")
        getByName("androidTest").java.srcDir("../../../../app/src/androidTest/java")
    }
    signingConfigs {
        if (!releaseStore.isNullOrBlank()) create("release") {
            storeFile = file(releaseStore)
            storePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            packaging {
                jniLibs.keepDebugSymbols.add("*/arm64-v8a/*.so")
                jniLibs.keepDebugSymbols.add("*/armeabi-v7a/*.so")
                jniLibs.keepDebugSymbols.add("*/x86/*.so")
                jniLibs.keepDebugSymbols.add("*/x86_64/*.so")
            }
        }
        getByName("release") {
            if (!releaseStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
            optimization {
               enable = false
            }
            proguardFiles(
                *fileTree(".") {
                  include("**/*.pro")
                  exclude("build/**")
                }.files.toTypedArray()
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

rust {
    rootDirRel = "../../../"
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

apply(from = file("tauri.build.gradle.kts"))
