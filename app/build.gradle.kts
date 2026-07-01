import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val keystorePropertiesFile = rootProject.file("signing/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun signingProperty(name: String): String? =
    keystoreProperties.getProperty(name) ?: System.getenv(name)

val releaseStoreFile = signingProperty("RELEASE_STORE_FILE")
val hasReleaseSigning = !releaseStoreFile.isNullOrBlank()

val appVersionCode =
    (project.findProperty("APP_VERSION_CODE") as String?)?.toIntOrNull()
        ?: System.getenv("APP_VERSION_CODE")?.toIntOrNull()
        ?: 1

val appVersionName =
    (project.findProperty("APP_VERSION_NAME") as String?)
        ?: System.getenv("APP_VERSION_NAME")
        ?: "1.0"

android {
    namespace = "ca.tariq_sekhri.time_tracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "ca.tariq_sekhri.time_tracker"
        minSdk = 33
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = signingProperty("RELEASE_STORE_PASSWORD")
                keyAlias = signingProperty("RELEASE_KEY_ALIAS")
                keyPassword = signingProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
        debug {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.gson)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
