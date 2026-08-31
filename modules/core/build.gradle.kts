plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

kotlin {
    explicitApi()
    jvmToolchain(21)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("detekt.yml"))
}

dependencies {
    // The architecture boundary: core sees ports and domain types, never adapters.
    api(project(":modules:model"))
    // A logging facade, not an adapter: the write path has one best-effort step (the markdown
    // mirror) whose failure is swallowed on purpose, and swallowing it silently is what made
    // the last persistence bug undiagnosable.
    implementation(libs.kotlin.logging)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
