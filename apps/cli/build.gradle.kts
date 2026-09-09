plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    application
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("detekt.yml"))
}

application {
    mainClass.set("com.scp.cli.MainKt")
    // Not "scp": that is OpenSSH's secure-copy command on every Unix system and on
    // Windows (System32\OpenSSH), and putting ours on PATH would shadow it.
    applicationName = "scpx"
}

// Keep the distribution archive named "scp-<version>" regardless of the Gradle module
// path segment (which would otherwise default the base name to "cli").
distributions {
    main {
        distributionBaseName.set("scpx")
    }
}

dependencies {
    implementation(project(":modules:skills"))
    implementation(project(":modules:core"))
    implementation(project(":modules:model"))
    implementation(project(":modules:database"))
    implementation(project(":modules:search"))
    implementation(project(":modules:markdown"))
    implementation(project(":modules:config"))

    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
