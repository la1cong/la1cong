package com.friday.wimm.util

import java.security.MessageDigest

object HashUtil {
    /** md5 十六进制（去重用：hash(时间+金额+商户)） */
    fun md5(text: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            text.hashCode().toString()
        }
    }
}
