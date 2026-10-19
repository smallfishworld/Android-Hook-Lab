plugins {
    id("com.android.application")
}

android {
    namespace = "dev.smallfish.hooklab.demo"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.smallfish.hooklab.demo"
        minSdk = 29
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
