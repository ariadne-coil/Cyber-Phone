package org.fossify.phone.activities

import android.content.Intent
import android.os.Bundle
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.SIDELOADING_FALSE

class SplashActivity : SimpleActivity() {
    companion object {
        const val EXTRA_DEFAULT_DIALER_PROMPT_SHOWN = "default_dialer_prompt_shown"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Ensure commons sideload checks never trigger warning dialogs in this forked build.
        baseConfig.appSideloadingStatus = SIDELOADING_FALSE
        super.onCreate(savedInstanceState)
        requestDefaultDialerRoleIfNeeded {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_DEFAULT_DIALER_PROMPT_SHOWN, true)
            )
            finish()
        }
    }

    override fun onBackPressedCompat(): Boolean {
        finish()
        return true
    }
}
