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
    }
}
