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
    // 壁時計のしきい値を持つ性能テスト（TS-102）は、機械差で揺れるため既定では回さない。performanceTest で回す
    useJUnitPlatform {
        excludeTags("performance")
    }
}

tasks.register<Test>("performanceTest") {
    description = "壁時計のしきい値を持つ性能テストだけを回す（手元用。CI の build には含めない）"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("performance")
    }
}
