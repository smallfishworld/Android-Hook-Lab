plugins { id("com.android.library") }

android {
    namespace = "dev.smallfish.hooklab.hook"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation(project(":libs:protocol"))
}
