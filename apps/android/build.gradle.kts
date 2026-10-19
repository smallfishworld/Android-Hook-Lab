plugins { id("com.android.application") }

android {
    namespace = "dev.smallfish.hooklab"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.smallfish.hooklab"
        minSdk = 29
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0-m0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":engines:vector"))
    implementation(project(":libs:protocol"))
    testImplementation("junit:junit:4.13.2")
}
