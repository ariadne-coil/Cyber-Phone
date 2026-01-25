package org.fossify.mesh.rns

class RnsDestination private constructor(
    val identity: RnsIdentity?,
    val direction: Int,
    val type: Int,
    val appName: String,
    val aspects: List<String>,
    val name: String,
    val nameHash: ByteArray,
    val hash: ByteArray
) {
    companion object {
        const val SINGLE = 0x00
        const val GROUP = 0x01
        const val PLAIN = 0x02
        const val LINK = 0x03

        const val IN = 0x11
        const val OUT = 0x12

        fun expandName(identity: RnsIdentity?, appName: String, aspects: List<String>): String {
            require(!appName.contains('.')) { "Dots cannot be used in app names" }
            val builder = StringBuilder(appName)
            for (aspect in aspects) {
                require(!aspect.contains('.')) { "Dots cannot be used in aspects" }
                builder.append('.').append(aspect)
            }
            if (identity != null) {
                builder.append('.').append(identity.hexHash)
            }
            return builder.toString()
        }

        fun hash(identity: RnsIdentity?, appName: String, aspects: List<String>): ByteArray {
            val name = expandName(null, appName, aspects)
            val nameHash = RnsHash.sha256(name.toByteArray(Charsets.UTF_8))
                .copyOfRange(0, RnsConstants.NAME_HASH_LENGTH_BITS / 8)
            val material = if (identity != null) {
                nameHash + identity.hash
            } else {
                nameHash
            }
            return RnsHash.sha256(material)
                .copyOfRange(0, RnsConstants.TRUNCATED_HASH_LENGTH_BITS / 8)
        }

        fun create(identity: RnsIdentity, direction: Int, type: Int, appName: String, aspects: List<String>): RnsDestination {
            require(type != PLAIN) { "Plain destinations cannot hold an identity" }
            val name = expandName(identity, appName, aspects)
            val nameHash = RnsHash.sha256(expandName(null, appName, aspects).toByteArray(Charsets.UTF_8))
                .copyOfRange(0, RnsConstants.NAME_HASH_LENGTH_BITS / 8)
            val hash = hash(identity, appName, aspects)
            return RnsDestination(identity, direction, type, appName, aspects, name, nameHash, hash)
        }

        fun createWithHash(
            identity: RnsIdentity,
            direction: Int,
            type: Int,
            appName: String,
            aspects: List<String>,
            hashOverride: ByteArray
        ): RnsDestination {
            require(type != PLAIN) { "Plain destinations cannot hold an identity" }
            val name = expandName(identity, appName, aspects)
            val nameHash = RnsHash.sha256(expandName(null, appName, aspects).toByteArray(Charsets.UTF_8))
                .copyOfRange(0, RnsConstants.NAME_HASH_LENGTH_BITS / 8)
            return RnsDestination(identity, direction, type, appName, aspects, name, nameHash, hashOverride)
        }

        fun fromHash(hash: ByteArray, type: Int): RnsDestination {
            return RnsDestination(
                identity = null,
                direction = OUT,
                type = type,
                appName = "",
                aspects = emptyList(),
                name = "",
                nameHash = ByteArray(0),
                hash = hash
            )
        }

        fun appAndAspectsFromName(fullName: String): Pair<String, List<String>> {
            val components = fullName.split(".")
            val app = components.firstOrNull().orEmpty()
            val aspects = if (components.size > 1) components.subList(1, components.size) else emptyList()
            return app to aspects
        }
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        return when (type) {
            PLAIN -> plaintext
            SINGLE -> {
                val id = identity ?: error("Single destination requires identity")
                id.encrypt(plaintext)
            }
            GROUP -> error("Group destination encryption not implemented")
            else -> plaintext
        }
    }

    fun decrypt(ciphertext: ByteArray): ByteArray? {
        return when (type) {
            PLAIN -> ciphertext
            SINGLE -> {
                val id = identity ?: error("Single destination requires identity")
                id.decrypt(ciphertext)
            }
            GROUP -> error("Group destination decryption not implemented")
            else -> ciphertext
        }
    }

    fun sign(data: ByteArray): ByteArray? {
        return identity?.sign(data)
    }
}
