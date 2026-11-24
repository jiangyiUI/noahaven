package dev.anilbeesetti.nextplayer.feature.books.data

import java.security.MessageDigest

/** 书籍路径相关的哈希工具（缓存目录、覆盖存储共用）。 */
internal object BookHashes {
    fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
