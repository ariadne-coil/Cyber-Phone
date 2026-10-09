package org.fossify.phone.wallet

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class LdkBackupRestoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val federationId = "test-federation"
    private val directory get() = WalletStoragePaths.federationDirName(federationId)
    private fun archive(extra: Map<String, ByteArray> = emptyMap(), seed: ByteArray? = ByteArray(64), manifestId: String = federationId): ZipInputStream {
        val data = ByteArrayOutputStream()
        ZipOutputStream(data).use { zip ->
            val manifest = JSONObject().put("version", 2).put("type", "ldk").put("federation_id", manifestId).put("storage_dir", directory)
            val files = linkedMapOf("wallet_backup.json" to manifest.toString().toByteArray(), "ldk/$directory/manager" to "state".toByteArray())
            if (seed != null) files["ldk/$directory/keys_seed"] = seed
            files.putAll(extra)
            files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return ZipInputStream(ByteArrayInputStream(data.toByteArray()))
    }
    private fun existing(): File = temporary.newFolder("wallet").also { File(it, "previous-state").writeText("keep me") }

    @Test fun invalidSdkStatePreservesExistingWalletAndCleansStaging() {
        val target = existing()
        archive().use { zip ->
            assertThrows(IllegalStateException::class.java) { LdkBackupRestore.restore(zip, federationId, target, 16_000) { false } }
        }
        assertEquals("keep me", File(target, "previous-state").readText())
        assertEquals(listOf("wallet"), temporary.root.list()!!.toList())
    }

    @Test fun successfulLocalValidationPrecedesReplacement() {
        val target = existing()
        archive().use { zip ->
            LdkBackupRestore.restore(zip, federationId, target, 16_000) { staging ->
                assertEquals("keep me", File(target, "previous-state").readText())
                assertTrue(File(staging, "keys_seed").isFile)
                true
            }
        }
        assertFalse(File(target, "previous-state").exists())
        assertEquals(64L, File(target, "keys_seed").length())
        assertEquals(listOf("wallet"), temporary.root.list()!!.toList())
    }

    @Test fun missingSeedAndWrongManifestDoNotReplaceWallet() {
        val target = existing()
        for (zip in listOf(archive(seed = null), archive(manifestId = "another-wallet"), archive(seed = ByteArray(63)))) {
            zip.use { assertThrows(IllegalArgumentException::class.java) { LdkBackupRestore.restore(it, federationId, target, 16_000) { true } } }
            assertTrue(File(target, "previous-state").exists())
        }
    }

    @Test fun traversalAndExpandedZipLimitAreRejected() {
        val target = existing()
        for (zip in listOf(archive(mapOf("ldk/$directory/../outside" to byteArrayOf(1))), archive(mapOf("ignored/huge" to ByteArray(32_000))))) {
            zip.use { assertThrows(IllegalArgumentException::class.java) { LdkBackupRestore.restore(it, federationId, target, 16_000) { true } } }
            assertTrue(File(target, "previous-state").exists())
        }
        assertFalse(File(temporary.root, "outside").exists())
        assertEquals(listOf("wallet"), temporary.root.list()!!.toList())
    }
}
