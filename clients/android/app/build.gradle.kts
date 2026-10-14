plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Distribution versions must not silently change the persisted mobile state identity.
// Rust software follows VERSION; MobileContractV1 retains the explicit 1.0.0 / v1 contract.
val workspaceVersion = file("../../../VERSION").readText().trim()
val emulatorTests = providers.gradleProperty("xszcEmulatorTests").orNull == "true"

val semanticVersion = workspaceVersion.substringBefore('-').split('.').map(String::toInt)
require(semanticVersion.size == 3) { "Workspace version must use major.minor.patch" }
require(workspaceVersion == "1.1.5") {
    "This release builds xszc 1.1.5"
}

val releasePkcs12Path = providers
    .environmentVariable("XSZC_ANDROID_SIGNING_PKCS12_PATH")
    .orNull
    ?.takeIf { it.isNotBlank() }
val releasePkcs12Password = providers
    .environmentVariable("XSZC_ANDROID_SIGNING_PKCS12_PASSWORD")
    .orNull
    ?.takeIf { it.isNotEmpty() }

// `gradle build` also reaches Release tasks even though the requested task name
// does not contain "Release". Inspect the resolved graph so no indirect command
// can emit an unsigned formal APK.
gradle.taskGraph.whenReady {
    val buildsReleaseVariant = allTasks.any { task ->
        task.project == project && task.name.contains("Release", ignoreCase = true)
    }
    if (buildsReleaseVariant) {
        require(!emulatorTests) { "Emulator ABI is restricted to debug tests" }
        val signingPath = requireNotNull(releasePkcs12Path) {
            "Release tasks require XSZC_ANDROID_SIGNING_PKCS12_PATH"
        }
        requireNotNull(releasePkcs12Password) {
            "Release tasks require XSZC_ANDROID_SIGNING_PKCS12_PASSWORD"
        }
        require(file(signingPath).isFile) {
            "XSZC_ANDROID_SIGNING_PKCS12_PATH must name a regular file"
        }
    }
}

android {
    namespace = "org.sarmg.xszc"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "org.sarmg.xszc"
        minSdk = 37
        targetSdk = 37
        versionCode = semanticVersion[0] * 1_000_000 + semanticVersion[1] * 1_000 + semanticVersion[2]
        versionName = workspaceVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += if (emulatorTests) "x86_64" else "arm64-v8a"
        }
    }

    signingConfigs {
        releasePkcs12Path?.let { signingPath ->
            releasePkcs12Password?.let { signingPassword ->
                create("release") {
                    storeFile = file(signingPath)
                    storePassword = signingPassword
                    keyAlias = "xszc-android-release"
                    keyPassword = signingPassword
                    storeType = "PKCS12"
                }
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.9.2")
    implementation("androidx.media3:media3-ui:1.9.2")
    implementation("androidx.media3:media3-datasource-okhttp:1.9.2")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
