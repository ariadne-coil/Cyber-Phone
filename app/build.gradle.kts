import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.konan.properties.Properties
import java.io.FileInputStream

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

// Strip URL literals from merged translatable resources so dependency metadata links
// (stores/sponsors/social/docs) do not bleed into this fork's packaged strings.
tasks.matching { it.name.matches(Regex("merge\\w+Resources")) }.configureEach {
    doLast {
        val variant = name.removePrefix("merge").removeSuffix("Resources")
            .replaceFirstChar { it.lowercaseChar() }
        val mergedDir = layout.buildDirectory
            .dir("intermediates/merged-not-compiled-resources/$variant")
            .get()
            .asFile
        if (!mergedDir.exists()) return@doLast

        val urlRegex = Regex("""https?://[^\s<>"']+""")

        mergedDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("values") && it.extension == "xml" }
            .forEach { file ->
                var text = file.readText()
                val sanitized = urlRegex.replace(text, "link_removed")
                if (sanitized != text) {
                    text = sanitized
                    file.writeText(text)
                }
            }
    }
}
