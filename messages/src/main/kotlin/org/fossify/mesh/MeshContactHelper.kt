package org.fossify.mesh

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact

object MeshContactHelper {
    fun getMeshAddress(context: Context, contactId: Long): String? {
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

    fun upsertMeshAddressForRawContact(
        context: Context,
        rawContactId: Long,
        meshAddress: String
    ) {
        val selection = "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(rawContactId.toString(), MESH_CONTACT_MIME)
        val values = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, MESH_CONTACT_MIME)
            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            put(ContactsContract.Data.DATA1, meshAddress)
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
        val selection = "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(rawContactId.toString(), MESH_CONTACT_MIME)
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
        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DATA1} = ?"
        val selectionArgs = arrayOf(MESH_CONTACT_MIME, meshAddress)
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
        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DATA1} = ?"
        val selectionArgs = arrayOf(MESH_CONTACT_MIME, meshAddress)
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
