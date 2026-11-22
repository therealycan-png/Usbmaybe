plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.pixelferry"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.pixelferry"
        minSdk = 29          // Android 10 = the last version on the original Pixel XL
        //noinspection ExpiredTargetSdkVersion  sideloaded app built for Android 10 storage rules
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("sideload") {
            storeFile = file("pixelferry-sideload.p12")
            storePassword = "pixelferry"
            keyAlias = "pixelferry"
            keyPassword = "pixelferry"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation(project(":core"))
}
