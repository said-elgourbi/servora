// Servora Android build entry point.
//
// The repository is one monorepo, so the Android build is rooted at `android/`
// instead of the repository root: the API and (later) the Angular workspace have
// their own toolchains and must not share a Gradle build (`Project.md` §12).

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // A module must not declare its own repositories, so every dependency has one
    // resolution source and cannot silently come from two places (`dev.md` §4).
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "servora-android"

include(":app")
