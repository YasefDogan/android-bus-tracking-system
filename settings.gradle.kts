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
        google()
        mavenCentral()

        // 🗺️ Mapbox deposu (token ile birlikte)
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            authentication {
                create<BasicAuthentication>("basic")
            }
            credentials {
                username = "mapbox"
                password = "sk.eyJ1IjoieWFzZWYiLCJhIjoiY21nbWIyaXRtMDBnNTJscjJ0NmR5bXEwMCJ9.9suva33mkaRp8f-GxUd9tA"
            }
        }
    }
}

rootProject.name = "otobusTest"
include(":app")
