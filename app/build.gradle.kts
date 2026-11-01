plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes the run number so every build installs as an update over the last one.
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()

android {
    namespace = "app.bagtimer"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.bagtimer"
        minSdk = 26          // Android 8.0: PiP, notification channels. Covers Fire OS 7 and 8.
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("release") {
            // A fixed key, so each new APK installs over the old one without uninstalling.
            storeFile = file("../signing/bagtimer.jks")
            storePassword = "bagtimer"
            keyAlias = "bagtimer"
            keyPassword = "bagtimer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
