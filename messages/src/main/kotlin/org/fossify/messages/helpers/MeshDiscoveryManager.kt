package org.fossify.messages.helpers

import android.content.Context
import android.provider.ContactsContract
import android.telephony.SmsManager
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getContactFromAddress
import org.fossify.messages.messaging.sendMessageCompat
import org.fossify.mesh.MeshContactHelper
import org.fossify.mesh.MeshIdentityStore
import org.fossify.mesh.lxmf.LxmfAddress
import org.fossify.mesh.lxmf.LxmfConstants
import org.fossify.mesh.rns.RnsDestination
import org.fossify.mesh.rns.RnsIdentity

object MeshDiscoveryManager {
    private const val MESH_URI_SCHEME = "mesh:"
    private const val LXM_URI_SCHEME = "lxm:"
    private const val LXMF_URI_SCHEME = "lxmf:"
    private const val VCARD_BEGIN = "BEGIN:VCARD"
    private const val HTTP_MESH_PREFIX = "https://cyberphone.local/mesh/"
    private const val SMS_PREFIX = "sms:"
    private const val SMSTO_PREFIX = "smsto:"
    private const val MMS_PREFIX = "mms:"
    private const val MMSTO_PREFIX = "mmsto:"

    fun isMeshAddressMessage(text: String): Boolean {
        return text.startsWith(MESH_ADDRESS_MESSAGE_PREFIX)
    }

    fun buildMeshAddressMessage(context: Context): String? {
        val address = getLocalMeshAddress(context) ?: return null
        return MESH_ADDRESS_MESSAGE_PREFIX + address
    }

    /**
     * A QR-friendly representation. Many camera scanners interpret `PREFIX:` as a URI scheme, so
     * the historical `MESHADDR1:` prefix is a bad fit for QR codes.
     */
    fun buildMeshAddressUri(context: Context): String? {
        val address = getLocalMeshAddress(context) ?: return null
        return MESH_URI_SCHEME + address
    }

    fun buildMeshIntentUri(context: Context): String? {
        val address = getLocalMeshAddress(context) ?: return null
        val normalized = LxmfAddress.normalize(address).removePrefix(MESH_URI_SCHEME)
        return "intent://mesh/$normalized#Intent;scheme=mesh;package=${context.packageName};end"
    }

    fun buildMeshSmsUri(context: Context): String? {
        val address = getLocalMeshAddress(context) ?: return null
        val normalized = LxmfAddress.normalize(address)
        return SMSTO_PREFIX + normalized
    }

    fun buildMeshHttpUri(context: Context): String? {
        val address = getLocalMeshAddress(context) ?: return null
        val normalized = LxmfAddress.normalize(address).removePrefix(MESH_URI_SCHEME)
        return HTTP_MESH_PREFIX + normalized
    }

    fun buildMeshVCard(context: Context): String? {
        val uri = buildMeshAddressUri(context) ?: return null
        val address = uri.removePrefix(MESH_URI_SCHEME).trim().removePrefix("//").trim()
        // A vCard QR is widely supported by OEM camera apps and can be "added to contacts"
        // without relying on custom scheme handling.
        return """
            BEGIN:VCARD
            VERSION:3.0
            N:Cyber;Phone;;;
            FN:Cyber Phone
            TEL:$address
            NOTE:$uri
            X-CYBERPHONE-MESH:$uri
            END:VCARD
        """.trimIndent()
    }

    fun extractMeshAddress(text: String): String? {
        val trimmed = text.trim()

        // Look for embedded tokens (vCard NOTE:, shared text, logs, etc).
        val tokenMarkers = listOf(
            MESH_URI_SCHEME,
            LXMF_URI_SCHEME,
            LXM_URI_SCHEME,
            SMS_PREFIX,
            SMSTO_PREFIX,
            MMS_PREFIX,
            MMSTO_PREFIX,
            MESH_ADDRESS_MESSAGE_PREFIX,
            "${MESH_ADDRESS_MESSAGE_PREFIX.dropLast(1)}:",
            "meshaddr1:",
            HTTP_MESH_PREFIX
        )
        for (marker in tokenMarkers) {
            val idx = trimmed.indexOf(marker, ignoreCase = true)
            if (idx >= 0) {
                val rest = trimmed.substring(idx)
                val token = rest.split(Regex("[\\s\\r\\n]"), limit = 2).firstOrNull().orEmpty()
                val parsed = extractMeshAddressToken(token)
                if (!parsed.isNullOrBlank()) return parsed
            }
        }

        // vCard payloads: try line-by-line
        if (trimmed.contains(VCARD_BEGIN, ignoreCase = true)) {
            val lines = trimmed.replace("\r", "\n").split('\n')
            lines.forEach { line ->
                val trimmedLine = line.trim()
                // Handle TEL: lines (often used by vCard importers)
                if (trimmedLine.startsWith("TEL", ignoreCase = true)) {
                    val candidate = trimmedLine.substringAfter(':', "").trim()
                    if (candidate.isNotBlank()) {
                        val normalized = LxmfAddress.normalize(candidate)
                        if (LxmfAddress.isMeshAddress(normalized)) return normalized
                    }
                }
                // Handle X- fields explicitly
                if (trimmedLine.startsWith("X-CYBERPHONE-MESH", ignoreCase = true)) {
                    val candidate = trimmedLine.substringAfter(':', "").trim()
                    if (candidate.isNotBlank()) {
                        val parsed = extractMeshAddressToken(candidate)
                        if (!parsed.isNullOrBlank()) return parsed
                    }
                }
                val parsed = extractMeshAddress(line)
                if (!parsed.isNullOrBlank()) return parsed
            }
        }

        return extractMeshAddressToken(trimmed)
    }

    private fun extractMeshAddressToken(text: String): String? {
        val token = text.trim()
        val candidate = when {
            token.startsWith("intent://", ignoreCase = true) -> {
                val base = token.substringBefore("#Intent", token)
                val uri = try {
                    android.net.Uri.parse(base)
                } catch (_: Exception) {
                    null
                }
                if (uri != null && uri.host?.equals("mesh", ignoreCase = true) == true) {
                    val segment = uri.path?.trim('/').orEmpty()
                    if (segment.isNotBlank()) MESH_URI_SCHEME + segment else null
                } else {
                    null
                }
            }
            token.startsWith("http://", ignoreCase = true) || token.startsWith("https://", ignoreCase = true) -> {
                val uri = try {
                    android.net.Uri.parse(token)
                } catch (_: Exception) {
                    null
                }
                if (uri != null && uri.host?.equals("cyberphone.local", ignoreCase = true) == true) {
                    val segment = uri.path.orEmpty().removePrefix("/mesh/").trim('/')
                    if (segment.isNotBlank()) MESH_URI_SCHEME + segment else null
                } else {
                    null
                }
            }
            // SMS/legacy prefix
            isMeshAddressMessage(token) -> token.removePrefix(MESH_ADDRESS_MESSAGE_PREFIX).trim()
            token.startsWith(SMSTO_PREFIX, ignoreCase = true) ->
                token.substringAfter(SMSTO_PREFIX, "").trim()
            token.startsWith(SMS_PREFIX, ignoreCase = true) ->
                token.substringAfter(SMS_PREFIX, "").trim()
            token.startsWith(MMSTO_PREFIX, ignoreCase = true) ->
                token.substringAfter(MMSTO_PREFIX, "").trim()
            token.startsWith(MMS_PREFIX, ignoreCase = true) ->
                token.substringAfter(MMS_PREFIX, "").trim()
            // Camera apps may interpret `MESHADDR1:` as a URI scheme ("meshaddr1:")
            token.startsWith("${MESH_ADDRESS_MESSAGE_PREFIX.dropLast(1)}:", ignoreCase = true) ->
                token.substringAfter(':').trim()
            // QR/URI-friendly schemes
            token.startsWith(MESH_URI_SCHEME, ignoreCase = true) ->
                token.removePrefix(MESH_URI_SCHEME).trim().removePrefix("//").trim()
            token.startsWith(LXM_URI_SCHEME, ignoreCase = true) ->
                token.removePrefix(LXM_URI_SCHEME).trim().removePrefix("//").trim()
            token.startsWith(LXMF_URI_SCHEME, ignoreCase = true) ->
                token.removePrefix(LXMF_URI_SCHEME).trim().removePrefix("//").trim()
            token.startsWith("meshaddr1:", ignoreCase = true) ->
                token.substringAfter(':').trim()
            else -> token
        }
        if (candidate.isNullOrBlank()) return null
        val normalized = LxmfAddress.normalize(candidate)
        return if (LxmfAddress.isMeshAddress(normalized)) normalized else null
    }

    fun getLocalMeshAddress(context: Context): String? {
        val identity = MeshIdentityStore.getOrCreate(context)
        val rnsIdentity = identity.privateKey?.let { RnsIdentity.fromPrivate(it) }
            ?: RnsIdentity.fromPublic(identity.publicKey)
        val destination = RnsDestination.create(
            identity = rnsIdentity,
            direction = RnsDestination.OUT,
            type = RnsDestination.SINGLE,
            appName = LxmfConstants.APP_NAME,
            aspects = listOf("delivery")
        )
        return LxmfAddress.encode(destination.hash)
    }

    fun handleIncomingMeshAddress(
        context: Context,
        address: String,
        threadId: Long,
        text: String,
        subscriptionId: Int,
        allowAutoReply: Boolean = true
    ): Boolean {
        val meshAddress = extractMeshAddress(text) ?: return false
        storeMeshAddressForContact(context, address, meshAddress)
        if (allowAutoReply && shouldAutoReply(context, threadId)) {
            val reply = buildMeshAddressMessage(context)
            if (!reply.isNullOrBlank()) {
                val subId = if (subscriptionId >= 0) subscriptionId else SmsManager.getDefaultSmsSubscriptionId()
                context.sendMessageCompat(reply, listOf(address), subId, emptyList())
                markAutoReplySent(context, threadId)
            }
        }
        return true
    }

    fun getMeshAddressForContact(context: Context, contact: SimpleContact): String? {
        val fromContact = when {
            contact.contactId > 0 -> MeshContactHelper.getMeshAddress(context, contact.contactId.toLong())
            contact.rawId > 0 -> MeshContactHelper.getMeshAddress(context, contact.rawId.toLong())
            else -> null
        }
        if (!fromContact.isNullOrBlank()) return fromContact
        contact.phoneNumbers.forEach { phoneNumber ->
            val rawValue = phoneNumber.value.orEmpty().trim()
            if (rawValue.isNotEmpty() && LxmfAddress.isMeshAddress(rawValue)) {
                return LxmfAddress.normalize(rawValue)
            }
            if (rawValue.isNotEmpty()) {
                val byNumber = getMeshAddressForPhoneNumber(context, rawValue)
                if (!byNumber.isNullOrBlank()) return byNumber
            }
        }
        return null
    }

    fun getMeshAddressForPhoneNumber(context: Context, phoneNumber: String): String? {
        val normalized = phoneNumber.trim()
        if (normalized.isEmpty()) return null
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(normalized)
        )
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.CONTACT_ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val contactId = cursor.getLong(0)
                if (contactId > 0) {
                    return MeshContactHelper.getMeshAddress(context, contactId)
                }
            }
        }
        return null
    }

    private fun shouldAutoReply(context: Context, threadId: Long): Boolean {
        val key = threadId.toString()
        return !context.config.meshAddressReplyThreads.contains(key)
    }

    private fun markAutoReplySent(context: Context, threadId: Long) {
        val key = threadId.toString()
        context.config.meshAddressReplyThreads = context.config.meshAddressReplyThreads.plus(key)
    }

    private fun storeMeshAddressForContact(context: Context, address: String, meshAddress: String) {
        context.getContactFromAddress(address) { contact ->
            val rawId = resolveRawContactId(context, contact) ?: return@getContactFromAddress
            MeshContactHelper.upsertMeshAddressForRawContact(context, rawId, meshAddress)
        }
    }

    private fun resolveRawContactId(context: Context, contact: SimpleContact?): Long? {
        if (contact == null) return null
        if (contact.rawId > 0) {
            return contact.rawId.toLong()
        }
        if (contact.contactId <= 0) {
            return null
        }
        return context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0",
            arrayOf(contact.contactId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }
}
