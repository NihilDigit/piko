package dev.piko.shared.auth

import kotlinx.io.IOException

/**
 * Blocking storage for secret bytes, isolated by key. Callers select an IO dispatcher.
 *
 * Missing items, or records that can no longer be deciphered after an OS/account change, return
 * null. A temporarily inaccessible vault must throw instead of appearing empty: the caller must
 * not interpret a locked keychain as a logged-out account.
 */
interface SecretVault {
    fun read(key: String): ByteArray?
    fun write(key: String, data: ByteArray)
    fun delete(key: String)
}

class VaultUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)
