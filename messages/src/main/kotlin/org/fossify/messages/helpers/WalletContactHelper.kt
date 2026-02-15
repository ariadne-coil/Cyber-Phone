package org.fossify.messages.helpers

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract

/**
 * Contacts integration for wallet destinations.
 *
 * This lives in the :messages module as well (duplicated from :app) so the Profile/QR flows can
 * read/write wallet addresses without depending on the :app module.
 */
object WalletContactHelper {
    private const val WALLET_LABEL = "Bitcoin"
    private const val WALLET_PLACEHOLDER = "bitcoin:"

    // Legacy IM storage (kept for migration / cleanup).
    private const val LEGACY_WALLET_PROTOCOL = "bitcoin"

    fun upsertWalletDestination(context: Context, rawId: Long, destination: String): Boolean {
        val ok = upsertWalletPhoneForRawContact(context, rawId, destination.trim())
        // Clean up legacy IM entry if it exists to avoid duplicate fields in editors.
        deleteLegacyImRowForRawContact(context, rawId)
        return ok
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
            selectionArgs,
        )

        return if (updated > 0) {
            true
        } else {
            resolver.insert(ContactsContract.Data.CONTENT_URI, values) != null
        }
    }

    private fun buildWalletPhoneSelection(rawId: Long): Pair<String, Array<String>> {
        val selection =
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Phone.TYPE}=? AND ${ContactsContract.CommonDataKinds.Phone.LABEL}=?"
        val selectionArgs = arrayOf(
            rawId.toString(),
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM.toString(),
            WALLET_LABEL,
        )
        return selection to selectionArgs
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
