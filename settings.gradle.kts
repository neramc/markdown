pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://packages.jetbrains.team/maven/p/kpm/public/")
    }
}

// Auto-provisions the JetBrains Runtime toolchain declared in gradle/libs.versions.toml, so a
// developer with any JDK installed can still build and run.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        // Compose Multiplatform 1.11 resolves its androidx dependencies (collection, annotation,
        // lifecycle, savedstate) from Google's Maven repository; Maven Central alone cannot
        // resolve the build.
        google()
        maven("https://packages.jetbrains.team/maven/p/kpm/public/")
    }
}

rootProject.name = "quill"

include(":quill-bridge")
include(":quill-app")
