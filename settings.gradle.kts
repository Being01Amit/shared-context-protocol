pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "scp"

include(
    ":modules:model",
    ":modules:core",
    ":modules:database",
    ":modules:search",
    ":modules:markdown",
    ":modules:config",
    ":modules:skills",
    ":apps:mcp-server",
    ":apps:cli",
)
// :modules:api is a reserved slot for the optional Ktor HTTP surface — not built in v1.
