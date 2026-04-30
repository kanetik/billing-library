import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

// Coordinates published to Maven Central as `com.kanetik.billing:billing:<version>`.
// CI overrides VERSION_NAME via -PVERSION_NAME=<x.y.z> from the git tag (see
// .github/workflows/publish.yml). Local builds get the SNAPSHOT default so
// nothing accidentally publishes as a release version off main.
group = "com.kanetik.billing"
version = (findProperty("VERSION_NAME") as String?) ?: "0.1.0-SNAPSHOT"

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

// Maven Central publishing config. The vanniktech plugin handles AGP's
// singleVariant publishing wiring, sources/javadoc JAR generation, POM
// metadata, and GPG signing — see gradle.properties for SONATYPE_HOST and
// signing toggles.
mavenPublishing {
    coordinates("com.kanetik.billing", "billing", version.toString())

    pom {
        name.set("Kanetik Billing Library")
        description.set(
            "A coroutine-first wrapper around Google Play Billing Library 8.x. " +
                "Typed exception hierarchy with retry-type hints, lifecycle-aware " +
                "connection sharing, exponential backoff, and opt-in helpers " +
                "(signature verification, purchase-flow coordinator, activity validation)."
        )
        url.set("https://github.com/kanetik/billing-library")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("kanetik")
                name.set("Kanetik")
                email.set("billinglibrary@kanetik.com")
                url.set("https://github.com/kanetik")
            }
        }

        scm {
            url.set("https://github.com/kanetik/billing-library")
            connection.set("scm:git:https://github.com/kanetik/billing-library.git")
            developerConnection.set("scm:git:ssh://git@github.com/kanetik/billing-library.git")
        }
    }

    // Publish the release Android variant with sources + Javadoc JARs.
    // Maven Central rejects uploads missing either, so both flags must stay true.
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        )
    )

    // Upload to Sonatype Central Portal (the modern API; OSSRH was retired in 2024).
    // No `automaticRelease = true` for v0.1.0.x — manual "Release" click in the Portal
    // UI gives a final sanity check before the artifact goes public.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // Sign all publications iff signing.keyId / signing.password are present in
    // ~/.gradle/gradle.properties or env (or signingInMemoryKey via CI secrets).
    // Without those, signing tasks skip — local development is unblocked.
    signAllPublications()
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
