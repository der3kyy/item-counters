dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.purpurmc.org/snapshots/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.helpch.at/releases") {
            content { includeGroup("me.clip") }
        }
    }
}

rootProject.name = "item-counters"
