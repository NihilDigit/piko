package dev.piko.desktop.secrets

import dev.piko.shared.auth.FileSecretVault
import dev.piko.shared.auth.VaultUnavailableException
import dev.piko.shared.log.PikoLog
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import java.nio.file.Path

/**
 * 以 DPAPI（CryptProtectData，当前用户范围）加密的凭据文件 `<key>.bin`，经 FFM 直调 crypt32。
 *
 * 不用凭据管理器：它的 blob 上限 2560 字节，会话里的 access token 加上 refresh token 已逼近。
 * 不带附加熵、描述串为空，与 CLI 那边经 PowerShell 的 ProtectedData 互通（PowerShellDpapiVault）。
 */
internal class DpapiSecretVault(directory: Path) : FileSecretVault(directory, "bin") {
    override fun seal(data: ByteArray): ByteArray = try {
        Dpapi.protect(data)
    } catch (e: DpapiException) {
        throw VaultUnavailableException(e.message!!, e)
    }

    override fun unseal(data: ByteArray): ByteArray? = try {
        Dpapi.unprotect(data)
    } catch (e: DpapiException) {
        // 换了机器、换了用户或重置过密码，密钥已不是加密时那一把，只能当作没有
        PikoLog.w("credentials", "DPAPI 解不开这份凭据，当作没有", e)
        null
    }
}

internal class DpapiException(message: String) : Exception(message)

internal object Dpapi {
    private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

    private val linker = Linker.nativeLinker()
    private val crypt32 by lazy { SymbolLookup.libraryLookup("crypt32", Arena.global()) }
    private val kernel32 by lazy { SymbolLookup.libraryLookup("kernel32", Arena.global()) }

    // 失败原因只能经 GetLastError 取，而 JVM 自己随时会调系统 API 把它冲掉，必须让 FFM 在调用返回的当下捕获
    private val captureLastError = Linker.Option.captureCallState("GetLastError")
    private val callStateLayout = Linker.Option.captureStateLayout()

    // BOOL f(DATA_BLOB* in, LPCWSTR description, DATA_BLOB* entropy, PVOID reserved, PROMPTSTRUCT* prompt, DWORD flags, DATA_BLOB* out)
    private val cryptDescriptor = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
    private val cryptProtectData by lazy { cryptFunction("CryptProtectData") }
    private val cryptUnprotectData by lazy { cryptFunction("CryptUnprotectData") }
    private val localFree by lazy {
        linker.downcallHandle(kernel32.find("LocalFree").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS))
    }

    // DATA_BLOB：DWORD cbData 之后按指针对齐补四个字节，再是 BYTE* pbData；只支持 64 位
    private val dataBlob = MemoryLayout.structLayout(JAVA_INT, MemoryLayout.paddingLayout(4), ADDRESS)
    private const val BLOB_SIZE_OFFSET = 0L
    private const val BLOB_DATA_OFFSET = 8L

    fun protect(data: ByteArray): ByteArray = transform(cryptProtectData, "CryptProtectData", data)

    fun unprotect(data: ByteArray): ByteArray = transform(cryptUnprotectData, "CryptUnprotectData", data)

    private fun transform(function: MethodHandle, name: String, data: ByteArray): ByteArray = Arena.ofConfined().use { arena ->
        val input = arena.allocate(dataBlob)
        input.set(JAVA_INT, BLOB_SIZE_OFFSET, data.size)
        input.set(ADDRESS, BLOB_DATA_OFFSET, arena.allocateFrom(JAVA_BYTE, *data))
        val output = arena.allocate(dataBlob)
        val callState = arena.allocate(callStateLayout)
        val ok = function.invokeWithArguments(
            callState, input, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
            CRYPTPROTECT_UI_FORBIDDEN, output,
        ) as Int
        if (ok == 0) {
            val error = callState.get(JAVA_INT, callStateLayout.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError")))
            throw DpapiException("$name 失败：错误码 0x%08X".format(error))
        }
        // 输出缓冲由 DPAPI 以 LocalAlloc 分配，拷出来之后由调用方 LocalFree
        val size = output.get(JAVA_INT, BLOB_SIZE_OFFSET).toLong()
        val buffer = output.get(ADDRESS, BLOB_DATA_OFFSET)
        try {
            buffer.reinterpret(size).toArray(JAVA_BYTE)
        } finally {
            localFree.invokeWithArguments(buffer)
        }
    }

    private fun cryptFunction(name: String): MethodHandle =
        linker.downcallHandle(crypt32.find(name).orElseThrow(), cryptDescriptor, captureLastError)
}
