plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":itunes"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "playlistsyncfox.desktop.MainKt"
    }
}
