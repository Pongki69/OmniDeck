plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Set by the release workflow from the git tag, as -PappVersion=1.2.3.
val appVersion = (findProperty("appVersion") as String?) ?: "1.0.0"
val versionParts = appVersion.split('.').map(String::toInt)

android {
    namespace = "com.remotegamestreamer.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.remotegamestreamer.client"
        minSdk = 26
        targetSdk = 35
        versionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
        versionName = appVersion
    }

    // Android only installs an update signed with the same key as the installed app.
    signingConfigs {
        create("release") {
            System.getenv("OMNIDECK_KEYSTORE")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("OMNIDECK_KEYSTORE_PASSWORD")
                keyAlias = "omnideck"
                keyPassword = System.getenv("OMNIDECK_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
