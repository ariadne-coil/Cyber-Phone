package org.fossify.mesh

enum class MeshMode(val id: Int) {
    STANDARD_ONLY(0),
    MESH_WITH_FALLBACK(1),
    MESH_ONLY(2);

    companion object {
        fun fromId(id: Int): MeshMode {
            return entries.firstOrNull { it.id == id } ?: STANDARD_ONLY
        }
    }
}
