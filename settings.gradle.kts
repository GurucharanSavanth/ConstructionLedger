pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
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
    }
    versionCatalogs {
        create("libs") {
            from(files("android-native/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "ConstructionCost"
check(!file("app/build.gradle.kts").exists() && !file("gradle/libs.versions.toml").exists()) {
    "Use android-native/app and android-native/gradle/libs.versions.toml; do not restore the retired scaffold."
}
include(":app")
project(":app").projectDir = file("android-native/app")
