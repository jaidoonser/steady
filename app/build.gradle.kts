plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.jaiden.steady"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jaiden.steady"
        minSdk = 26
        targetSdk = 36
        versionCode = vCode
        versionName = "1.0.$vCode"
    }

    // The signing key is NOT in this repo. Official builds are signed privately; set STEADY_KEYSTORE etc. to sign your own.
    val ksPath = System.getenv("STEADY_KEYSTORE")
    signingConfigs {
        create("steady") {
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("STEADY_STORE_PASSWORD")
                keyAlias = System.getenv("STEADY_KEY_ALIAS")
                keyPassword = System.getenv("STEADY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (ksPath != null) signingConfig = signingConfigs.getByName("steady")
        }
        getByName("debug") {
            if (ksPath != null) signingConfig = signingConfigs.getByName("steady")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
