plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(project(":shared"))

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}
