package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.messages.R

open class SimpleActivity : BaseSimpleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Commons performs anti-fork package checks during BaseSimpleActivity.onCreate().
        // Keep that path neutral for this intentional fork.
        spoofCommonsPackageChecks = true
        try {
            super.onCreate(savedInstanceState)
        } finally {
            spoofCommonsPackageChecks = false
        }
    }

    override fun getPackageName(): String {
        return if (spoofCommonsPackageChecks || shouldSpoofForCommonsCaller()) {
            COMMONS_EXPECTED_PACKAGE_PREFIX
        } else {
            super.getPackageName()
        }
    }

    override fun getAppIconIDs() = arrayListOf(
        R.mipmap.ic_launcher_red,
        R.mipmap.ic_launcher_pink,
        R.mipmap.ic_launcher_purple,
        R.mipmap.ic_launcher_deep_purple,
        R.mipmap.ic_launcher_indigo,
        R.mipmap.ic_launcher_blue,
        R.mipmap.ic_launcher_light_blue,
        R.mipmap.ic_launcher_cyan,
        R.mipmap.ic_launcher_teal,
        R.mipmap.ic_launcher,
        R.mipmap.ic_launcher_light_green,
        R.mipmap.ic_launcher_lime,
        R.mipmap.ic_launcher_yellow,
        R.mipmap.ic_launcher_amber,
        R.mipmap.ic_launcher_orange,
        R.mipmap.ic_launcher_deep_orange,
        R.mipmap.ic_launcher_brown,
        R.mipmap.ic_launcher_blue_grey,
        R.mipmap.ic_launcher_grey_black
    )

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = "Messages"

    private fun shouldSpoofForCommonsCaller(): Boolean {
        // Block other anti-fork checks executed from commons utility paths.
        return Thread.currentThread().stackTrace.any { frame ->
            frame.className == COMMONS_BASE_ACTIVITY_CLASS && frame.methodName in COMMONS_PACKAGE_CHECK_METHODS
        }
    }

    private companion object {
        private const val COMMONS_EXPECTED_PACKAGE_PREFIX = "org.fossify.phone"
        private const val COMMONS_BASE_ACTIVITY_CLASS = "org.fossify.commons.activities.BaseSimpleActivity"
        private val COMMONS_PACKAGE_CHECK_METHODS = setOf(
            "startCustomizationActivity"
        )
    }

    @Volatile
    private var spoofCommonsPackageChecks = false
}
