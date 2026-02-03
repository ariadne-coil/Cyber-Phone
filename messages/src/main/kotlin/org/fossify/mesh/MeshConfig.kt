package org.fossify.mesh

import android.content.Context
import org.fossify.commons.helpers.BaseConfig

class MeshConfig(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = MeshConfig(context)
    }

    var meshMode: Int
        get() = prefs.getInt(MESH_MODE, MeshMode.STANDARD_ONLY.id)
        set(value) = prefs.edit().putInt(MESH_MODE, value).apply()

    var meshRoutingEnabled: Boolean
        get() = prefs.getBoolean(MESH_ROUTING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(MESH_ROUTING_ENABLED, value).apply()

    fun getMeshMode(): MeshMode = MeshMode.fromId(meshMode)

    var meshIdentityPublic: String?
        get() = prefs.getString(MESH_IDENTITY_PUBLIC, null)
        set(value) = prefs.edit().putString(MESH_IDENTITY_PUBLIC, value).apply()

    var meshIdentityPrivate: String?
        get() = prefs.getString(MESH_IDENTITY_PRIVATE, null)
        set(value) = prefs.edit().putString(MESH_IDENTITY_PRIVATE, value).apply()

    var meshRatchets: String?
        get() = prefs.getString(MESH_RATCHETS, null)
        set(value) = prefs.edit().putString(MESH_RATCHETS, value).apply()

    var meshRatchetsUpdated: Long
        get() = prefs.getLong(MESH_RATCHETS_UPDATED, 0L)
        set(value) = prefs.edit().putLong(MESH_RATCHETS_UPDATED, value).apply()

    var meshCallQuality: Int
        get() = prefs.getInt(MESH_CALL_QUALITY, 0)
        set(value) = prefs.edit().putInt(MESH_CALL_QUALITY, value).apply()

    var meshOutboundStampCosts: String?
        get() = prefs.getString(MESH_OUTBOUND_STAMP_COSTS, null)
        set(value) = prefs.edit().putString(MESH_OUTBOUND_STAMP_COSTS, value).apply()
}
