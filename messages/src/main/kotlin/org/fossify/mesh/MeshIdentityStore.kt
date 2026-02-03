package org.fossify.mesh

import android.content.Context
import android.util.Base64
import org.fossify.mesh.rns.RnsIdentity
import java.util.concurrent.TimeUnit

object MeshIdentityStore {
    private const val MAX_RATCHETS = 5
    private val RATCHET_ROTATE_INTERVAL_MS = TimeUnit.HOURS.toMillis(12)

    fun getOrCreate(context: Context): MeshIdentity {
        val config = MeshConfig.newInstance(context)
        val existingPublic = config.meshIdentityPublic
        val existingPrivate = config.meshIdentityPrivate
        if (!existingPublic.isNullOrBlank()) {
            val publicKey = Base64.decode(existingPublic, Base64.DEFAULT)
            if (!existingPrivate.isNullOrBlank()) {
                val privateKey = Base64.decode(existingPrivate, Base64.DEFAULT)
                if (privateKey.size == 64) {
                    val identity = RnsIdentity.fromPrivate(privateKey)
                    return MeshIdentity(
                        publicKey = identity.publicKey,
                        privateKey = identity.privateKey ?: privateKey
                    )
                }
            }

            if (publicKey.size == 64) {
                val identity = RnsIdentity.fromPublic(publicKey)
                return MeshIdentity(publicKey = identity.publicKey, privateKey = null)
            }
        }

        val identity = RnsIdentity.generate()
        val publicKey = identity.publicKey
        val privateKey = identity.privateKey

        config.meshIdentityPublic = Base64.encodeToString(publicKey, Base64.NO_WRAP)
        if (privateKey != null) {
            config.meshIdentityPrivate = Base64.encodeToString(privateKey, Base64.NO_WRAP)
        }

        return MeshIdentity(publicKey = publicKey, privateKey = privateKey)
    }

    fun getRatchetPrivates(context: Context): List<ByteArray> {
        val config = MeshConfig.newInstance(context)
        val stored = config.meshRatchets
        if (stored.isNullOrBlank()) return emptyList()
        return stored.split(",")
            .mapNotNull { entry ->
                val trimmed = entry.trim()
                if (trimmed.isBlank()) return@mapNotNull null
                try {
                    Base64.decode(trimmed, Base64.DEFAULT)
                } catch (_: Exception) {
                    null
                }
            }
    }

    fun ensureCurrentRatchet(context: Context): ByteArray {
        val config = MeshConfig.newInstance(context)
        val now = System.currentTimeMillis()
        val existing = getRatchetPrivates(context).toMutableList()
        val needsRotate = existing.isEmpty() || now - config.meshRatchetsUpdated >= RATCHET_ROTATE_INTERVAL_MS
        if (needsRotate) {
            val ratchet = RnsIdentity.generateRatchetPrivate()
            existing.add(0, ratchet)
            while (existing.size > MAX_RATCHETS) {
                existing.removeAt(existing.lastIndex)
            }
            config.meshRatchets = existing.joinToString(",") { Base64.encodeToString(it, Base64.NO_WRAP) }
            config.meshRatchetsUpdated = now
        }
        if (existing.isEmpty()) {
            val ratchet = RnsIdentity.generateRatchetPrivate()
            config.meshRatchets = Base64.encodeToString(ratchet, Base64.NO_WRAP)
            config.meshRatchetsUpdated = now
            return ratchet
        }
        return existing.first()
    }

    fun rotateIdentity(context: Context): MeshIdentity {
        val config = MeshConfig.newInstance(context)
        val identity = RnsIdentity.generate()
        val publicKey = identity.publicKey
        val privateKey = identity.privateKey
        config.meshIdentityPublic = Base64.encodeToString(publicKey, Base64.NO_WRAP)
        if (privateKey != null) {
            config.meshIdentityPrivate = Base64.encodeToString(privateKey, Base64.NO_WRAP)
        } else {
            config.meshIdentityPrivate = ""
        }
        config.meshRatchets = ""
        config.meshRatchetsUpdated = 0L
        return MeshIdentity(publicKey = publicKey, privateKey = privateKey)
    }
}
