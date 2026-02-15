package org.fossify.phone.wallet

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract

object WalletContactHelper {
    // We store a wallet destination as a custom Phone row so it is editable in stock contact editors
    // and shows up directly under phone numbers (similar to Mesh).
    private const val WALLET_LABEL = "Bitcoin"
    private const val WALLET_PLACEHOLDER = "bitcoin:"

    // Legacy storage (kept for backward compatibility / migration).
    private const val LEGACY_WALLET_PROTOCOL = "bitcoin"

    fun getWalletDestination(context: Context, rawId: Long?, contactId: Int): String? {
        val resolver = context.contentResolver

        val idColumn: String
        val idValue: String
        if (rawId != null && rawId > 0L) {
            idColumn = ContactsContract.Data.RAW_CONTACT_ID
            idValue = rawId.toString()
        } else if (contactId > 0) {
            idColumn = ContactsContract.Data.CONTACT_ID
            idValue = contactId.toString()
        } else {
            return null
        }

        // 1) Prefer a custom Phone row, as it is user-editable in stock contact editors.
        getWalletDestinationFromPhones(context, idColumn, idValue)?.let { return it }

        // 2) Legacy fallback: custom IM row.
        val (selection, selectionArgs) = buildLegacyImSelection(idColumn, idValue)
        return resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Im.DATA),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.trim()
            ?.takeIf { it.isNotBlank() }
            // Ignore our editor placeholder.
            ?.takeIf { !it.equals(WALLET_PLACEHOLDER, ignoreCase = true) }
    }

    fun upsertWalletDestination(context: Context, rawId: Long, destination: String): Boolean {
        val resolver = context.contentResolver

        val ok = upsertWalletPhoneForRawContact(context, rawId, destination.trim())

        // Remove legacy IM entry to avoid confusing duplicate fields in editors.
        deleteLegacyImRowForRawContact(context, rawId)

        return ok
    }

    fun ensureWalletDestinationRowForRawContact(context: Context, rawId: Long) {
        if (rawId <= 0L) return
        if (hasWalletPhoneRow(context, rawId)) return

        // If legacy data exists, migrate it so users can edit it in the Phone section.
        val legacy = getLegacyImDestinationForRawContact(context, rawId)
        if (!legacy.isNullOrBlank() && !legacy.equals(WALLET_PLACEHOLDER, ignoreCase = true)) {
            upsertWalletPhoneForRawContact(context, rawId, legacy.trim())
            deleteLegacyImRowForRawContact(context, rawId)
            return
        }

        // Insert a placeholder row so the field becomes visible in most contact editors.
        upsertWalletPhoneForRawContact(context, rawId, WALLET_PLACEHOLDER)
    }

    fun addWalletInsertExtras(intent: Intent, destination: String = WALLET_PLACEHOLDER) {
        val data = intent.getParcelableArrayListExtra<ContentValues>(ContactsContract.Intents.Insert.DATA)
            ?: arrayListOf()
        val values = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM)
            put(ContactsContract.CommonDataKinds.Phone.LABEL, WALLET_LABEL)
            put(ContactsContract.CommonDataKinds.Phone.NUMBER, destination.trim())
        }
        data.add(values)
        intent.putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, data)
    }

    fun deleteWalletDestination(context: Context, rawId: Long): Boolean {
        val resolver = context.contentResolver
        val deletedPhone = deleteWalletPhoneForRawContact(context, rawId)
        deleteLegacyImRowForRawContact(context, rawId)
        return deletedPhone
    }

    private fun getWalletDestinationFromPhones(context: Context, idColumn: String, idValue: String): String? {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL
        )
        val selection = "${ContactsContract.Data.MIMETYPE}=? AND $idColumn=?"
        val selectionArgs = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            idValue
        )

        val candidates = ArrayList<Pair<String, Boolean>>() // value to preferred
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val value = cursor.getString(0)?.trim().orEmpty()
                if (value.isBlank() || value.equals(WALLET_PLACEHOLDER, ignoreCase = true)) continue

                val type = cursor.getInt(1)
                val label = cursor.getString(2).orEmpty()
                val preferred = type == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM &&
                    label.equals(WALLET_LABEL, ignoreCase = true)

                if (preferred || looksLikeWalletDestination(value)) {
                    candidates.add(value to preferred)
                }
            }
        }

        return candidates.firstOrNull { it.second }?.first ?: candidates.firstOrNull()?.first
    }

    private fun looksLikeWalletDestination(value: String): Boolean {
        val v = value.trim()
        if (v.isBlank()) return false

        // Highly distinctive prefixes. Avoid guessing legacy base58 (1/3) here.
        return v.startsWith("bitcoin:", ignoreCase = true) ||
            v.startsWith("ln", ignoreCase = true) || // bolt11 / lnurl
            v.startsWith("bc1", ignoreCase = true) ||
            v.startsWith("tb1", ignoreCase = true) ||
            v.startsWith("bcrt1", ignoreCase = true)
    }

    private fun hasWalletPhoneRow(context: Context, rawId: Long): Boolean {
        val resolver = context.contentResolver
        val (selection, selectionArgs) = buildWalletPhoneSelection(rawId)
        return resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            cursor.moveToFirst()
        } ?: false
    }

    private fun upsertWalletPhoneForRawContact(context: Context, rawId: Long, destination: String): Boolean {
        val resolver = context.contentResolver
        val (selection, selectionArgs) = buildWalletPhoneSelection(rawId)

        val values = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.CommonDataKinds.Phone.NUMBER, destination.trim())
            put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM)
            put(ContactsContract.CommonDataKinds.Phone.LABEL, WALLET_LABEL)
        }

        val updated = resolver.update(
            ContactsContract.Data.CONTENT_URI,
            values,
            selection,
            selectionArgs
        )
        return if (updated > 0) {
            true
        } else {
            resolver.insert(ContactsContract.Data.CONTENT_URI, values) != null
        }
    }

    private fun deleteWalletPhoneForRawContact(context: Context, rawId: Long): Boolean {
        val resolver = context.contentResolver
        val (selection, selectionArgs) = buildWalletPhoneSelection(rawId)
        return resolver.delete(ContactsContract.Data.CONTENT_URI, selection, selectionArgs) > 0
    }

    private fun buildWalletPhoneSelection(rawId: Long): Pair<String, Array<String>> {
        val selection =
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Phone.TYPE}=? AND ${ContactsContract.CommonDataKinds.Phone.LABEL}=?"
        val selectionArgs = arrayOf(
            rawId.toString(),
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM.toString(),
            WALLET_LABEL
        )
        return selection to selectionArgs
    }

    private fun getLegacyImDestinationForRawContact(context: Context, rawId: Long): String? {
        val (selection, selectionArgs) = buildLegacyImSelection(
            idColumn = ContactsContract.Data.RAW_CONTACT_ID,
            idValue = rawId.toString()
        )
        return context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Im.DATA),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.trim()
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
            WALLET_LABEL
        )
        return selection to selectionArgs
    }
}
