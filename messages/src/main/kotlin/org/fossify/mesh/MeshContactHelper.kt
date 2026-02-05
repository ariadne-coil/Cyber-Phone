package org.fossify.mesh

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.mesh.lxmf.LxmfAddress

object MeshContactHelper {
    private const val MESH_PHONE_LABEL = "Mesh"
    private const val MESH_PHONE_PLACEHOLDER = "mesh:"

    fun getMeshAddress(context: Context, contactId: Long): String? {
        // Prefer a standard Phone row with our custom label, as it is user-editable in stock contact editors.
        getMeshAddressFromPhones(context, contactId)?.let { return it }

        val projection = arrayOf(ContactsContract.Data.DATA1)
        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(contactId.toString(), MESH_CONTACT_MIME)
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return null
    }

    private fun getMeshAddressFromPhones(context: Context, contactId: Long): String? {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL
        )
        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(
            contactId.toString(),
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
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
                val value = cursor.getString(0) ?: continue
                val type = cursor.getInt(1)
                val label = cursor.getString(2).orEmpty()
                val normalized = LxmfAddress.normalize(value)
                if (!LxmfAddress.isMeshAddress(normalized)) continue

                val preferred = type == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM &&
                    label.equals(MESH_PHONE_LABEL, ignoreCase = true)
                candidates.add(normalized to preferred)
            }
        }
        return candidates.firstOrNull { it.second }?.first ?: candidates.firstOrNull()?.first
    }

    fun upsertMeshAddressForRawContact(
        context: Context,
        rawContactId: Long,
        meshAddress: String
    ) {
        val normalized = LxmfAddress.normalize(meshAddress)
        upsertMeshPhoneForRawContact(context, rawContactId, normalized)

        val selection = "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(rawContactId.toString(), MESH_CONTACT_MIME)
        val values = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, MESH_CONTACT_MIME)
            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            put(ContactsContract.Data.DATA1, normalized)
        }

        val updated = context.contentResolver.update(
            ContactsContract.Data.CONTENT_URI,
            values,
            selection,
            selectionArgs
        )
        if (updated == 0) {
            context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values)
        }
    }

    private fun upsertMeshPhoneForRawContact(context: Context, rawContactId: Long, meshAddress: String) {
        val selection =
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.Data.DATA2}=? AND ${ContactsContract.Data.DATA3}=?"
        val selectionArgs = arrayOf(
            rawContactId.toString(),
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM.toString(),
            MESH_PHONE_LABEL
        )
        val values = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            put(ContactsContract.CommonDataKinds.Phone.NUMBER, meshAddress)
            put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM)
            put(ContactsContract.CommonDataKinds.Phone.LABEL, MESH_PHONE_LABEL)
        }
        val updated = context.contentResolver.update(
            ContactsContract.Data.CONTENT_URI,
            values,
            selection,
            selectionArgs
        )
        if (updated == 0) {
            context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values)
        }
    }

    fun deleteMeshAddressForRawContact(context: Context, rawContactId: Long) {
        deleteMeshPhoneForRawContact(context, rawContactId)
        val selection = "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(rawContactId.toString(), MESH_CONTACT_MIME)
        context.contentResolver.delete(
            ContactsContract.Data.CONTENT_URI,
            selection,
            selectionArgs
        )
    }

    fun addMeshPhoneInsertExtras(intent: Intent, meshAddress: String = MESH_PHONE_PLACEHOLDER) {
        val data = intent.getParcelableArrayListExtra<ContentValues>(ContactsContract.Intents.Insert.DATA)
            ?: arrayListOf()
        val values = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM)
            put(ContactsContract.CommonDataKinds.Phone.LABEL, MESH_PHONE_LABEL)
            put(ContactsContract.CommonDataKinds.Phone.NUMBER, LxmfAddress.normalize(meshAddress))
        }
        data.add(values)
        intent.putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, data)
    }

    fun ensureMeshPhoneRowForRawContact(context: Context, rawContactId: Long) {
        if (rawContactId <= 0L) return
        if (hasMeshPhoneRow(context, rawContactId)) return
        if (!insertMeshPhoneRow(context, rawContactId, MESH_PHONE_PLACEHOLDER)) {
            insertMeshPhoneRow(context, rawContactId, "mesh:")
        }
    }

    private fun hasMeshPhoneRow(context: Context, rawContactId: Long): Boolean {
        val selection =
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.Data.DATA2}=? AND ${ContactsContract.Data.DATA3}=?"
        val selectionArgs = arrayOf(
            rawContactId.toString(),
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM.toString(),
            MESH_PHONE_LABEL
        )
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            return cursor.moveToFirst()
        }
        return false
    }

    private fun insertMeshPhoneRow(context: Context, rawContactId: Long, number: String): Boolean {
        val values = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            put(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
            put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM)
            put(ContactsContract.CommonDataKinds.Phone.LABEL, MESH_PHONE_LABEL)
        }
        return context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values) != null
    }

    private fun deleteMeshPhoneForRawContact(context: Context, rawContactId: Long) {
        val selection =
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.Data.DATA2}=? AND ${ContactsContract.Data.DATA3}=?"
        val selectionArgs = arrayOf(
            rawContactId.toString(),
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM.toString(),
            MESH_PHONE_LABEL
        )
        context.contentResolver.delete(
            ContactsContract.Data.CONTENT_URI,
            selection,
            selectionArgs
        )
    }

    fun getContactNameAndPhotoForMeshAddress(context: Context, meshAddress: String): Pair<String?, String?> {
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID
        )
        val selection = "(${ContactsContract.Data.MIMETYPE} = ? OR ${ContactsContract.Data.MIMETYPE} = ?) AND ${ContactsContract.Data.DATA1} = ?"
        val selectionArgs = arrayOf(
            MESH_CONTACT_MIME,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            LxmfAddress.normalize(meshAddress)
        )
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val contactId = cursor.getLong(0)
                return getContactNameAndPhoto(context, contactId)
            }
        }
        return null to null
    }

    fun getSimpleContactForMeshAddress(context: Context, meshAddress: String): SimpleContact? {
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.RAW_CONTACT_ID
        )
        val selection = "(${ContactsContract.Data.MIMETYPE} = ? OR ${ContactsContract.Data.MIMETYPE} = ?) AND ${ContactsContract.Data.DATA1} = ?"
        val selectionArgs = arrayOf(
            MESH_CONTACT_MIME,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            LxmfAddress.normalize(meshAddress)
        )
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val contactId = cursor.getLong(0)
                val rawId = cursor.getLong(1)
                val (name, photoUri) = getContactNameAndPhoto(context, contactId)
                val phoneNumber = PhoneNumber(meshAddress, 0, "", meshAddress)
                return SimpleContact(
                    rawId = rawId.toInt(),
                    contactId = contactId.toInt(),
                    name = name ?: meshAddress,
                    photoUri = photoUri ?: "",
                    phoneNumbers = arrayListOf(phoneNumber),
                    birthdays = ArrayList(),
                    anniversaries = ArrayList()
                )
            }
        }
        return null
    }

    private fun getContactNameAndPhoto(context: Context, contactId: Long): Pair<String?, String?> {
        val contactUri: Uri = ContactsContract.Contacts.CONTENT_URI.buildUpon()
            .appendPath(contactId.toString())
            .build()
        val projection = arrayOf(
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_URI
        )
        context.contentResolver.query(
            contactUri,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                val photoUri = cursor.getString(1)
                return name to photoUri
            }
        }
        return null to null
    }
}
