import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.licensee)
}

android {
    namespace = "playlistsyncfox.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "playlistsyncfox.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        // 移行の試験の入力にする Room のスキーマ（X6-1）
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

// 依存の許諾を `artifacts.json` にして資産へ入れる（X7-6）。許す許諾は Apache-2.0 と、SPDX の無い MIT（slf4j-api）、Bouncy Castle の許諾（MIT 相当）
licensee {
    allow("Apache-2.0")
    allowUrl("https://opensource.org/license/mit")
    allowUrl("https://www.bouncycastle.org/licence.html")
    bundleAndroidAsset.set(true)
}

/**
 * APK に入る依存の jar から `META-INF/NOTICE*` を集め、assets の `notices/<jar の名前>.txt` に写す（X7-7。Apache-2.0 の部品は NOTICE の添付を求める）。
 * ファイル名は画面（ui/Licenses.kt の `LicenseEntry.noticeFile`）が `<artifactId>-<version>.txt` で探すものと対応させる。NOTICE が 1 つも無くても、空の出力で成功する。
 */
abstract class CollectNotices : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val dependencyFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun collect() {
        val noticeEntry = Regex("META-INF/NOTICE[^/]*", RegexOption.IGNORE_CASE)
        val noticeDir = outputDir.get().asFile.resolve("notices")
        noticeDir.deleteRecursively()
        noticeDir.mkdirs()

        for (jar in dependencyFiles.files.filter { it.isFile && it.extension == "jar" }) {
            val notices = ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && noticeEntry.matches(it.name) }
                    .map { entry -> zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } }
                    .filter { it.isNotBlank() }
                    .toList()
            }
            if (notices.isNotEmpty()) noticeDir.resolve("${jar.nameWithoutExtension}.txt").writeText(notices.joinToString("\n\n"))
        }
    }
}

// 変種ごとに、その変種の runtime classpath（APK に入る依存）から NOTICE を集めて assets に足す（X7-7）
androidComponents {
    onVariants { variant ->
        val collectNotices = tasks.register<CollectNotices>("collect${variant.name.replaceFirstChar { it.uppercase() }}Notices") {
            dependencyFiles.from(configurations.named("${variant.name}RuntimeClasspath"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(collectNotices, CollectNotices::outputDir)
    }
}

// Room のスキーマの JSON を repo に置く（X6-1）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    // 点演算の実体（J16、X4-1）
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.network.tls.certificates)

    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
