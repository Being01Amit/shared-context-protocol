pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Lets Gradle fetch the JDK 17 toolchain (ADR-17) on machines that only have a newer JDK,
// so the build target never depends on what a contributor happens to have installed.
// Version is inline rather than in the catalog (ADR-1): settings.gradle.kts declares the
// catalog, so it cannot resolve `libs` accessors for its own plugins block.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
