package org.fossify.mesh.lxmf

import org.fossify.mesh.rns.RnsConstants
import org.fossify.mesh.rns.RnsHash
import org.fossify.mesh.rns.RnsHkdf
import org.msgpack.core.MessagePack
import java.security.SecureRandom

object LxmfStamper {
    const val WORKBLOCK_EXPAND_ROUNDS = 3000
    const val WORKBLOCK_EXPAND_ROUNDS_PN = 1000
    const val WORKBLOCK_EXPAND_ROUNDS_PEERING = 25
    const val STAMP_SIZE = 32
    val DESTINATION_HASH_LEN = RnsConstants.TRUNCATED_HASH_LENGTH_BITS / 8

    data class StampResult(val stamp: ByteArray, val value: Int)
    data class StampedMessage(val transientId: ByteArray, val lxmfData: ByteArray, val value: Int, val stamp: ByteArray)

    fun stampWorkblock(material: ByteArray, expandRounds: Int): ByteArray {
        val output = ByteArray(expandRounds * 256)
        var offset = 0
        for (n in 0 until expandRounds) {
            val packedN = packInt(n)
            val salt = RnsHash.sha256(material + packedN)
            val block = RnsHkdf.derive(length = 256, deriveFrom = material, salt = salt, context = null)
            block.copyInto(output, offset)
            offset += block.size
        }
        return output
    }

    fun stampValue(workblock: ByteArray, stamp: ByteArray): Int {
        val material = RnsHash.sha256(workblock + stamp)
        return countLeadingZeroBits(material)
    }

    fun isStampValid(stamp: ByteArray, targetCost: Int, workblock: ByteArray): Boolean {
        if (targetCost <= 0) return true
        val value = stampValue(workblock, stamp)
        return value >= targetCost
    }

    fun validatePeeringKey(peeringId: ByteArray, peeringKey: ByteArray, targetCost: Int): Boolean {
        val workblock = stampWorkblock(peeringId, WORKBLOCK_EXPAND_ROUNDS_PEERING)
        return isStampValid(peeringKey, targetCost, workblock)
    }

    fun generatePeeringKey(peeringId: ByteArray, targetCost: Int): ByteArray? {
        if (targetCost <= 0) return ByteArray(0)
        val result = generateStamp(peeringId, targetCost, WORKBLOCK_EXPAND_ROUNDS_PEERING) ?: return null
        return result.stamp
    }

    fun validatePnStamp(transientData: ByteArray, targetCost: Int, lxmfOverhead: Int): StampedMessage? {
        if (transientData.size <= lxmfOverhead + STAMP_SIZE) return null
        val lxmfData = transientData.copyOfRange(0, transientData.size - STAMP_SIZE)
        val stamp = transientData.copyOfRange(transientData.size - STAMP_SIZE, transientData.size)
        val transientId = RnsHash.sha256(lxmfData)
        val workblock = stampWorkblock(transientId, WORKBLOCK_EXPAND_ROUNDS_PN)
        if (!isStampValid(stamp, targetCost, workblock)) return null
        val value = stampValue(workblock, stamp)
        return StampedMessage(transientId, lxmfData, value, stamp)
    }

    fun validatePnStamps(transientList: List<ByteArray>, targetCost: Int, lxmfOverhead: Int): List<StampedMessage> {
        if (transientList.isEmpty()) return emptyList()
        return transientList.mapNotNull { validatePnStamp(it, targetCost, lxmfOverhead) }
    }

    fun generateStamp(messageId: ByteArray, stampCost: Int, expandRounds: Int = WORKBLOCK_EXPAND_ROUNDS_PN): StampResult? {
        if (stampCost <= 0) return StampResult(ByteArray(0), 0)
        val workblock = stampWorkblock(messageId, expandRounds)
        val rng = SecureRandom()
        while (true) {
            val stamp = ByteArray(STAMP_SIZE)
            rng.nextBytes(stamp)
            if (isStampValid(stamp, stampCost, workblock)) {
                val value = stampValue(workblock, stamp)
                return StampResult(stamp, value)
            }
        }
    }

    private fun countLeadingZeroBits(bytes: ByteArray): Int {
        var count = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == 0) {
                count += 8
            } else {
                count += Integer.numberOfLeadingZeros(v) - 24
                break
            }
        }
        return count
    }

    private fun packInt(value: Int): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packInt(value)
        val bytes = packer.toByteArray()
        packer.close()
        return bytes
    }
}
