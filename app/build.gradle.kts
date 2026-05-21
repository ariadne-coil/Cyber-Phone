import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.konan.properties.Properties
import java.io.FileInputStream
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.detekt)
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun hasSigningVars(): Boolean {
    return providers.environmentVariable("SIGNING_KEY_ALIAS").orNull != null
            && providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull != null
            && providers.environmentVariable("SIGNING_STORE_FILE").orNull != null
            && providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull != null
}

fun isCommandOnPath(name: String): Boolean {
    val pathEntries = (System.getenv("PATH") ?: return false)
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }

    val candidates = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        val pathext = (System.getenv("PATHEXT") ?: ".COM;.EXE;.BAT;.CMD")
            .split(";")
            .filter { it.isNotBlank() }
        if (name.contains(".")) {
            listOf(name)
        } else {
            listOf(name) + pathext.map { ext ->
                if (ext.startsWith(".")) "$name$ext" else "$name.$ext"
            }
        }
    } else {
        listOf(name)
    }

    return pathEntries.any { entry ->
        candidates.any { candidate -> File(entry, candidate).isFile }
    }
}

fun toWslPath(file: File): String? {
    val normalized = file.absolutePath.replace('\\', '/')
    val match = Regex("^([A-Za-z]):/(.*)$").matchEntire(normalized) ?: return null
    val drive = match.groupValues[1].lowercase()
    val rest = match.groupValues[2]
    return "/mnt/$drive/$rest"
}

fun shellSingleQuote(value: String): String = value.replace("'", "'\"'\"'")

val blockedReleaseUrlFragments = listOf(
    "play.google.com/store/apps/",
    "play.google.com/store/apps/dev",
    "fossify.org/upgrade_to_pro",
    "www.fossify.org/policy/",
    "github.com/FossifyOrg",
    "github.com/sponsors/FossifyOrg",
    "opencollective.com/fossify/",
    "www.patreon.com/naveen3singh",
    "paypal.me/naveen3singh",
    "liberapay.com/naveensingh",
    "t.me/Fossify",
    "www.reddit.com/r/Fossify"
)

val fedimintSourceRoot = rootProject.file("third_party/fedimint-web")
val fedimintGeneratedAssetRootDir = layout.buildDirectory.dir("generated/fedimintRuntime/assets")
val fedimintGeneratedAssetDir = layout.buildDirectory.dir("generated/fedimintRuntime/assets/fedimint")
val fedimintRustTargetDir = layout.buildDirectory.dir("intermediates/fedimintRuntime/target")
val fedimintRuntimeBuildScript = rootProject.file("scripts/build-fedimint-web-runtime.mjs")
val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val hasLocalCargo = isCommandOnPath("cargo")
val hasWsl = isCommandOnPath("wsl")
val fedimintWslRoot = if (isWindowsHost && !hasLocalCargo) toWslPath(rootProject.projectDir) else null

val generateFedimintWebRuntime by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Fedimint Web runtime from pinned source into generated Android assets."
    doNotTrackState("Fedimint runtime generation depends on external toolchain state and submodule contents.")
    notCompatibleWithConfigurationCache("Fedimint runtime generation depends on external toolchain state and submodule contents.")
    onlyIf {
        val hasSourceCheckout = fedimintSourceRoot.listFiles()?.isNotEmpty() == true
        if (!hasSourceCheckout) {
            logger.lifecycle("Fedimint source checkout is not populated; using checked-in Android assets.")
        }
        hasSourceCheckout
    }

    val outputDir = fedimintGeneratedAssetDir.get().asFile
    val targetDir = fedimintRustTargetDir.get().asFile

    inputs.dir(fedimintSourceRoot)
    inputs.file(fedimintRuntimeBuildScript)
    outputs.dir(outputDir)

    if (fedimintWslRoot != null && hasWsl) {
        val wslCommand =
            "cd '${shellSingleQuote(fedimintWslRoot)}' && " +
                    "node scripts/build-fedimint-web-runtime.mjs " +
                    "third_party/fedimint-web " +
                    "app/build/generated/fedimintRuntime/assets/fedimint " +
                    "app/build/intermediates/fedimintRuntime/target"
        commandLine("wsl.exe", "bash", "-lc", wslCommand)
    } else {
        commandLine(
            "node",
            fedimintRuntimeBuildScript.absolutePath,
            fedimintSourceRoot.absolutePath,
            outputDir.absolutePath,
            targetDir.absolutePath,
        )
    }
}

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = project.property("APP_ID").toString()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        // Deterministic versioning for reproducible release builds.
        versionName = project.property("VERSION_NAME").toString()
        versionCode = project.property("VERSION_CODE").toString().toInt()
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            register("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        } else if (hasSigningVars()) {
            register("release") {
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
                storeFile = file(providers.environmentVariable("SIGNING_STORE_FILE").get())
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
            }
        } else {
            logger.warn("Warning: No signing config found. Build will be unsigned.")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            installation {
                // Some Studio/device combinations fail APK deployment with
                // INSTALL_BASELINE_PROFILE_FAILED on non-debuggable builds.
                // Disable per-APK baseline profile sidecar generation to keep
                // release APK installs reliable during local testing.
                enableBaselineProfile = false
            }
            if (keystorePropertiesFile.exists() || hasSigningVars()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        val currentJavaVersionFromLibs =
            JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    dependenciesInfo {
        includeInApk = false
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    packaging {
        // ldk-node-android uses JNA internally. Some devices/ROMs require legacy native library
        // packaging to ensure the dispatch/native libs are loadable from the filesystem.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    // Keep source package namespace stable while allowing applicationId branding changes.
    namespace = "org.fossify.phone"

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        lintConfig = rootProject.file("lint.xml")
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    sourceSets.getByName("main").assets.directories.add(fedimintGeneratedAssetRootDir.get().asFile.absolutePath)
}

detekt {
    baseline = layout.projectDirectory.file("detekt-baseline.xml").asFile
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

dependencies {
    implementation(libs.fossify.commons)
    implementation(libs.indicator.fast.scroll)
    implementation(libs.autofit.text.view)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.eventbus)
    implementation(libs.libphonenumber)
    implementation(libs.carrier)
    implementation(libs.geocoder)
    implementation(libs.libphonenumberinfo)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle)
    implementation(libs.slf4j.android)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.webkit)
    implementation(libs.work.runtime)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.concentus)
    implementation(libs.ldk.node.android)
    detektPlugins(libs.compose.detekt)
    implementation(project(":messages"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

tasks.register("testClasses") {
    group = "verification"
    description = "Runs unit tests (alias for testDebugUnitTest)."
    dependsOn("testDebugUnitTest")
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(generateFedimintWebRuntime)
}

tasks.register("verifyReleaseUrlScrub") {
    group = "verification"
    description = "Checks that blocked ecosystem/store URL fragments are absent from the release APK."
    dependsOn("assembleRelease")
    doLast {
        val apkFile = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        if (!apkFile.exists()) {
            throw GradleException("Release APK not found at ${apkFile.absolutePath}")
        }

        val hits = LinkedHashSet<String>()
        ZipFile(apkFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue

                val content = zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
                blockedReleaseUrlFragments.forEach { blocked ->
                    if (content.contains(blocked)) {
                        hits.add("${entry.name} -> $blocked")
                    }
                }
            }
        }

        if (hits.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Blocked URL fragments detected in release APK:")
                    hits.forEach { appendLine(it) }
                }
            )
        }
        logger.lifecycle("verifyReleaseUrlScrub: OK")
    }
}
