package com.hxwz2next.app

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * MD5 工具：与 md5s.json 清单配套的哈希计算。
 */
object FileHasher {

    /** 计算字节数组的 MD5 十六进制字符串。 */
    fun md5Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        return digest.toHex()
    }

    /** 计算文件的 MD5 十六进制字符串。 */
    fun md5File(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
