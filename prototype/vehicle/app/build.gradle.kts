plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "ai.pleos.destinations.vehicle"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.pleos.destinations.vehicle"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.4.0-issue29"
        testApplicationId = "ai.pleos.destinations.vehicle.probe"
        testInstrumentationRunner = "ai.pleos.destinations.vehicle.NaviSdkProbe"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
    testOptions.unitTests.all {
        it.systemProperty("prototype.server.path", rootProject.file("../server.mjs").absolutePath)
        // HTTP/SSE integration tests execute this source in a real Node process.
        it.inputs.file(rootProject.file("../server.mjs"))
    }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core"))
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
