package org.fossify.mesh

import android.content.Context
import org.fossify.commons.models.SimpleContact

fun SimpleContact.getMeshAddress(context: Context): String? {
    return MeshContactHelper.getMeshAddress(context, contactId.toLong())
}
