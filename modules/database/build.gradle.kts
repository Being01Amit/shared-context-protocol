plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.sqldelight)
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

// Generated SQLDelight sources are not held to hand-written style rules. ktlint patterns
// match relative to each source root, and the generated root's files land directly in
// com/scp/database (hand-written code lives one level deeper, in .../adapter), so these
// two patterns exclude exactly the generated files.
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
    exclude("com/scp/database/*.kt", "com/scp/database/database/**")
}

sqldelight {
    databases {
        create("ScpDatabase") {
            packageName.set("com.scp.database")
            dialect(libs.sqldelight.dialect.sqlite338)
        }
    }
}

dependencies {
    api(project(":modules:model"))
    api(libs.sqldelight.runtime)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.sqlite.jdbc)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
