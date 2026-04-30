plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    // JVM 11 keeps the AAR consumable by any Android consumer on JDK 11+.
    // We use no JDK 17+ language or API features; bumping higher would only
    // shrink the consumer pool with no upside.
    jvmToolchain(11)
}

android {
    namespace = "com.kanetik.billing"
    compileSdk = 36

    defaultConfig {
        // PBL 8.1.0 raised the minimum supported SDK to 23 (Android 6.0). We pin
        // to PBL 8.3.0, so 23 is the floor — going lower would let consumers hit
        // a runtime crash on API 21–22 devices.
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_11)
        targetCompatibility(JavaVersion.VERSION_11)
    }

    testOptions {
        unitTests {
            // PBL types call into android.text.TextUtils, android.os.Looper, etc.
            // during validation in their builders. Without this flag, those
            // stubbed methods throw "not mocked" RuntimeExceptions in pure-JVM
            // tests; with it, they return safe defaults (false / 0 / null) so
            // tests can exercise our wrapper logic without dragging in
            // Robolectric.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Public API surface — types appear in consumer code.
    api(libs.play.billing.ktx)
    api(libs.kotlinx.coroutines.core)
    api(libs.lifecycle.common)

    // Internal implementation — not exposed to consumers.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
