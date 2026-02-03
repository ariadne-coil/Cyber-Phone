package org.fossify.mesh.call

enum class MeshCallQuality(val id: Int, val sampleRate: Int, val bitrate: Int) {
    LOW(0, 16000, 16000),
    HIGH(1, 48000, 32000);

    companion object {
        fun fromId(id: Int): MeshCallQuality {
            return entries.firstOrNull { it.id == id } ?: LOW
        }
    }
}
