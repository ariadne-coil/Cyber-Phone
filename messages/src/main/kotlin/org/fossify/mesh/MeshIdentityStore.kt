package org.fossify.mesh

import android.content.Context
import android.util.Base64
import org.fossify.mesh.rns.RnsIdentity

object MeshIdentityStore {
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
}
