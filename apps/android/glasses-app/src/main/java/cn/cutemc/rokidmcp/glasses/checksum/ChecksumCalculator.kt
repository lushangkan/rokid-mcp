package cn.cutemc.rokidmcp.glasses.checksum

import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolConstants
import java.security.MessageDigest

class ChecksumCalculator {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance(LocalProtocolConstants.FILE_CHECKSUM_ALGO)
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
