package org.fossify.mesh

import android.content.Context
import org.fossify.commons.models.contacts.Contact

fun Contact.getMeshAddress(context: Context): String? {
    return MeshContactHelper.getMeshAddress(context, contactId.toLong())
}
