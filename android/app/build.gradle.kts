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
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// The computer sender ships inside the app, so the Pixel can hand it to your computer.
val copySender by tasks.registering(Copy::class) {
    from(rootProject.file("../desktop")) { include("pixelferry_send.py", "PixelFerry Send.bat", "pixelferry-send.sh") }
    into(layout.buildDirectory.dir("generated/senderAssets/sender"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/senderAssets"))
tasks.named("preBuild") { dependsOn(copySender) }

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(kotlin("test"))
    testImplementation("com.google.android.apps.common.testing.accessibility.framework:accessibility-test-framework:4.1.1")
}
