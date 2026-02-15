package org.fossify.phone.wallet

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Centralized wallet storage paths.
 *
 * Federation IDs come from remote directory data and must never be used as raw filesystem segments.
 * We derive a stable, sanitized segment with a hash suffix to prevent collisions.
 */
object WalletStoragePaths {
    private const val LDK_ROOT = "wallet/ldk"
    private const val MAX_BASE_LEN = 48
    private const val HASH_LEN = 12

    fun ldkRootDir(context: Context): File {
        return File(context.filesDir, LDK_ROOT)
    }

    fun ldkFederationDir(context: Context, federationId: String): File {
        return File(ldkRootDir(context), federationDirName(federationId))
    }

    fun federationDirName(federationId: String): String {
        val raw = federationId.trim().ifBlank { "default" }
        val sanitized = raw.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
        val base = sanitized.ifBlank { "federation" }.take(MAX_BASE_LEN)
        val hash = sha256Hex(raw).take(HASH_LEN)
        return "$base-$hash"
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            out.append(((b.toInt() ushr 4) and 0x0F).toString(16))
            out.append((b.toInt() and 0x0F).toString(16))
        }
        return out.toString()
    }
}
