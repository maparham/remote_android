pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // Ignore repositories injected at project level (e.g. by ~/.gradle/init.d
    // scripts); otherwise Gradle drops the settings repos and Maven Central
    // artifacts such as kotlin-stdlib fail to resolve.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "lanremote"
include(":app")
