plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes -PbuildNumber=<workflow run number>; every build then gets a higher versionCode and
// a version name like 1.0.<run number>. Local builds are 1.0.0-dev.
val ciBuild = (project.findProperty("buildNumber") as String?)?.toIntOrNull()
val versionBase = (project.findProperty("versionBase") as String?) ?: "1.0"

android {
    namespace = "com.example.circlesearch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.circlesearch"
        minSdk = 33 // TouchInteractionController (four-finger gesture) needs API 33
        targetSdk = 34
        versionCode = ciBuild ?: 1
        versionName = if (ciBuild != null) "$versionBase.$ciBuild" else "$versionBase.0-dev"
    }

    // A fixed key (committed in the repo) so that every CI build is signed the same way and can be
    // installed over the previous one as an update. Personal sideloaded app only.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
