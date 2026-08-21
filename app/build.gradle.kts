import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val keystorePropertiesFile = rootProject.file("signing/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun signingProperty(vararg names: String): String? {
    for (name in names) {
        val prop = keystoreProperties.getProperty(name) ?: System.getenv(name)
        if (!prop.isNullOrBlank()) return prop
    }
    return null
}

val releaseStoreFile = signingProperty("RELEASE_STORE_FILE", "storeFile")
val releaseStorePassword = signingProperty("RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingProperty("RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingProperty("RELEASE_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

val appVersionCode =
    (project.findProperty("APP_VERSION_CODE") as String?)?.toIntOrNull()
        ?: System.getenv("APP_VERSION_CODE")?.toIntOrNull()
        ?: 7

val appVersionName =
    (project.findProperty("APP_VERSION_NAME") as String?)
        ?: System.getenv("APP_VERSION_NAME")
        ?: "0.5.0"

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
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
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
    implementation(libs.work.runtime)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
