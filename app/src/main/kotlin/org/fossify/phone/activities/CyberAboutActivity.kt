package org.fossify.phone.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.fossify.commons.extensions.toast
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
        binding.aboutVersion.text = getString(R.string.about_version_format, BuildConfig.VERSION_NAME)
        binding.aboutRepoLink.setOnClickListener { openExternalLink(getString(R.string.about_repo_link)) }
        binding.aboutSubstackLink.setOnClickListener { openExternalLink(getString(R.string.about_substack_link)) }
        updateTextColors(binding.aboutHolder)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.aboutAppbar, NavigationIcon.Arrow)
    }

    private fun openExternalLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast(R.string.about_open_link_failed)
        }
    }
}
