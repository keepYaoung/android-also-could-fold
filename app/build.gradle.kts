plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.tommy.foldshell"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.tommy.foldshell"
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.1"
    }

    buildFeatures { aidl = true; buildConfig = true }
    sourceSets.getByName("main").java.srcDir("../system/src")

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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
