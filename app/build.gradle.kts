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

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = project.property("APP_ID").toString()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        // Deterministic versioning for reproducible release builds.
        versionName = project.property("VERSION_NAME").toString()
        versionCode = project.property("VERSION_CODE").toString().toInt()
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
