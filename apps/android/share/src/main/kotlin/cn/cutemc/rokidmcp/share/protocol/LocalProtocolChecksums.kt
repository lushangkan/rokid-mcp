package cn.cutemc.rokidmcp.share.protocol

import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32

object LocalProtocolChecksums {
    fun crc32(bytes: ByteArray): String {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value.toString(16).padStart(8, '0')
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).toHexString()
    }

    fun sha256(file: File): String = sha256(file.readBytes())
}

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
    byte.toUByte().toString(16).padStart(2, '0')
}
