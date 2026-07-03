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
