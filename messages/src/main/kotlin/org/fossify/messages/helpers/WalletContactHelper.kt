package org.fossify.messages.helpers

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract

/**
 * Contacts integration for wallet destinations.
 *
 * This lives in the :messages module as well (duplicated from :app) so the Profile/QR flows can
 * read/write wallet destinations without depending on the :app module.
 */
object WalletContactHelper {
    private const val WALLET_ONCHAIN_LABEL = "Bitcoin"
    private const val WALLET_LIGHTNING_LABEL = "Lightning"
    private const val WALLET_ONCHAIN_PLACEHOLDER = "bitcoin:"
    private const val WALLET_LIGHTNING_PLACEHOLDER = "lightning:"

    // Legacy IM storage (kept for migration / cleanup).
    private const val LEGACY_WALLET_PROTOCOL = "bitcoin"

    fun upsertWalletDestination(context: Context, rawId: Long, destination: String): Boolean {
        val value = destination.trim()
        return if (looksLikeLightningDestination(value)) {
            upsertWalletLightningDestination(context, rawId, value)
        } else {
            upsertWalletOnchainDestination(context, rawId, value)
        }
    }

    fun upsertWalletOnchainDestination(context: Context, rawId: Long, destination: String): Boolean {
        val trimmed = destination.trim()
        val ok = upsertWalletPhoneForRawContact(
            context = context,
            rawId = rawId,
            destination = trimmed,
            label = WALLET_ONCHAIN_LABEL
        )
        // Clean up legacy IM entry if it exists to avoid duplicate fields in editors.
        deleteLegacyImRowForRawContact(context, rawId)
        return ok
    }

    fun upsertWalletLightningDestination(context: Context, rawId: Long, destination: String): Boolean {
        val trimmed = destination.trim()
        val ok = upsertWalletPhoneForRawContact(
            context = context,
            rawId = rawId,
            destination = trimmed,
            label = WALLET_LIGHTNING_LABEL
        )
        return ok
    }

    fun addWalletInsertExtras(
        intent: Intent,
        onchainDestination: String = WALLET_ONCHAIN_PLACEHOLDER,
        lightningDestination: String = WALLET_LIGHTNING_PLACEHOLDER
    ) {
        val data = intent.getParcelableArrayListExtra<ContentValues>(ContactsContract.Intents.Insert.DATA)
            ?: arrayListOf()
        val onchainValues = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM)
            put(ContactsContract.CommonDataKinds.Phone.LABEL, WALLET_ONCHAIN_LABEL)
            put(ContactsContract.CommonDataKinds.Phone.NUMBER, onchainDestination.trim())
        }
        val lightningValues = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM)
            put(ContactsContract.CommonDataKinds.Phone.LABEL, WALLET_LIGHTNING_LABEL)
            put(ContactsContract.CommonDataKinds.Phone.NUMBER, lightningDestination.trim())
        }
        data.add(onchainValues)
        data.add(lightningValues)
        intent.putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, data)
    }

    fun dedupeWalletDestinationRowsForRawContact(context: Context, rawId: Long) {
        if (rawId <= 0L) return
        dedupeWalletPhoneRowsForRawContact(context, rawId, WALLET_ONCHAIN_LABEL, preferredValue = null)
        dedupeWalletPhoneRowsForRawContact(context, rawId, WALLET_LIGHTNING_LABEL, preferredValue = null)
    }

    private fun upsertWalletPhoneForRawContact(
        context: Context,
        rawId: Long,
        destination: String,
        label: String
    ): Boolean {
        val resolver = context.contentResolver
        val (selection, selectionArgs) = buildWalletPhoneSelection(rawId, label)

        val values = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.CommonDataKinds.Phone.NUMBER, destination.trim())
            put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM)
            put(ContactsContract.CommonDataKinds.Phone.LABEL, label)
        }

        val updated = resolver.update(
            ContactsContract.Data.CONTENT_URI,
            values,
            selection,
            selectionArgs,
        )

        val success = if (updated > 0) {
            true
        } else {
            resolver.insert(ContactsContract.Data.CONTENT_URI, values) != null
        }
        dedupeWalletPhoneRowsForRawContact(context, rawId, label, destination.trim())
        return success
    }

    private fun buildWalletPhoneSelection(rawId: Long, label: String): Pair<String, Array<String>> {
        val selection =
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Phone.TYPE}=? AND ${ContactsContract.CommonDataKinds.Phone.LABEL}=?"
        val selectionArgs = arrayOf(
            rawId.toString(),
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM.toString(),
            label,
        )
        return selection to selectionArgs
    }

    private fun dedupeWalletPhoneRowsForRawContact(
        context: Context,
        rawId: Long,
        label: String,
        preferredValue: String?
    ) {
        val (selection, selectionArgs) = buildWalletPhoneSelection(rawId, label)
        val rows = ArrayList<Pair<Long, String>>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            selection,
            selectionArgs,
            "${ContactsContract.Data._ID} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(cursor.getLong(0) to (cursor.getString(1) ?: ""))
            }
        }

        if (rows.isEmpty()) return
        val normalizedPreferred = preferredValue?.trim()?.takeIf { it.isNotBlank() }

        val keepId = rows.firstOrNull {
            normalizedPreferred != null && it.second.trim().equals(normalizedPreferred, ignoreCase = true)
        }?.first
            ?: rows.firstOrNull {
                val value = it.second.trim()
                value.isNotBlank() && !isWalletPlaceholder(label, value)
            }?.first
            ?: rows.first().first

        if (!normalizedPreferred.isNullOrBlank()) {
            val updateValues = ContentValues().apply {
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, normalizedPreferred)
            }
            context.contentResolver.update(
                ContactsContract.Data.CONTENT_URI,
                updateValues,
                "${ContactsContract.Data._ID}=?",
                arrayOf(keepId.toString())
            )
        }

        val duplicateIds = rows.asSequence()
            .map { it.first }
            .filter { it != keepId }
            .toList()
        if (duplicateIds.isEmpty()) return

        val placeholders = duplicateIds.joinToString(",") { "?" }
        context.contentResolver.delete(
            ContactsContract.Data.CONTENT_URI,
            "${ContactsContract.Data._ID} IN ($placeholders)",
            duplicateIds.map { it.toString() }.toTypedArray()
        )
    }

    private fun isWalletPlaceholder(label: String, value: String): Boolean {
        return when {
            label.equals(WALLET_ONCHAIN_LABEL, ignoreCase = true) ->
                value.equals(WALLET_ONCHAIN_PLACEHOLDER, ignoreCase = true)

            label.equals(WALLET_LIGHTNING_LABEL, ignoreCase = true) ->
                value.equals(WALLET_LIGHTNING_PLACEHOLDER, ignoreCase = true)

            else -> false
        }
    }

    private fun deleteLegacyImRowForRawContact(context: Context, rawId: Long) {
        val resolver = context.contentResolver
        val (selection, selectionArgs) = buildLegacyImSelection(
            idColumn = ContactsContract.Data.RAW_CONTACT_ID,
            idValue = rawId.toString()
        )
        resolver.delete(ContactsContract.Data.CONTENT_URI, selection, selectionArgs)
    }

    private fun buildLegacyImSelection(idColumn: String, idValue: String): Pair<String, Array<String>> {
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
            LEGACY_WALLET_PROTOCOL,
            ContactsContract.CommonDataKinds.Im.TYPE_CUSTOM.toString(),
            WALLET_ONCHAIN_LABEL
        )
        return selection to selectionArgs
    }

    private fun looksLikeLightningDestination(value: String): Boolean {
        val v = value.trim()
        if (v.isBlank()) return false
        if (v.startsWith("lightning:", ignoreCase = true)) return true
        if (v.startsWith("ln", ignoreCase = true)) return true
        if (v.contains('@') && !v.contains(' ')) return true
        return false
    }
}
