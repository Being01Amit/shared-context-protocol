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
    api(project(":modules:model"))
    // The search adapter composes over generated typed queries instead of duplicating
    // schema knowledge (docs/01 §2 note); core still only sees the SearchIndex port.
    implementation(project(":modules:database"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
