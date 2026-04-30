plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    // Match the library's JVM target so we don't accidentally test against a
    // newer language level than consumers will have.
    jvmToolchain(11)
}

android {
    namespace = "com.kanetik.billing.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kanetik.billing.sample"
        // Match the library minSdk so we exercise the same surface consumers
        // hit on their lowest supported devices.
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_11)
        targetCompatibility(JavaVersion.VERSION_11)
    }
}

dependencies {
    // Project reference (not the published artifact) so library changes
    // propagate to the sample on the next rebuild — no publish step needed
    // during library development.
    implementation(project(":billing"))

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)

    // Compose BOM is the single version source for the Compose UI/material/tooling
    // libraries; the per-library entries are deliberately version-less.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.tooling)
}
