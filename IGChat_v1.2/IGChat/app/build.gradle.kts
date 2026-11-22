plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "sk.filip.igchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "sk.filip.igchat"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    // Pevný kľúč – každá nová verzia sa dá nainštalovať ako aktualizácia
    signingConfigs {
        create("igchat") {
            storeFile = file("igchat.keystore")
            storePassword = "igchat123"
            keyAlias = "igchat"
            keyPassword = "igchat123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("igchat")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("igchat")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
