package org.fossify.mesh.rns

data class RnsAnnounce(
    val destinationHash: ByteArray,
    val publicKey: ByteArray,
    val nameHash: ByteArray,
    val randomHash: ByteArray,
    val ratchet: ByteArray,
    val signature: ByteArray,
    val appData: ByteArray?
) {
    companion object {
        private const val KEY_SIZE = 64
        private const val RATCHET_SIZE = 32
        private const val NAME_HASH_SIZE = RnsConstants.NAME_HASH_LENGTH_BITS / 8
        private const val SIGNATURE_SIZE = 64
        private const val RANDOM_HASH_SIZE = 10

        fun parse(packet: RnsPacket): RnsAnnounce? {
            if (packet.packetType != RnsPacket.ANNOUNCE || packet.destination == null) return null
            val data = packet.data
            if (data.size < KEY_SIZE + NAME_HASH_SIZE + RANDOM_HASH_SIZE + SIGNATURE_SIZE) {
                return null
            }

            val publicKey = data.copyOfRange(0, KEY_SIZE)
            val nameHashStart = KEY_SIZE
            val nameHashEnd = nameHashStart + NAME_HASH_SIZE
            val randomHashStart = nameHashEnd
            val randomHashEnd = randomHashStart + RANDOM_HASH_SIZE

            val ratchet: ByteArray
            val signature: ByteArray
            val appData: ByteArray?

            if (packet.contextFlag == RnsPacket.FLAG_SET) {
                val ratchetStart = randomHashEnd
                val ratchetEnd = ratchetStart + RATCHET_SIZE
                val signatureStart = ratchetEnd
                val signatureEnd = signatureStart + SIGNATURE_SIZE
                if (data.size < signatureEnd) return null
                ratchet = data.copyOfRange(ratchetStart, ratchetEnd)
                signature = data.copyOfRange(signatureStart, signatureEnd)
                appData = if (data.size > signatureEnd) data.copyOfRange(signatureEnd, data.size) else null
            } else {
                ratchet = ByteArray(0)
                val signatureStart = randomHashEnd
                val signatureEnd = signatureStart + SIGNATURE_SIZE
                if (data.size < signatureEnd) return null
                signature = data.copyOfRange(signatureStart, signatureEnd)
                appData = if (data.size > signatureEnd) data.copyOfRange(signatureEnd, data.size) else null
            }

            return RnsAnnounce(
                destinationHash = packet.destination.hash,
                publicKey = publicKey,
                nameHash = data.copyOfRange(nameHashStart, nameHashEnd),
                randomHash = data.copyOfRange(randomHashStart, randomHashEnd),
                ratchet = ratchet,
                signature = signature,
                appData = appData
            )
        }

        fun validate(packet: RnsPacket): RnsAnnounce? {
            val announce = parse(packet) ?: return null
            val identity = RnsIdentity.fromPublic(announce.publicKey)
            val signedData = announce.destinationHash +
                announce.publicKey +
                announce.nameHash +
                announce.randomHash +
                announce.ratchet +
                (announce.appData ?: ByteArray(0))

            if (!identity.verify(signedData, announce.signature)) {
                return null
            }

            val expectedHash = RnsHash.sha256(
                announce.nameHash + RnsHash.truncatedHash(announce.publicKey)
            ).copyOfRange(0, RnsConstants.TRUNCATED_HASH_LENGTH_BITS / 8)

            if (!expectedHash.contentEquals(announce.destinationHash)) {
                return null
            }

            return announce
        }

        fun build(
            destination: RnsDestination,
            appData: ByteArray? = null,
            ratchet: ByteArray? = null
        ): RnsPacket {
            val identity = destination.identity ?: error("Destination requires identity for announce")
            val randomSeed = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val randomPrefix = RnsHash.sha256(randomSeed).copyOfRange(0, 5)
            val timeBytes = toFiveByteTimestamp(System.currentTimeMillis() / 1000L)
            val randomHash = randomPrefix + timeBytes
            val ratchetBytes = ratchet ?: ByteArray(0)
            val signedData = destination.hash +
                identity.publicKey +
                destination.nameHash +
                randomHash +
                ratchetBytes +
                (appData ?: ByteArray(0))
            val signature = identity.sign(signedData)
            val payload = identity.publicKey +
                destination.nameHash +
                randomHash +
                ratchetBytes +
                signature +
                (appData ?: ByteArray(0))
            val contextFlag = if (ratchetBytes.isNotEmpty()) RnsPacket.FLAG_SET else RnsPacket.FLAG_UNSET
            return RnsPacket(
                destination = RnsDestination.fromHash(destination.hash, destination.type),
                data = payload,
                packetType = RnsPacket.ANNOUNCE,
                context = RnsPacket.NONE,
                contextFlag = contextFlag
            )
        }

        private fun toFiveByteTimestamp(seconds: Long): ByteArray {
            val bytes = ByteArray(5)
            for (i in 0 until 5) {
                bytes[4 - i] = ((seconds shr (i * 8)) and 0xFF).toByte()
            }
            return bytes
        }
    }
}
