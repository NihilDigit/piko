package dev.piko.shared.sync

import java.security.SecureRandom

private val secureRandom = SecureRandom()

internal actual fun secureRandomBytes(count: Int): ByteArray =
    ByteArray(count).also { if (count > 0) secureRandom.nextBytes(it) }
