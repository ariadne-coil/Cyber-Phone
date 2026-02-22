package org.fossify.phone.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import org.fossify.messages.activities.ManageE2eKeysActivity

/**
 * Receives external intents (QR scanners, browsers, shares) and forwards them to Cyber Features.
 */
class MeshLinkActivity : Activity() {
    private companion object {
        // vCards/QR payloads should stay small; hard cap avoids OOM from oversized external URIs.
        const val MAX_IMPORTED_TEXT_BYTES = 512 * 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val src = intent
        val extracted = extractPayload(src)

        // If we extracted a mesh address, we want to ensure it's passed as EXTRA_TEXT
        // which ManageE2eKeysActivity likely uses for auto-importing.
        val forward = Intent(this, ManageE2eKeysActivity::class.java).apply {
            action = if (!extracted.isNullOrBlank()) Intent.ACTION_VIEW else src?.action
            setDataAndType(src?.data, src?.type)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            src?.extras?.let { putExtras(it) }

            if (!extracted.isNullOrBlank()) {
                putExtra(Intent.EXTRA_TEXT, extracted)
                // Also set the data to the extracted URI if it's a mesh address
                if (extracted.startsWith("mesh", ignoreCase = true) ||
                    extracted.startsWith("lxm", ignoreCase = true)) {
                    data = extracted.toUri()
                }
            }
        }

        startActivity(forward)
        finish()
    }

    private fun extractPayload(src: Intent?): String? {
        if (src == null) return null

        // 1. Check for explicit EXTRA_TEXT (common in Shares)
        val extraText = src.getStringExtra(Intent.EXTRA_TEXT)
        if (!extraText.isNullOrBlank()) return extraText

        val uri = src.data
        if (uri == null) return null

        // 2. Handle Custom Schemes (mesh:, lxmf:, etc)
        // For opaque URIs like mesh:address, uri.schemeSpecificPart contains the address.
        val scheme = uri.scheme?.lowercase()
        if (scheme == "mesh" || scheme == "lxm" || scheme == "lxmf" || scheme == "meshaddr1") {
            return uri.toString()
        }

        // 3. Handle App Links / Web Links
        if (scheme == "http" || scheme == "https") {
            if (uri.host?.equals("cyberphone.local", ignoreCase = true) == true) {
                return uri.toString()
            }
        }

        // 4. Handle vCards and files
        val type = src.type.orEmpty()
        if (type.contains("vcard", ignoreCase = true) || uri.path?.endsWith(".vcf") == true) {
            return readTextFromUri(uri)
        }

        // 5. Fallback: try to read stream
        val stream = src.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (stream != null) {
            return readTextFromUri(stream)
        }

        return null
    }

    private fun readTextFromUri(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_IMPORTED_TEXT_BYTES) {
                        return null
                    }
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } catch (_: Exception) {
            null
        }
    }
}
