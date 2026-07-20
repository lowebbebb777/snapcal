import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("com.android.legacy-kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tazawa.snapcal"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tazawa.snapcal"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("demo") {
            dimension = "distribution"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
        }
        create("production") {
            dimension = "distribution"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            // Hackathon APKs are installable without external signing setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1") {
        // The bundled OCR model is local. Remove the optional CCT telemetry sender.
        exclude(group = "com.google.android.datatransport", module = "transport-backend-cct")
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val privacyGateSelfTest by tasks.registering {
    group = "verification"
    description = "Proves that the release privacy policy rejects forbidden fixtures."

    doLast {
        val permissions = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
        )
        val injectedManifest = "<uses-permission android:name=\"android.permission.INTERNET\" />"
        check(
            runCatching {
                verifyNoForbiddenPermissions("injected fixture", injectedManifest, permissions)
            }.isFailure,
        ) { "The privacy gate accepted an injected INTERNET permission." }

        val fragments = rootProject.file("gradle/forbidden-network-dependencies.txt")
            .readPolicyLines()
        val dependencyWithClassifierArtifact = moduleCoordinate(
            group = "com.squareup.okhttp3",
            module = "okhttp",
        )
        check(findForbiddenArtifacts(listOf(dependencyWithClassifierArtifact), fragments).isNotEmpty()) {
            "The privacy gate accepted an injected HTTP client."
        }
        check(findForbiddenArtifacts(listOf("androidx.core:core-ktx"), fragments).isEmpty()) {
            "The privacy gate rejected a safe fixture."
        }
    }
}

val releaseGate by tasks.registering {
    group = "verification"
    description = "Builds release APKs and fails if a release can access the network."
    dependsOn(
        "assembleDemoRelease",
        "assembleProductionRelease",
        "testDemoDebugUnitTest",
        "testProductionDebugUnitTest",
        "lintVitalDemoRelease",
        "lintVitalProductionRelease",
        privacyGateSelfTest,
    )

    doLast {
        val forbiddenPermissions = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
        )
        val forbiddenDependencyFragments = rootProject.file("gradle/forbidden-network-dependencies.txt")
            .readPolicyLines()

        val variants = mapOf(
            "demoRelease" to "demo",
            "productionRelease" to "production",
        )
        variants.forEach { (variant, _) ->
            val taskSuffix = variant.replaceFirstChar(Char::uppercaseChar)
            val manifest = layout.buildDirectory.file(
                "intermediates/merged_manifests/$variant/process${taskSuffix}Manifest/AndroidManifest.xml",
            ).get().asFile
            check(manifest.isFile) { "Merged manifest is missing for $variant." }
            verifyNoForbiddenPermissions(
                manifest.invariantSeparatorsPath,
                manifest.readText(),
                forbiddenPermissions,
            )
        }

        listOf("demoReleaseRuntimeClasspath", "productionReleaseRuntimeClasspath").forEach { configurationName ->
            val artifactCoordinates = configurations.getByName(configurationName)
                .resolvedConfiguration
                .resolvedArtifacts
                .map { artifact ->
                    moduleCoordinate(
                        artifact.moduleVersion.id.group,
                        artifact.moduleVersion.id.name,
                    )
                }
            val forbidden = findForbiddenArtifacts(artifactCoordinates, forbiddenDependencyFragments)
            check(forbidden.isEmpty()) { "Forbidden network dependencies in $configurationName: $forbidden" }
        }

        val releaseApks = variants.map { (_, flavor) ->
            layout.buildDirectory.file("outputs/apk/$flavor/release/app-$flavor-release.apk")
                .get().asFile
                .also { check(it.isFile) { "Release APK is missing for $flavor." } }
        }

        val localProperties = Properties().apply {
            rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
        }
        val sdkDir = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: localProperties.getProperty("sdk.dir")
            ?: error("Android SDK path is unavailable.")
        val aapt = file("$sdkDir/build-tools/36.0.0/aapt")
        check(aapt.canExecute()) { "aapt is unavailable at ${aapt.invariantSeparatorsPath}" }

        releaseApks.forEach { apk ->
            val permissionDump = providers.exec {
                commandLine(aapt.absolutePath, "dump", "permissions", apk.absolutePath)
            }.standardOutput.asText.get()
            verifyNoForbiddenPermissions(apk.name, permissionDump, forbiddenPermissions)
        }

        val outputDirectory = rootProject.file("dist").apply { mkdirs() }
        releaseApks.forEach { apk ->
            val output = outputDirectory.resolve(apk.name)
            apk.copyTo(output, overwrite = true)
            val digest = MessageDigest.getInstance("SHA-256").digest(output.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
            outputDirectory.resolve("${output.name}.sha256").writeText("$digest  ${output.name}\n")
        }
    }
}

fun File.readPolicyLines(): List<String> = readLines()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("#") }

fun verifyNoForbiddenPermissions(
    source: String,
    contents: String,
    forbiddenPermissions: List<String>,
) {
    val found = forbiddenPermissions.filter(contents::contains)
    check(found.isEmpty()) { "Forbidden permissions in $source: $found" }
}

fun findForbiddenArtifacts(
    artifactNames: List<String>,
    forbiddenFragments: List<String>,
): List<String> = artifactNames.filter { name ->
    forbiddenFragments.any(name.lowercase()::contains)
}

fun moduleCoordinate(group: String, module: String): String = "$group:$module".lowercase()
