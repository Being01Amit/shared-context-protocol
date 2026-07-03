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
