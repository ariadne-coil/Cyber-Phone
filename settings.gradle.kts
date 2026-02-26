rootProject.name = "CyberPhone"
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Required for dependencies that are not published on Maven Central.
        // All such dependencies are pinned to immutable versions.
        maven { setUrl("https://jitpack.io") }
    }
}
include(":app")
include(":messages")
