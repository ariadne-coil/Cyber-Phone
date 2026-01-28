package org.fossify.messages.helpers

import android.content.Context
import android.provider.ContactsContract
import android.util.Base64
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_WRITE_CONTACTS
import org.fossify.messages.R
import org.fossify.messages.extensions.config
import org.fossify.messages.messaging.sendMessageCompat
import org.fossify.messages.extensions.getContactFromAddress
import org.fossify.mesh.rns.RnsHkdf
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object E2eManager {
    private const val KEY_ALGORITHM = "EC"
    private const val CURVE_NAME = "secp256r1"
    private const val AGREEMENT_ALGORITHM = "ECDH"
    private const val CIPHER_TRANSFORM = "AES/GCM/NoPadding"
    private const val AES_KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val CONTACT_KEY_MIME = "vnd.android.cursor.item/vnd.cyberphone.e2e_key"
    private const val CONTACT_KEY_LABEL = "Cyber Phone E2E Key"

    fun ensureKeyPair(context: Context) {
        val cfg = context.config
        if (cfg.e2ePublicKey.isNotBlank() && cfg.e2ePrivateKey.isNotBlank()) {
            return
        }

        val keyPair = generateKeyPair()
        cfg.e2ePublicKey = encodeBase64(keyPair.public.encoded)
        cfg.e2ePrivateKey = encodeBase64(keyPair.private.encoded)
    }

    fun regenerateKeyPair(context: Context) {
        val cfg = context.config
        val keyPair = generateKeyPair()
        cfg.e2ePublicKey = encodeBase64(keyPair.public.encoded)
        cfg.e2ePrivateKey = encodeBase64(keyPair.private.encoded)
        cfg.e2eSharedSecrets = ""
        cfg.e2eEncryptedThreads = emptySet()
        cfg.e2eKeySentThreads = emptySet()
        cfg.e2eKeySetTimes = ""
    }

    fun getPublicKeyBase64(context: Context): String {
        ensureKeyPair(context)
        return context.config.e2ePublicKey
    }

    fun getPrivateKeyBase64(context: Context): String {
        ensureKeyPair(context)
        return context.config.e2ePrivateKey
    }

    fun buildBackupData(context: Context): String {
        ensureKeyPair(context)
        return buildString {
            appendLine("cyber_phone_e2e_key_backup_v1")
            appendLine("public=${context.config.e2ePublicKey}")
            appendLine("private=${context.config.e2ePrivateKey}")
        }
    }

    fun importBackupData(context: Context, data: String): Boolean {
        val lines = data.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty() || lines.first() != "cyber_phone_e2e_key_backup_v1") {
            return false
        }
        val map = lines.drop(1).mapNotNull {
            val split = it.split("=", limit = 2)
            if (split.size != 2) null else split[0] to split[1]
        }.toMap()
        val publicKey = map["public"].orEmpty()
        val privateKey = map["private"].orEmpty()
        if (publicKey.isBlank() || privateKey.isBlank()) {
            return false
        }

        val cfg = context.config
        cfg.e2ePublicKey = publicKey
        cfg.e2ePrivateKey = privateKey
        cfg.e2eSharedSecrets = ""
        cfg.e2eEncryptedThreads = emptySet()
        cfg.e2eKeySentThreads = emptySet()
        cfg.e2eKeySetTimes = ""
        return true
    }

    fun isKeyExchangeMessage(text: String): Boolean {
        return text.startsWith(E2E_KEY_MESSAGE_PREFIX)
    }

    fun buildKeyExchangeMessage(context: Context): String {
        return E2E_KEY_MESSAGE_PREFIX + getPublicKeyBase64(context)
    }

    fun isEncryptedMessage(text: String): Boolean {
        return text.startsWith(E2E_ENCRYPTED_MESSAGE_PREFIX)
    }

    fun handleIncomingKeyExchange(
        context: Context,
        threadId: Long,
        address: String,
        text: String,
        receivedAtMillis: Long,
        subscriptionId: Int,
    ): Boolean {
        val publicKeyBase64 = extractKeyFromMessage(text) ?: return false
        val secret = deriveSharedSecret(context, publicKeyBase64) ?: return false
        storeSharedSecret(context, threadId, secret, receivedAtMillis / 1000L)
        setThreadEncrypted(context, threadId, true)
        storeContactPublicKeyForAddress(context, address, publicKeyBase64)

        if (!isKeySent(context, threadId)) {
            markKeySent(context, threadId)
            val message = buildKeyExchangeMessage(context)
            context.sendMessageCompat(message, listOf(address), subscriptionId, emptyList())
        }
        return true
    }

    fun encryptOutgoing(context: Context, threadId: Long, text: String): String? {
        if (!isThreadEncrypted(context, threadId)) {
            return null
        }
        if (isKeyExchangeMessage(text) || isEncryptedMessage(text)) {
            return null
        }

        val secret = getSharedSecret(context, threadId) ?: return null
        val key = deriveAesKey(secret, threadId)
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(nonce.size + ciphertext.size)
        nonce.copyInto(combined, 0, 0, nonce.size)
        ciphertext.copyInto(combined, nonce.size, 0, ciphertext.size)
        return E2E_ENCRYPTED_MESSAGE_PREFIX + encodeBase64(combined)
    }

    data class DisplayResult(
        val body: String,
        val shouldPersist: Boolean
    )

    fun getDisplayBody(context: Context, threadId: Long, text: String): String {
        return getDisplayResult(context, threadId, text, null).body
    }

    fun getDisplayBody(
        context: Context,
        threadId: Long,
        text: String,
        messageDateSeconds: Long?
    ): String {
        return getDisplayResult(context, threadId, text, messageDateSeconds).body
    }

    fun getDisplayResult(
        context: Context,
        threadId: Long,
        text: String,
        messageDateSeconds: Long?
    ): DisplayResult {
        if (isKeyExchangeMessage(text)) {
            return DisplayResult(context.getString(R.string.e2e_key_exchange_message), false)
        }
        if (!isEncryptedMessage(text)) {
            return DisplayResult(text, false)
        }

        val keySetTime = getKeySetTimeSeconds(context, threadId)
        if (keySetTime != null && messageDateSeconds != null && messageDateSeconds < keySetTime) {
            return DisplayResult(text, false)
        }

        val secret = getSharedSecret(context, threadId)
            ?: return DisplayResult(text, false)
        val payload = decodeBase64(text.removePrefix(E2E_ENCRYPTED_MESSAGE_PREFIX)) ?: return context.getString(
            R.string.e2e_decrypt_failed
        ).let { DisplayResult(it, false) }
        if (payload.size <= NONCE_BYTES) {
            return DisplayResult(context.getString(R.string.e2e_decrypt_failed), false)
        }

        val nonce = payload.copyOfRange(0, NONCE_BYTES)
        val ciphertext = payload.copyOfRange(NONCE_BYTES, payload.size)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
            val key = deriveAesKey(secret, threadId)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            val plain = cipher.doFinal(ciphertext)
            DisplayResult(String(plain, Charsets.UTF_8), true)
        } catch (_: Exception) {
            DisplayResult(context.getString(R.string.e2e_decrypt_failed), false)
        }
    }

    fun isThreadEncrypted(context: Context, threadId: Long): Boolean {
        return context.config.e2eEncryptedThreads.contains(threadId.toString())
    }

    fun setThreadEncrypted(context: Context, threadId: Long, enabled: Boolean) {
        val cfg = context.config
        cfg.e2eEncryptedThreads = if (enabled) {
            cfg.e2eEncryptedThreads.plus(threadId.toString())
        } else {
            cfg.e2eEncryptedThreads.minus(threadId.toString())
        }
    }

    fun markKeySent(context: Context, threadId: Long) {
        val cfg = context.config
        cfg.e2eKeySentThreads = cfg.e2eKeySentThreads.plus(threadId.toString())
    }

    fun isKeySent(context: Context, threadId: Long): Boolean {
        return context.config.e2eKeySentThreads.contains(threadId.toString())
    }

    fun hasSharedSecret(context: Context, threadId: Long): Boolean {
        return getSharedSecret(context, threadId) != null
    }

    fun ensureSharedSecretFromContact(
        context: Context,
        threadId: Long,
        contact: org.fossify.commons.models.SimpleContact,
    ): Boolean {
        if (hasSharedSecret(context, threadId)) {
            return true
        }
        val publicKey = getContactPublicKey(context, contact) ?: return false
        val secret = deriveSharedSecret(context, publicKey) ?: return false
        storeSharedSecret(context, threadId, secret, System.currentTimeMillis() / 1000L)
        return true
    }

    fun clearSharedSecret(context: Context, threadId: Long) {
        val cfg = context.config
        val secrets = decodeSharedSecrets(cfg.e2eSharedSecrets).toMutableMap()
        secrets.remove(threadId.toString())
        cfg.e2eSharedSecrets = encodeSharedSecrets(secrets)
        val times = decodeKeySetTimes(cfg.e2eKeySetTimes).toMutableMap()
        times.remove(threadId.toString())
        cfg.e2eKeySetTimes = encodeKeySetTimes(times)
    }

    fun storeContactPublicKey(
        context: Context,
        contact: org.fossify.commons.models.SimpleContact,
        publicKey: String,
    ): Boolean {
        if (!context.hasPermission(PERMISSION_WRITE_CONTACTS)) {
            return false
        }
        val rawId = resolveRawContactId(context, contact) ?: return false
        val resolver = context.contentResolver
        val storedCustom = upsertContactKeyCustomData(resolver, rawId, publicKey)
        val storedIm = upsertContactKeyImData(resolver, rawId, publicKey)
        return storedCustom || storedIm
    }

    fun removeContactPublicKey(
        context: Context,
        contact: org.fossify.commons.models.SimpleContact,
    ): Boolean {
        if (!context.hasPermission(PERMISSION_WRITE_CONTACTS)) {
            return false
        }
        val rawId = resolveRawContactId(context, contact) ?: return false
        val resolver = context.contentResolver
        val customSelection = "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.Data.RAW_CONTACT_ID}=?"
        val customArgs = arrayOf(CONTACT_KEY_MIME, rawId.toString())
        val removedCustom = resolver.delete(ContactsContract.Data.CONTENT_URI, customSelection, customArgs) > 0
        val removedIm = deleteContactKeyImData(resolver, rawId)
        return removedCustom || removedIm
    }

    fun getContactPublicKey(
        context: Context,
        contact: org.fossify.commons.models.SimpleContact,
    ): String? {
        val rawId = resolveRawContactId(context, contact)
        val resolver = context.contentResolver
        val selection: String
        val selectionArgs: Array<String>
        if (rawId != null) {
            selection = "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.Data.RAW_CONTACT_ID}=?"
            selectionArgs = arrayOf(CONTACT_KEY_MIME, rawId.toString())
        } else if (contact.contactId > 0) {
            selection = "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.Data.CONTACT_ID}=?"
            selectionArgs = arrayOf(CONTACT_KEY_MIME, contact.contactId.toString())
        } else {
            return null
        }

        val customKey = resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DATA1),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        if (!customKey.isNullOrBlank()) {
            return customKey
        }

        return getContactKeyFromImData(resolver, rawId, contact.contactId)
    }

    fun storeContactPublicKeyForAddress(context: Context, address: String, publicKey: String) {
        context.getContactFromAddress(address) { contact ->
            if (contact != null) {
                storeContactPublicKey(context, contact, publicKey)
            }
        }
    }

    private fun resolveRawContactId(
        context: Context,
        contact: org.fossify.commons.models.SimpleContact,
    ): Long? {
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

    private fun upsertContactKeyCustomData(
        resolver: android.content.ContentResolver,
        rawId: Long,
        publicKey: String,
    ): Boolean {
        val selection = "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.Data.RAW_CONTACT_ID}=?"
        val selectionArgs = arrayOf(CONTACT_KEY_MIME, rawId.toString())
        val existingId = resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

        val values = android.content.ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, CONTACT_KEY_MIME)
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.Data.DATA1, publicKey)
        }

        return if (existingId != null) {
            resolver.update(
                ContactsContract.Data.CONTENT_URI,
                values,
                "${ContactsContract.Data._ID}=?",
                arrayOf(existingId.toString())
            ) > 0
        } else {
            resolver.insert(ContactsContract.Data.CONTENT_URI, values) != null
        }
    }

    private fun upsertContactKeyImData(
        resolver: android.content.ContentResolver,
        rawId: Long,
        publicKey: String,
    ): Boolean {
        val (selection, selectionArgs) = buildImSelection(
            idColumn = ContactsContract.Data.RAW_CONTACT_ID,
            idValue = rawId.toString()
        )
        val existingId = resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

        val values = android.content.ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE)
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.CommonDataKinds.Im.DATA, publicKey)
            put(ContactsContract.CommonDataKinds.Im.TYPE, ContactsContract.CommonDataKinds.Im.TYPE_CUSTOM)
            put(ContactsContract.CommonDataKinds.Im.LABEL, CONTACT_KEY_LABEL)
            put(ContactsContract.CommonDataKinds.Im.PROTOCOL, ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM)
            put(ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL, CONTACT_KEY_LABEL)
        }

        return if (existingId != null) {
            resolver.update(
                ContactsContract.Data.CONTENT_URI,
                values,
                "${ContactsContract.Data._ID}=?",
                arrayOf(existingId.toString())
            ) > 0
        } else {
            resolver.insert(ContactsContract.Data.CONTENT_URI, values) != null
        }
    }

    private fun deleteContactKeyImData(
        resolver: android.content.ContentResolver,
        rawId: Long,
    ): Boolean {
        val (selection, selectionArgs) = buildImSelection(
            idColumn = ContactsContract.Data.RAW_CONTACT_ID,
            idValue = rawId.toString()
        )
        return resolver.delete(ContactsContract.Data.CONTENT_URI, selection, selectionArgs) > 0
    }

    private fun getContactKeyFromImData(
        resolver: android.content.ContentResolver,
        rawId: Long?,
        contactId: Int,
    ): String? {
        val idColumn: String
        val idValue: String
        if (rawId != null) {
            idColumn = ContactsContract.Data.RAW_CONTACT_ID
            idValue = rawId.toString()
        } else if (contactId > 0) {
            idColumn = ContactsContract.Data.CONTACT_ID
            idValue = contactId.toString()
        } else {
            return null
        }
        val (selection, selectionArgs) = buildImSelection(idColumn, idValue)
        return resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Im.DATA),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() }
    }

    private fun buildImSelection(idColumn: String, idValue: String): Pair<String, Array<String>> {
        val selection = buildString {
            append("${ContactsContract.Data.MIMETYPE}=?")
            append(" AND $idColumn=?")
            append(" AND ((")
            append("${ContactsContract.CommonDataKinds.Im.PROTOCOL}=? AND ")
            append("${ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL}=?")
            append(") OR (")
            append("${ContactsContract.CommonDataKinds.Im.TYPE}=? AND ")
            append("${ContactsContract.CommonDataKinds.Im.LABEL}=?")
            append("))")
        }
        val selectionArgs = arrayOf(
            ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE,
            idValue,
            ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM.toString(),
            CONTACT_KEY_LABEL,
            ContactsContract.CommonDataKinds.Im.TYPE_CUSTOM.toString(),
            CONTACT_KEY_LABEL
        )
        return selection to selectionArgs
    }

    private fun storeSharedSecret(
        context: Context,
        threadId: Long,
        secret: ByteArray,
        keySetTimeSeconds: Long
    ) {
        val cfg = context.config
        val secrets = decodeSharedSecrets(cfg.e2eSharedSecrets).toMutableMap()
        secrets[threadId.toString()] = encodeBase64(secret)
        cfg.e2eSharedSecrets = encodeSharedSecrets(secrets)
        setKeySetTimeSeconds(context, threadId, keySetTimeSeconds)
    }

    private fun getSharedSecret(context: Context, threadId: Long): ByteArray? {
        val secrets = decodeSharedSecrets(context.config.e2eSharedSecrets)
        val encoded = secrets[threadId.toString()] ?: return null
        return decodeBase64(encoded)
    }

    private fun deriveSharedSecret(context: Context, remotePublicKeyBase64: String): ByteArray? {
        ensureKeyPair(context)
        val privateKey = decodePrivateKey(context.config.e2ePrivateKey) ?: return null
        val publicKey = decodePublicKey(remotePublicKeyBase64) ?: return null
        val agreement = KeyAgreement.getInstance(AGREEMENT_ALGORITHM)
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }

    private fun deriveAesKey(secret: ByteArray, threadId: Long): ByteArray {
        val context = threadId.toString().toByteArray(Charsets.UTF_8)
        return RnsHkdf.derive(AES_KEY_BYTES, secret, context = context)
    }

    private fun extractKeyFromMessage(text: String): String? {
        if (!isKeyExchangeMessage(text)) {
            return null
        }
        val encoded = text.removePrefix(E2E_KEY_MESSAGE_PREFIX).trim()
        if (encoded.isEmpty()) {
            return null
        }
        return encoded
    }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        generator.initialize(ECGenParameterSpec(CURVE_NAME), SecureRandom())
        return generator.generateKeyPair()
    }

    private fun decodePrivateKey(encoded: String): PrivateKey? {
        return runCatching {
            val bytes = decodeBase64(encoded) ?: return null
            val spec = PKCS8EncodedKeySpec(bytes)
            KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(spec)
        }.getOrNull()
    }

    private fun decodePublicKey(encoded: String): PublicKey? {
        return runCatching {
            val bytes = decodeBase64(encoded) ?: return null
            val spec = X509EncodedKeySpec(bytes)
            KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(spec)
        }.getOrNull()
    }

    private fun encodeBase64(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decodeBase64(data: String): ByteArray? {
        return runCatching { Base64.decode(data, Base64.NO_WRAP) }.getOrNull()
    }

    private fun encodeSharedSecrets(secrets: Map<String, String>): String {
        if (secrets.isEmpty()) {
            return ""
        }
        return org.fossify.messages.extensions.gson.gson.toJson(secrets)
    }

    private fun decodeSharedSecrets(data: String): Map<String, String> {
        if (data.isBlank()) {
            return emptyMap()
        }
        val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
        return org.fossify.messages.extensions.gson.gson.fromJson(data, type) ?: emptyMap()
    }

    private fun setKeySetTimeSeconds(context: Context, threadId: Long, timeSeconds: Long) {
        val cfg = context.config
        val times = decodeKeySetTimes(cfg.e2eKeySetTimes).toMutableMap()
        times[threadId.toString()] = timeSeconds.toString()
        cfg.e2eKeySetTimes = encodeKeySetTimes(times)
    }

    private fun getKeySetTimeSeconds(context: Context, threadId: Long): Long? {
        val times = decodeKeySetTimes(context.config.e2eKeySetTimes)
        return times[threadId.toString()]?.toLongOrNull()
    }

    private fun encodeKeySetTimes(times: Map<String, String>): String {
        if (times.isEmpty()) {
            return ""
        }
        return org.fossify.messages.extensions.gson.gson.toJson(times)
    }

    private fun decodeKeySetTimes(data: String): Map<String, String> {
        if (data.isBlank()) {
            return emptyMap()
        }
        val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
        return org.fossify.messages.extensions.gson.gson.fromJson(data, type) ?: emptyMap()
    }
}
