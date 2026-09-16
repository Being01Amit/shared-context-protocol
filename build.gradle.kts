// Root build file: declares plugin versions once (via the version catalog) and holds no code.
// Each module applies the plugins it needs; the module dependency graph enforces the
// architecture boundary (core -> model only), not convention.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// `-Pversion=x.y.z` only sets the invoked (root) project's version by default; this
// propagates it to every subproject so release archives are named/tagged consistently.
// Without -Pversion, findProperty("version") is Gradle's "unspecified" placeholder rather than null,
// so the fallback has to test for it explicitly or it never applies.
allprojects {
    version = (rootProject.findProperty("version") as String?)?.takeUnless { it == "unspecified" } ?: "0.0.0-dev"
}
