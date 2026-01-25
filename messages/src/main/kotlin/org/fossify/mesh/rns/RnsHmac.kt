package org.fossify.mesh.rns

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object RnsHmac {
    fun sha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
