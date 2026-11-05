plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "ai.pleos.destinations.driver"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.pleos.destinations.driver"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-issue14-test-only"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
kotlin { jvmToolchain(21) }
dependencies { implementation("ai.pleos.playground:NaviHelper:3.0.5") }
