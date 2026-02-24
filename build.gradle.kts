plugins {
    alias(libs.plugins.android).apply(false)
    alias(libs.plugins.androidLibrary).apply(false)
    alias(libs.plugins.kotlinSerialization).apply(false)
    alias(libs.plugins.ksp).apply(false)
    alias(libs.plugins.detekt).apply(false)
}

subprojects {
    configurations.configureEach {
        exclude(group = "com.android.support", module = "support-compat")
        // LibPhoneNumberInfo currently bundles YACB's SIA code and expects commons-codec 1.15.
        // Newer commons-codec versions change field visibility in BaseNCodec/Base64, which can
        // cause VerifyError at runtime on Android (seen in DbUpdateRequester.getUpdate()).
        resolutionStrategy.force("commons-codec:commons-codec:1.15")
    }
}

subprojects {
    val projectDirFile = project.projectDir
    val resDirectories = projectDirFile.resolve("src")
        .listFiles()
        ?.map { it.resolve("res") }
        ?.filter { it.exists() && it.isDirectory }
        .orEmpty()

    val verifyNoIncludeIdOnMergeRoot = tasks.register("verifyNoIncludeIdOnMergeRoot") {
        group = "verification"
        description = "Fails if an <include android:id> references a layout whose root is <merge>."
        doLast {
            if (resDirectories.isEmpty()) return@doLast

            val includeTagRegex = Regex("<include\\b([\\s\\S]*?)/>")
            val idRegex = Regex("android:id\\s*=\\s*\"@\\+id/([^\"]+)\"")
            val layoutRegex = Regex("layout\\s*=\\s*\"@layout/([^\"]+)\"")
            val rootTagRegex = Regex("<\\s*([A-Za-z0-9_.:-]+)(\\s|>)")
            val xmlDeclarationRegex = Regex("(?s)<\\?.*?\\?>")
            val xmlCommentsRegex = Regex("(?s)<!--.*?-->")

            val includeFiles = resDirectories.flatMap { resDir ->
                resDir.walkTopDown()
                    .filter { it.isFile && it.extension == "xml" }
                    .toList()
            }
            val violations = mutableListOf<String>()

            includeFiles.forEach { includeFile ->
                val content = includeFile.readText()
                includeTagRegex.findAll(content).forEach { includeMatch ->
                    val attrs = includeMatch.groupValues[1]
                    val includeId = idRegex.find(attrs)?.groupValues?.get(1) ?: return@forEach
                    val layoutName = layoutRegex.find(attrs)?.groupValues?.get(1) ?: return@forEach

                    val variants = resDirectories.flatMap { resDir ->
                        resDir.walkTopDown()
                            .filter { variant ->
                                variant.isFile &&
                                    variant.name == "$layoutName.xml" &&
                                    variant.parentFile?.name?.startsWith("layout") == true
                            }
                            .toList()
                    }
                    variants.forEach { variant ->
                        val variantContent = variant.readText()
                            .replace(xmlDeclarationRegex, "")
                            .replace(xmlCommentsRegex, "")
                            .trimStart()
                        val rootTag = rootTagRegex.find(variantContent)?.groupValues?.get(1)
                        if (rootTag == "merge") {
                            val includePath = includeFile.relativeTo(projectDirFile).path
                            val variantPath = variant.relativeTo(projectDirFile).path
                            violations += "$includePath includes @$layoutName with id @$includeId, but $variantPath root is <merge>."
                        }
                    }
                }
            }

            if (violations.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("Found include-with-id references to merge-root layouts:")
                        violations.sorted().forEach { appendLine(" - $it") }
                    }
                )
            }
        }
    }

    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(verifyNoIncludeIdOnMergeRoot)
    }
}
