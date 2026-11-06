plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.jmdns)
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
}

tasks.test {
    // TD-43（実ファイルの WatchService）は ubuntu の実装が Windows と違うため既定では回さない。手元の Windows で -PwindowsWatch を付けて回す
    useJUnitPlatform {
        if (!project.hasProperty("windowsWatch")) excludeTags("windows-watch")
    }
}

compose.desktop {
    application {
        mainClass = "playlistsyncfox.desktop.MainKt"
    }
}
