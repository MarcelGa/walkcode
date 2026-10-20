plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.walkcode.app"
    // Current AndroidX (Compose BOM 2026.09) requires compiling against 37.
    // Runtime behaviour is governed by targetSdk, which stays at 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.walkcode.app"
        minSdk = 35
        targetSdk = 36
        // CI sets these from the tag and the run number. Obtainium and Android only
        // accept an update whose versionCode is higher than the installed one.
        versionCode = System.getenv("WALKCODE_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("WALKCODE_VERSION_NAME") ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release APKs are signed with a stable key so each new build installs over the
    // previous one. Without WALKCODE_KEYSTORE_FILE (local builds) they fall back to the
    // debug key, so the signing key is never silently missing on a published build.
    signingConfigs {
        create("release") {
            System.getenv("WALKCODE_KEYSTORE_FILE")?.let { keystore ->
                storeFile = file(keystore)
                storePassword = System.getenv("WALKCODE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WALKCODE_KEY_ALIAS")
                keyPassword = System.getenv("WALKCODE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("WALKCODE_KEYSTORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // BouncyCastle ships multi-release jars with clashing metadata.
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/{LICENSE,NOTICE}.md",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":terminal-view"))

    implementation(libs.sshj)
    // sshj only declares BouncyCastle as a runtime dependency; we use it directly.
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Config export/import file only; kotlinx.serialization is JetBrains, not Play/GMS.
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
