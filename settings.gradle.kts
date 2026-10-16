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
        mavenCentral()
    }
}

rootProject.name = "walkcode"
include(":app")

// Vendored Termux terminal libraries (Apache 2.0), see vendor/README.md.
include(":terminal-emulator", ":terminal-view")
project(":terminal-emulator").projectDir = file("vendor/terminal-emulator")
project(":terminal-view").projectDir = file("vendor/terminal-view")
