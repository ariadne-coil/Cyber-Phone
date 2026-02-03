package org.fossify.mesh.lxmf

data class LxmfAttachmentPayload(
    val filename: String,
    val mimeType: String,
    val data: ByteArray
)

object LxmfAttachments {
    private const val KEY_NAME = "name"
    private const val KEY_TYPE = "type"
    private const val KEY_DATA = "data"

    fun encode(
        attachments: List<LxmfAttachmentPayload>,
        baseFields: Map<Int, Any?> = emptyMap()
    ): Map<Int, Any?> {
        if (attachments.isEmpty()) return baseFields
        val encoded = attachments.map { attachment ->
            mapOf(
                KEY_NAME to attachment.filename,
                KEY_TYPE to attachment.mimeType,
                KEY_DATA to attachment.data
            )
        }
        val merged = HashMap(baseFields)
        merged[LxmfConstants.FIELD_FILE_ATTACHMENTS] = encoded
        return merged
    }

    fun decode(fields: Map<Int, Any?>): List<LxmfAttachmentPayload> {
        val raw = fields[LxmfConstants.FIELD_FILE_ATTACHMENTS] ?: return emptyList()
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val name = map[KEY_NAME] as? String ?: ""
            val type = map[KEY_TYPE] as? String ?: ""
            val data = map[KEY_DATA] as? ByteArray ?: return@mapNotNull null
            if (type.isBlank()) return@mapNotNull null
            LxmfAttachmentPayload(name, type, data)
        }
    }
}
