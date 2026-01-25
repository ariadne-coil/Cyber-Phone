package org.fossify.mesh.rns

data class RnsPacket(
    val destination: RnsDestination?,
    val data: ByteArray,
    val packetType: Int = DATA,
    val context: Int = NONE,
    val transportType: Int = RnsTransport.BROADCAST,
    val headerType: Int = HEADER_1,
    val transportId: ByteArray? = null,
    val hops: Int = 0,
    val contextFlag: Int = FLAG_UNSET,
    var raw: ByteArray? = null
) {
    companion object {
        const val DATA = 0x00
        const val ANNOUNCE = 0x01
        const val LINKREQUEST = 0x02
        const val PROOF = 0x03

        const val HEADER_1 = 0x00
        const val HEADER_2 = 0x01

        const val NONE = 0x00
        const val RESOURCE = 0x01
        const val RESOURCE_ADV = 0x02
        const val RESOURCE_REQ = 0x03
        const val RESOURCE_HMU = 0x04
        const val RESOURCE_PRF = 0x05
        const val RESOURCE_ICL = 0x06
        const val RESOURCE_RCL = 0x07
        const val CACHE_REQUEST = 0x08
        const val REQUEST = 0x09
        const val RESPONSE = 0x0A
        const val PATH_RESPONSE = 0x0B
        const val COMMAND = 0x0C
        const val COMMAND_STATUS = 0x0D
        const val CHANNEL = 0x0E
        const val KEEPALIVE = 0xFA
        const val LINKIDENTIFY = 0xFB
        const val LINKCLOSE = 0xFC
        const val LINKPROOF = 0xFD
        const val LRRTT = 0xFE
        const val LRPROOF = 0xFF

        const val FLAG_SET = 0x01
        const val FLAG_UNSET = 0x00

        private const val LRPROOF_SIG_LEN = 64
        private const val LRPROOF_PUB_LEN = 32
        private const val LRPROOF_SIGNAL_LEN = 3

        fun shouldEncrypt(packetType: Int, context: Int, destinationType: Int): Boolean {
            if (packetType == ANNOUNCE || packetType == LINKREQUEST) return false
            if (packetType == PROOF && context == RESOURCE_PRF) return false
            if (context == RESOURCE ||
                context == RESOURCE_ADV ||
                context == RESOURCE_REQ ||
                context == RESOURCE_HMU ||
                context == RESOURCE_ICL ||
                context == RESOURCE_RCL ||
                context == KEEPALIVE ||
                context == CACHE_REQUEST
            ) return false
            if (destinationType == RnsDestination.LINK) return false
            return destinationType != RnsDestination.PLAIN
        }

        fun shouldEncryptForLink(packetType: Int, context: Int): Boolean {
            if (packetType == ANNOUNCE || packetType == LINKREQUEST) return false
            if (packetType == PROOF) return false
            if (context == RESOURCE ||
                context == RESOURCE_ADV ||
                context == RESOURCE_REQ ||
                context == RESOURCE_HMU ||
                context == RESOURCE_ICL ||
                context == RESOURCE_RCL ||
                context == KEEPALIVE ||
                context == CACHE_REQUEST
            ) return false
            return true
        }

        fun getHashablePart(raw: ByteArray): ByteArray {
            val flags = raw[0].toInt() and 0xFF
            val headerType = (flags and 0b01000000) shr 6
            val dstLen = RnsConstants.TRUNCATED_HASH_LENGTH_BITS / 8
            val start = if (headerType == HEADER_2) dstLen + 2 else 2
            val out = ByteArray(1 + (raw.size - start))
            out[0] = (flags and 0x0F).toByte()
            System.arraycopy(raw, start, out, 1, raw.size - start)
            return out
        }

        fun fromRaw(raw: ByteArray): RnsPacket {
            return RnsPacket(destination = null, data = ByteArray(0)).unpack(raw)
        }
    }

    fun buildFlags(destinationTypeOverride: Int? = null): Int {
        val destinationType = if (context == LRPROOF) {
            RnsDestination.LINK
        } else {
            destinationTypeOverride ?: destination?.type ?: RnsDestination.PLAIN
        }
        return (headerType shl 6) or
            (contextFlag shl 5) or
            (transportType shl 4) or
            (destinationType shl 2) or
            packetType
    }

    fun pack(): ByteArray {
        val dst = destination ?: error("Destination required for packing")
        val dstHash = dst.hash
        val header = ArrayList<Byte>()
        header.add(buildFlags().toByte())
        header.add(hops.toByte())

        if (headerType == HEADER_2) {
            val tid = transportId ?: error("Transport ID required for HEADER_2")
            header.addAll(tid.toList())
        }

        header.addAll(dstHash.toList())
        if (context != LRPROOF) {
            header.add(context.toByte())
        }

        val destinationType = dst.type
        val payload = if (context == LRPROOF) {
            data
        } else if (shouldEncrypt(packetType, context, destinationType)) {
            dst.encrypt(data)
        } else {
            data
        }

        val raw = ByteArray(header.size + payload.size)
        for (i in header.indices) {
            raw[i] = header[i]
        }
        payload.copyInto(raw, destinationOffset = header.size)
        if (raw.size > RnsConstants.MTU) {
            throw IllegalArgumentException("Packet size ${raw.size} exceeds MTU ${RnsConstants.MTU}")
        }
        this.raw = raw
        return raw
    }

    fun unpack(raw: ByteArray): RnsPacket {
        val flags = raw[0].toInt() and 0xFF
        val parsedHeaderType = (flags and 0b01000000) shr 6
        val parsedContextFlag = (flags and 0b00100000) shr 5
        val parsedTransportType = (flags and 0b00010000) shr 4
        val parsedDestinationType = (flags and 0b00001100) shr 2
        val parsedPacketType = (flags and 0b00000011)

        val dstLen = RnsConstants.TRUNCATED_HASH_LENGTH_BITS / 8
        val hopValue = raw[1].toInt() and 0xFF

        var offset = 2
        var parsedTransportId: ByteArray? = null
        if (parsedHeaderType == HEADER_2) {
            parsedTransportId = raw.copyOfRange(offset, offset + dstLen)
            offset += dstLen
        }

        val destHash = raw.copyOfRange(offset, offset + dstLen)
        offset += dstLen

        val linkProofSize = LRPROOF_SIG_LEN + LRPROOF_PUB_LEN
        val linkProofSignalSize = linkProofSize + LRPROOF_SIGNAL_LEN
        val remaining = raw.size - offset
        val isLinkProof = parsedPacketType == PROOF &&
            parsedDestinationType == RnsDestination.LINK &&
            parsedHeaderType == HEADER_1 &&
            (remaining == linkProofSize || remaining == linkProofSignalSize)

        if (isLinkProof) {
            val parsedData = raw.copyOfRange(offset, raw.size)
            return RnsPacket(
                destination = RnsDestination.fromHash(destHash, parsedDestinationType),
                data = parsedData,
                packetType = parsedPacketType,
                context = LRPROOF,
                transportType = parsedTransportType,
                headerType = parsedHeaderType,
                transportId = parsedTransportId,
                hops = hopValue,
                contextFlag = parsedContextFlag,
                raw = raw
            )
        }

        val parsedContext = raw[offset].toInt() and 0xFF
        offset += 1
        val parsedData = raw.copyOfRange(offset, raw.size)

        return RnsPacket(
            destination = RnsDestination.fromHash(destHash, parsedDestinationType),
            data = parsedData,
            packetType = parsedPacketType,
            context = parsedContext,
            transportType = parsedTransportType,
            headerType = parsedHeaderType,
            transportId = parsedTransportId,
            hops = hopValue,
            contextFlag = parsedContextFlag,
            raw = raw
        )
    }
}
