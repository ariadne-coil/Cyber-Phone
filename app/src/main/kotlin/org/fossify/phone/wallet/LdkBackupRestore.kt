package org.fossify.phone.wallet

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/** Stage and validate wallet files before replacing any existing recovery state. */
object LdkBackupRestore {
    private const val MAX_ENTRIES = 4096
    private const val MAX_MANIFEST_BYTES = 4096

    fun restore(
        zip: ZipInputStream,
        federationId: String,
        target: File,
        maxBytes: Long,
        validate: (File) -> Boolean,
    ) {
        require(maxBytes > 0)
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Wallet storage parent unavailable" }
        val stage = File(parent, ".${target.name}.restore-${UUID.randomUUID()}")
        val previous = File(parent, ".${target.name}.pre-restore-${UUID.randomUUID()}")
        check(stage.mkdir()) { "Could not prepare wallet restore" }
        var movedPrevious = false
        try {
            extract(zip, stage, federationId, maxBytes)
            check(validate(stage)) { "Restored wallet state could not be validated" }
            if (target.exists()) {
                check(target.renameTo(previous)) { "Could not preserve existing wallet" }
                movedPrevious = true
            }
            if (!stage.renameTo(target)) {
                if (movedPrevious) {
                    check(previous.renameTo(target)) { "Restore failed; previous wallet is preserved at ${previous.name}" }
                    movedPrevious = false
                }
                error("Could not finalize restored wallet files")
            }
            // Only discard the previous copy after local SDK validation and successful replacement.
            if (movedPrevious) previous.deleteRecursively()
        } finally {
            stage.deleteRecursively()
            // A failed rollback intentionally leaves the previous directory available for recovery.
        }
    }

    private fun extract(zip: ZipInputStream, stage: File, federationId: String, maxBytes: Long) {
        val dirName = WalletStoragePaths.federationDirName(federationId)
        val prefix = "ldk/$dirName/"
        val legacyPrefix = "ldk/$federationId/"
        val names = HashSet<String>()
        var expandedBytes = 0L
        var manifest: JSONObject? = null
        var usedLegacyPrefix = false
        var entries = 0
        val buffer = ByteArray(8192)

        fun copyEntry(output: OutputStream?, entryLimit: Long = maxBytes) {
            var entryBytes = 0L
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                expandedBytes += count
                entryBytes += count
                require(expandedBytes <= maxBytes && entryBytes <= entryLimit) { "Backup exceeds extracted size limit" }
                output?.write(buffer, 0, count)
            }
        }

        while (true) {
            val entry = zip.nextEntry ?: break
            require(++entries <= MAX_ENTRIES) { "Backup contains too many entries" }
            val name = entry.name.replace('\\', '/')
            require(!name.startsWith('/') && name.split('/').none { it == ".." } && names.add(name)) { "Invalid or duplicate backup path" }
            if (name == "wallet_backup.json") {
                val output = ByteArrayOutputStream()
                copyEntry(output, MAX_MANIFEST_BYTES.toLong())
                manifest = JSONObject(output.toString(Charsets.UTF_8.name()))
            } else {
                val accepted = when {
                    name.startsWith(prefix) -> prefix
                    name.startsWith(legacyPrefix) -> legacyPrefix.also { usedLegacyPrefix = true }
                    else -> null
                }
                if (accepted == null || entry.isDirectory) {
                    copyEntry(null)
                } else {
                    val relative = name.removePrefix(accepted)
                    require(relative.isNotBlank()) { "Empty wallet filename" }
                    val output = File(stage, relative)
                    require(output.canonicalPath.startsWith(stage.canonicalPath + File.separator)) { "Invalid wallet file path" }
                    val outputParent = requireNotNull(output.parentFile)
                    check(outputParent.isDirectory || outputParent.mkdirs()) { "Could not create wallet directory" }
                    output.outputStream().use { copyEntry(it) }
                }
            }
            zip.closeEntry()
        }

        if (manifest != null) {
            require(manifest.optInt("version") in 1..2 && manifest.optString("type") == "ldk" &&
                manifest.optString("federation_id") == federationId) { "Backup manifest does not match this wallet" }
            val storageDir = manifest.optString("storage_dir")
            require(storageDir.isBlank() || storageDir == dirName || storageDir == federationId) { "Invalid backup storage directory" }
        } else {
            require(usedLegacyPrefix) { "Backup manifest missing" }
        }
        require(File(stage, "keys_seed").let { it.isFile && it.length() == 64L }) { "Backup seed missing or invalid" }
        require(File(stage, "manager").let { it.isFile && it.length() > 0 }) { "Backup channel state missing" }
    }
}
