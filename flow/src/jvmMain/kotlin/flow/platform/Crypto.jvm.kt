package flow.platform

import java.security.MessageDigest

actual fun digest(algorithm: String, data: ByteArray): ByteArray? =
    runCatching { MessageDigest.getInstance(algorithm).digest(data) }.getOrNull()
