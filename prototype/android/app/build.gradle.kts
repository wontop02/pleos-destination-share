plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "kr.together.road"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "kr.together.road"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1" }
    buildFeatures { buildConfig = true }
    // Android's default debug key normally lives under ~/.android. Keep this project's key local.
    signingConfigs.getByName("debug") { storeFile = file("build/signing/debug.keystore") }
    buildTypes {
        debug { buildConfigField("String", "DEFAULT_SERVER", "\"http://10.0.2.2:4180\"") }
        release { isMinifyEnabled = false; buildConfigField("String", "DEFAULT_SERVER", "\"https://localhost:4180\"") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.all { it.systemProperty("pleos.repo", rootDir.parentFile.parentFile.absolutePath) } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val createLocalDebugKey by tasks.registering(Exec::class) {
    val key = layout.buildDirectory.file("signing/debug.keystore")
    outputs.file(key)
    onlyIf { !key.get().asFile.exists() }
    doFirst { key.get().asFile.parentFile.mkdirs() }
    commandLine("${System.getProperty("java.home")}/bin/keytool", "-genkeypair", "-noprompt", "-keystore", key.get().asFile.absolutePath,
        "-storepass", "android", "-keypass", "android", "-alias", "AndroidDebugKey", "-dname", "CN=Android Debug,O=Android,C=US", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000")
}
tasks.configureEach { if (name == "validateSigningDebug") dependsOn(createLocalDebugKey) }
