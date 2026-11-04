@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.auth

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Security.*

/**
 * iOS generic-password items, scoped by [service] and the caller's key.
 *
 * Items stay on this device, are excluded from iCloud synchronization, and remain available for
 * background transfers after the first unlock following reboot. Before that unlock, access errors
 * propagate; there is deliberately no plaintext fallback. Call from an IO dispatcher.
 */
class IosKeychainVault internal constructor(
    private val service: String,
    private val api: KeychainApi,
) : SecretVault {
    constructor(service: String) : this(service, SystemKeychainApi)
    init {
        require(service.isNotBlank() && '\u0000' !in service) { "Invalid Keychain service" }
    }

    override fun read(key: String): ByteArray? = withQuery(key) { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            result.value = null
            val status = api.copyMatching(query, result.ptr)
            val value = result.value
            try {
                when (status) {
                    errSecItemNotFound -> null
                    errSecSuccess -> {
                        if (value == null || CFGetTypeID(value) != CFDataGetTypeID()) {
                            throw VaultUnavailableException("Keychain returned an unexpected value")
                        }
                        val data: CFDataRef = value.reinterpret()
                        val size = CFDataGetLength(data)
                        if (size < 0 || size > Int.MAX_VALUE) {
                            throw VaultUnavailableException("Keychain value is too large")
                        }
                        if (size == 0L) byteArrayOf() else {
                            val bytes = CFDataGetBytePtr(data)
                                ?: throw VaultUnavailableException("Keychain returned no bytes")
                            bytes.reinterpret<ByteVar>().readBytes(size.toInt())
                        }
                    }
                    else -> fail("read", status)
                }
            } finally { if (value != null) CFRelease(value) }
        }
    }

    override fun write(key: String, data: ByteArray) {
        withQuery(key) { query ->
            // Allocate at least one byte so addressOf(0) is valid even for an empty secret.
            val storage = if (data.isEmpty()) byteArrayOf(0) else data
            storage.usePinned { pinned ->
                val value = CFDataCreate(null, pinned.addressOf(0).reinterpret(), data.size.toLong())
                    ?: throw VaultUnavailableException("Creating Keychain value failed")
                try {
                    withDictionary { attributes ->
                        CFDictionarySetValue(attributes, kSecValueData, value)
                        CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                        var status = api.update(query, attributes)
                        if (status == errSecItemNotFound) {
                            CFDictionarySetValue(query, kSecValueData, value)
                            CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                            status = api.add(query)
                            if (status == errSecDuplicateItem) {
                                // Another instance inserted the item between update and add.
                                CFDictionaryRemoveValue(query, kSecValueData)
                                CFDictionaryRemoveValue(query, kSecAttrAccessible)
                                status = api.update(query, attributes)
                            }
                        }
                        if (status != errSecSuccess) fail("write", status)
                    }
                } finally { CFRelease(value) }
            }
        }
    }

    override fun delete(key: String) {
        withQuery(key) { query ->
            val status = api.delete(query)
            if (status != errSecSuccess && status != errSecItemNotFound) fail("delete", status)
        }
    }

    private fun <T> withQuery(key: String, block: (CFMutableDictionaryRef) -> T): T {
        require(0.toChar() !in key) { "Invalid Keychain key" }
        val serviceValue = CFStringCreateWithCString(null, service, kCFStringEncodingUTF8)
            ?: throw VaultUnavailableException("Creating Keychain service failed")
        try {
            val accountValue = CFStringCreateWithCString(null, key, kCFStringEncodingUTF8)
                ?: throw VaultUnavailableException("Creating Keychain key failed")
            try {
                return withDictionary { query ->
                    CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
                    CFDictionarySetValue(query, kSecAttrService, serviceValue)
                    CFDictionarySetValue(query, kSecAttrAccount, accountValue)
                    CFDictionarySetValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
                    block(query)
                }
            } finally { CFRelease(accountValue) }
        } finally { CFRelease(serviceValue) }
    }

    private fun <T> withDictionary(block: (CFMutableDictionaryRef) -> T): T {
        val dictionary = CFDictionaryCreateMutable(
            null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: throw VaultUnavailableException("Creating Keychain query failed")
        return try { block(dictionary) } finally { CFRelease(dictionary) }
    }

    // Never include keys, service names, or secret bytes in diagnostics.
    private fun fail(operation: String, status: Int): Nothing =
        throw VaultUnavailableException("Keychain $operation failed: OSStatus=$status")
}

/** Injectable system boundary so Native regression tests do not require Keychain entitlements. */
internal interface KeychainApi {
    fun copyMatching(query: CFDictionaryRef, result: CPointer<CFTypeRefVar>): Int
    fun update(query: CFDictionaryRef, attributes: CFDictionaryRef): Int
    fun add(query: CFDictionaryRef): Int
    fun delete(query: CFDictionaryRef): Int
}

private object SystemKeychainApi : KeychainApi {
    override fun copyMatching(query: CFDictionaryRef, result: CPointer<CFTypeRefVar>) =
        SecItemCopyMatching(query, result)
    override fun update(query: CFDictionaryRef, attributes: CFDictionaryRef) = SecItemUpdate(query, attributes)
    override fun add(query: CFDictionaryRef) = SecItemAdd(query, null)
    override fun delete(query: CFDictionaryRef) = SecItemDelete(query)
}
