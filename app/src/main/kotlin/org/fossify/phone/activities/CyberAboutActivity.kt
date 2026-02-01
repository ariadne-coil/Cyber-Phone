package org.fossify.phone.activities

import android.os.Bundle
import android.text.method.LinkMovementMethod
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.phone.BuildConfig
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivityCyberAboutBinding

class CyberAboutActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityCyberAboutBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomSystem = listOf(binding.aboutScrollview))
        binding.aboutAuthorLink.movementMethod = LinkMovementMethod.getInstance()
        binding.aboutVersion.text = getString(R.string.about_version_format, BuildConfig.VERSION_NAME)
        updateTextColors(binding.aboutHolder)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.aboutAppbar, NavigationIcon.Arrow)
    }
}
