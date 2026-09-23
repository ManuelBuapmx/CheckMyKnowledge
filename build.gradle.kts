plugins {
    id("com.android.application") version "8.5.2"
}

android {
    namespace = "com.prismalmesh.checkmyknowledge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.prismalmesh.checkmyknowledge"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
