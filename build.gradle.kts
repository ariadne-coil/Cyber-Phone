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
