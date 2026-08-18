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

rootProject.name = "Daily Brief"

// The 2026-08-19 reshuffle: apps/ services/ packages/. Module names are unchanged so
// scripts and CI keep spelling :app, :server, :planning-core, :planning-contract.
include(":app")
project(":app").projectDir = file("apps/android")
include(":planning-core")
project(":planning-core").projectDir = file("packages/planning-core")
include(":planning-contract")
project(":planning-contract").projectDir = file("packages/planning-contract")
include(":server")
project(":server").projectDir = file("services/server")
