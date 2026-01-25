package org.fossify.messages.helpers

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.models.Attachment
import org.fossify.messages.models.MessageAttachment

class Converters {
    private val gson = Gson()
    private val attachmentType = object : TypeToken<List<Attachment>>() {}.type
    private val simpleContactType = object : TypeToken<List<SimpleContact>>() {}.type
    private val messageAttachmentType = object : TypeToken<MessageAttachment?>() {}.type

    @TypeConverter
    fun jsonToAttachmentList(value: String?): ArrayList<Attachment>? {
        return gson.fromJson<ArrayList<Attachment>>(value, attachmentType)
    }

    @TypeConverter
    fun attachmentListToJson(list: ArrayList<Attachment>) = gson.toJson(list)

    @TypeConverter
    fun jsonToSimpleContactList(value: String?): ArrayList<SimpleContact>? {
        val contacts = gson.fromJson<ArrayList<SimpleContact>>(value, simpleContactType) ?: return null
        contacts.forEach { contact ->
            contact.phoneNumbers = sanitizePhoneNumbers(contact.phoneNumbers)
        }
        return contacts
    }

    @TypeConverter
    fun simpleContactListToJson(list: ArrayList<SimpleContact>) = gson.toJson(list)

    @TypeConverter
    fun jsonToMessageAttachment(value: String): MessageAttachment? {
        return gson.fromJson<MessageAttachment>(value, messageAttachmentType)
    }

    @TypeConverter
    fun messageAttachmentToJson(messageAttachment: MessageAttachment?): String? {
        return gson.toJson(messageAttachment)
    }

    private fun sanitizePhoneNumbers(phoneNumbers: ArrayList<PhoneNumber>): ArrayList<PhoneNumber> {
        if (phoneNumbers.isEmpty()) {
            return phoneNumbers
        }

        val rawList = phoneNumbers as List<*>
        var needsRebuild = false
        val rebuilt = ArrayList<PhoneNumber>(rawList.size)
        for (raw in rawList) {
            when (raw) {
                is PhoneNumber -> rebuilt.add(raw)
                is Map<*, *> -> {
                    val parsed = phoneNumberFromMap(raw)
                    if (parsed != null) {
                        rebuilt.add(parsed)
                    }
                    needsRebuild = true
                }
                is String -> {
                    rebuilt.add(PhoneNumber(raw, 0, "", raw))
                    needsRebuild = true
                }
                else -> {
                    needsRebuild = true
                }
            }
        }

        return if (needsRebuild) rebuilt else phoneNumbers
    }

    private fun phoneNumberFromMap(raw: Map<*, *>): PhoneNumber? {
        var value = (raw["value"]
            ?: raw["number"]
            ?: raw["normalizedNumber"]
            ?: raw["normalized_number"]
            ?: raw["normalized"]
            ?: raw["phoneNumber"]) as? String
        if (value.isNullOrBlank()) {
            value = raw.values.filterIsInstance<String>().firstOrNull { it.any(Char::isDigit) }
        }
        if (value.isNullOrBlank()) {
            return null
        }

        val normalized = (raw["normalizedNumber"]
            ?: raw["normalized_number"]
            ?: raw["normalized"]
            ?: value) as? String ?: value
        val type = (raw["type"] as? Number)?.toInt() ?: 0
        val label = raw["label"] as? String ?: ""
        val primary = raw["primary"] as? Boolean ?: raw["isPrimary"] as? Boolean ?: false
        return PhoneNumber(value, type, label, normalized, primary)
    }
}
