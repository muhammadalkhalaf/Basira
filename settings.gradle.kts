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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Meta Wearables Device Access Toolkit (com.meta.wearable) is published to Maven Central.
        mavenCentral()
    }
}

rootProject.name = "Basira"
include(":app", ":core", ":domain")
