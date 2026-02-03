package org.fossify.mesh.lxmf

import android.content.Context
import org.fossify.mesh.rns.RnsHash
import org.fossify.mesh.rns.RnsHex
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class LxmfPropagationStore(context: Context) {
    private val storeDir = File(context.filesDir, "mesh_propagation")
    private val entries = ConcurrentHashMap<String, PropagationEntry>()

    data class PropagationEntry(
        val transientId: ByteArray,
        val destinationHash: ByteArray,
        val receivedAt: Long,
        val sizeBytes: Int,
        val stampValue: Int,
        val file: File
    )

    init {
        loadEntries()
    }

    fun hasEntry(transientId: ByteArray): Boolean {
        return entries.containsKey(RnsHex.encode(transientId))
    }

    fun getEntry(transientId: ByteArray): PropagationEntry? {
        return entries[RnsHex.encode(transientId)]
    }

    fun listEntriesForDestination(destinationHash: ByteArray): List<PropagationEntry> {
        return entries.values.filter { it.destinationHash.contentEquals(destinationHash) }
    }

    fun listEntries(): List<PropagationEntry> {
        return entries.values.toList()
    }

    fun storeStampedMessage(lxmfData: ByteArray, stamp: ByteArray, stampValue: Int): PropagationEntry? {
        ensureDir()
        val transientId = RnsHash.sha256(lxmfData)
        val key = RnsHex.encode(transientId)
        if (entries.containsKey(key)) return entries[key]
        if (lxmfData.size < LxmfStamper.DESTINATION_HASH_LEN) return null

        val received = System.currentTimeMillis() / 1000L
        val safeStampValue = stampValue.coerceAtLeast(0)
        val fileName = "${key}_${received}_${safeStampValue}"
        val file = File(storeDir, fileName)
        val stampedData = if (stamp.isNotEmpty()) lxmfData + stamp else lxmfData
        return try {
            file.writeBytes(stampedData)
            val destinationHash = lxmfData.copyOfRange(0, LxmfStamper.DESTINATION_HASH_LEN)
            val entry = PropagationEntry(
                transientId = transientId,
                destinationHash = destinationHash,
                receivedAt = received,
                sizeBytes = stampedData.size,
                stampValue = safeStampValue,
                file = file
            )
            entries[key] = entry
            entry
        } catch (_: Exception) {
            null
        }
    }

    fun readLxmfData(entry: PropagationEntry): ByteArray? {
        return try {
            val data = entry.file.readBytes()
            if (entry.stampValue > 0 && data.size > LxmfStamper.STAMP_SIZE) {
                data.copyOfRange(0, data.size - LxmfStamper.STAMP_SIZE)
            } else {
                data
            }
        } catch (_: Exception) {
            null
        }
    }

    fun readStampedData(entry: PropagationEntry): ByteArray? {
        return try {
            entry.file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    fun removeEntry(transientId: ByteArray): Boolean {
        val key = RnsHex.encode(transientId)
        val entry = entries.remove(key) ?: return false
        return try {
            entry.file.delete()
        } catch (_: Exception) {
            false
        }
    }

    private fun loadEntries() {
        ensureDir()
        val files = storeDir.listFiles() ?: return
        files.forEach { file ->
            val parts = file.name.split("_")
            if (parts.size < 2) return@forEach
            val hex = parts[0]
            val received = parts.getOrNull(1)?.toDoubleOrNull()?.toLong() ?: 0L
            val stampValue = parts.getOrNull(2)?.toIntOrNull() ?: 0
            try {
                val transientId = RnsHex.decode(hex)
                val data = file.readBytes()
                val lxmfData = if (stampValue > 0 && data.size > LxmfStamper.STAMP_SIZE) {
                    data.copyOfRange(0, data.size - LxmfStamper.STAMP_SIZE)
                } else {
                    data
                }
                if (lxmfData.size < LxmfStamper.DESTINATION_HASH_LEN) return@forEach
                val destinationHash = lxmfData.copyOfRange(0, LxmfStamper.DESTINATION_HASH_LEN)
                val entry = PropagationEntry(
                    transientId = transientId,
                    destinationHash = destinationHash,
                    receivedAt = received,
                    sizeBytes = data.size,
                    stampValue = stampValue,
                    file = file
                )
                entries[RnsHex.encode(transientId)] = entry
            } catch (_: Exception) {
                // ignore corrupted files
            }
        }
    }

    private fun ensureDir() {
        if (!storeDir.exists()) {
            storeDir.mkdirs()
        }
    }
}
