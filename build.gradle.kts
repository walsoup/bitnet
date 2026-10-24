plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Transitive versions pinned to ones that are complete in the local Gradle cache, so the
// project builds with --offline. Raise them freely when building online.
val pinnedVersions = mapOf(
    "androidx.activity:activity" to "1.9.3",
    "androidx.activity:activity-ktx" to "1.9.3",
    "androidx.activity:activity-compose" to "1.9.3",
    "androidx.core:core" to "1.15.0",
    "androidx.core:core-ktx" to "1.15.0",
    "androidx.annotation:annotation" to "1.9.1",
    "androidx.annotation:annotation-jvm" to "1.9.1",
    "androidx.collection:collection" to "1.4.4",
    "androidx.collection:collection-jvm" to "1.4.4",
    "androidx.profileinstaller:profileinstaller" to "1.4.1",
    "org.jetbrains.kotlin:kotlin-stdlib-jdk7" to "1.9.24",
    "org.jetbrains.kotlin:kotlin-stdlib-jdk8" to "1.9.24",
    "org.jetbrains.kotlinx:kotlinx-coroutines-bom" to "1.9.0",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core" to "1.9.0",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm" to "1.9.0",
    "org.jetbrains.kotlinx:kotlinx-coroutines-android" to "1.9.0",
)

subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            pinnedVersions["${requested.group}:${requested.name}"]?.let { useVersion(it) }
        }
    }
}
