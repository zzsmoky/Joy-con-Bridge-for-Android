pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(rootDir.resolve("local-maven")) }
        google()
        mavenCentral()
    }
}

rootProject.name = "JoyConBridge"
include(":app")
