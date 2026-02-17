package org.fossify.phone.wallet

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract

object WalletContactHelper {
    // We store wallet destinations as custom Phone rows so they are editable in stock contact
    // editors and appear directly under phone numbers (similar to Mesh).
    private const val WALLET_ONCHAIN_LABEL = "Bitcoin"
    private const val WALLET_LIGHTNING_LABEL = "Lightning"
    private const val WALLET_ONCHAIN_PLACEHOLDER = "bitcoin:"
    private const val WALLET_LIGHTNING_PLACEHOLDER = "lightning:"

    // Legacy storage (kept for backward compatibility / migration).
    private const val LEGACY_WALLET_PROTOCOL = "bitcoin"

    data class WalletDestinations(
        val onchain: String? = null,
        val lightning: String? = null,
    ) {
        fun preferred(): String? = lightning ?: onchain
        fun isEmpty(): Boolean = onchain.isNullOrBlank() && lightning.isNullOrBlank()
    }

    fun getWalletDestination(context: Context, rawId: Long?, contactId: Int): String? {
        return getWalletDestinations(context, rawId, contactId).preferred()
    }

    fun getWalletDestinations(context: Context, rawId: Long?, contactId: Int): WalletDestinations {
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
            return WalletDestinations()
        }

        // 1) Prefer custom Phone rows, as they are user-editable in stock contact editors.
        val fromPhones = getWalletDestinationsFromPhones(context, idColumn, idValue)

        // 2) Legacy fallback: custom IM row (mapped to on-chain field).
        val (selection, selectionArgs) = buildLegacyImSelection(idColumn, idValue)
        val legacyOnchain = resolver.query(
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
            ?.takeIf { !it.equals(WALLET_ONCHAIN_PLACEHOLDER, ignoreCase = true) }

        return WalletDestinations(
            onchain = fromPhones.onchain ?: legacyOnchain,
            lightning = fromPhones.lightning,
        )
    }

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

        // Remove legacy IM entry to avoid confusing duplicate fields in editors.
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

    fun ensureWalletDestinationRowForRawContact(context: Context, rawId: Long) {
        ensureWalletDestinationRowsForRawContact(context, rawId)
    }

    fun ensureWalletDestinationRowsForRawContact(context: Context, rawId: Long) {
        if (rawId <= 0L) return

        if (!hasWalletPhoneRow(context, rawId, WALLET_ONCHAIN_LABEL)) {
            // If legacy data exists, migrate it so users can edit it in the Phone section.
            val legacy = getLegacyImDestinationForRawContact(context, rawId)
            if (!legacy.isNullOrBlank() && !legacy.equals(WALLET_ONCHAIN_PLACEHOLDER, ignoreCase = true)) {
                upsertWalletPhoneForRawContact(
                    context = context,
                    rawId = rawId,
                    destination = legacy.trim(),
                    label = WALLET_ONCHAIN_LABEL
                )
                deleteLegacyImRowForRawContact(context, rawId)
            } else {
                // Insert a placeholder row so the field becomes visible in most contact editors.
                upsertWalletPhoneForRawContact(
                    context = context,
                    rawId = rawId,
                    destination = WALLET_ONCHAIN_PLACEHOLDER,
                    label = WALLET_ONCHAIN_LABEL
                )
            }
        }

        if (!hasWalletPhoneRow(context, rawId, WALLET_LIGHTNING_LABEL)) {
            upsertWalletPhoneForRawContact(
                context = context,
                rawId = rawId,
                destination = WALLET_LIGHTNING_PLACEHOLDER,
                label = WALLET_LIGHTNING_LABEL
            )
        }

        dedupeWalletPhoneRowsForRawContact(context, rawId, WALLET_ONCHAIN_LABEL, preferredValue = null)
        dedupeWalletPhoneRowsForRawContact(context, rawId, WALLET_LIGHTNING_LABEL, preferredValue = null)
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

    fun deleteWalletDestination(context: Context, rawId: Long): Boolean {
        val deletedOnchain = deleteWalletOnchainDestination(context, rawId)
        val deletedLightning = deleteWalletLightningDestination(context, rawId)
        deleteLegacyImRowForRawContact(context, rawId)
        return deletedOnchain || deletedLightning
    }

    fun deleteWalletOnchainDestination(context: Context, rawId: Long): Boolean {
        return deleteWalletPhoneForRawContact(context, rawId, WALLET_ONCHAIN_LABEL)
    }

    fun deleteWalletLightningDestination(context: Context, rawId: Long): Boolean {
        return deleteWalletPhoneForRawContact(context, rawId, WALLET_LIGHTNING_LABEL)
    }

    private fun getWalletDestinationsFromPhones(context: Context, idColumn: String, idValue: String): WalletDestinations {
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

        var onchainLabeled: String? = null
        var lightningLabeled: String? = null
        var onchainHeuristic: String? = null
        var lightningHeuristic: String? = null

        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val value = cursor.getString(0)?.trim().orEmpty()
                if (value.isBlank()) continue

                val type = cursor.getInt(1)
                val label = cursor.getString(2).orEmpty()
                val isCustom = type == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM
                val isOnchainLabeled = isCustom && label.equals(WALLET_ONCHAIN_LABEL, ignoreCase = true)
                val isLightningLabeled = isCustom && label.equals(WALLET_LIGHTNING_LABEL, ignoreCase = true)
                val isOnchainPlaceholder = value.equals(WALLET_ONCHAIN_PLACEHOLDER, ignoreCase = true)
                val isLightningPlaceholder = value.equals(WALLET_LIGHTNING_PLACEHOLDER, ignoreCase = true)

                if (isOnchainLabeled && !isOnchainPlaceholder && onchainLabeled == null) {
                    onchainLabeled = value
                } else if (isLightningLabeled && !isLightningPlaceholder && lightningLabeled == null) {
                    lightningLabeled = value
                } else if (looksLikeLightningDestination(value) && !isLightningPlaceholder && lightningHeuristic == null) {
                    lightningHeuristic = value
                } else if (looksLikeOnchainDestination(value) && !isOnchainPlaceholder && onchainHeuristic == null) {
                    onchainHeuristic = value
                }
            }
        }

        return WalletDestinations(
            onchain = onchainLabeled ?: onchainHeuristic,
            lightning = lightningLabeled ?: lightningHeuristic,
        )
    }

    private fun looksLikeOnchainDestination(value: String): Boolean {
        val v = value.trim()
        if (v.isBlank()) return false

        return v.startsWith("bitcoin:", ignoreCase = true) ||
            v.startsWith("bc1", ignoreCase = true) ||
            v.startsWith("tb1", ignoreCase = true) ||
            v.startsWith("bcrt1", ignoreCase = true)
    }

    private fun looksLikeLightningDestination(value: String): Boolean {
        val v = value.trim()
        if (v.isBlank()) return false
        if (v.startsWith("lightning:", ignoreCase = true)) return true
        if (v.startsWith("ln", ignoreCase = true)) return true // BOLT11 / LNURL
        // Lightning Address (name@domain)
        if (v.contains('@') && !v.contains(' ')) return true
        return false
    }

    private fun hasWalletPhoneRow(context: Context, rawId: Long, label: String): Boolean {
        val resolver = context.contentResolver
        val (selection, selectionArgs) = buildWalletPhoneSelection(rawId, label)
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
            selectionArgs
        )
        val success = if (updated > 0) {
            true
        } else {
            resolver.insert(ContactsContract.Data.CONTENT_URI, values) != null
        }
        dedupeWalletPhoneRowsForRawContact(context, rawId, label, destination.trim())
        return success
    }

    private fun deleteWalletPhoneForRawContact(context: Context, rawId: Long, label: String): Boolean {
        val resolver = context.contentResolver
        val (selection, selectionArgs) = buildWalletPhoneSelection(rawId, label)
        return resolver.delete(ContactsContract.Data.CONTENT_URI, selection, selectionArgs) > 0
    }

    private fun buildWalletPhoneSelection(rawId: Long, label: String): Pair<String, Array<String>> {
        val selection =
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Phone.TYPE}=? AND ${ContactsContract.CommonDataKinds.Phone.LABEL}=?"
        val selectionArgs = arrayOf(
            rawId.toString(),
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM.toString(),
            label
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
            WALLET_ONCHAIN_LABEL
        )
        return selection to selectionArgs
    }
}
