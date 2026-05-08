# Installation

The library is published to Maven Central as `com.kanetik.billing:billing`.

## Kotlin DSL (`build.gradle.kts`)

```kotlin
dependencies {
    implementation("com.kanetik.billing:billing:0.1.1")
}
```

## Groovy DSL (`build.gradle`)

```groovy
dependencies {
    implementation 'com.kanetik.billing:billing:0.1.1'
}
```

## Version catalog (`gradle/libs.versions.toml`)

```toml
[versions]
kanetik-billing = "0.1.1"

[libraries]
kanetik-billing = { module = "com.kanetik.billing:billing", version.ref = "kanetik-billing" }
```

Then in your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.kanetik.billing)
}
```

## Maven

```xml
<dependency>
    <groupId>com.kanetik.billing</groupId>
    <artifactId>billing</artifactId>
    <version>0.1.1</version>
</dependency>
```

## Requirements

- **`minSdk = 23`** — PBL 8.1's floor. The library pins Play Billing Library to 8.3.0.
- **JVM target 11** — produced AAR targets JDK 11 bytecode. Your app can build with any newer JDK (the Gradle daemon needs JDK 17+ for Gradle 9.x compatibility, but that's a build-time concern, not a target).
- **AndroidX** — required (the library uses `androidx.lifecycle.*`).

## Verify the integration

After adding the dependency and syncing Gradle, `import com.kanetik.billing.BillingRepositoryCreator` in any Kotlin file. If your IDE resolves it, you're set. The [Quick start](quick-start.md) is the next step.
