package dev.piko.desktop.update

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_CHAR
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.charset.StandardCharsets.UTF_16LE

/**
 * 查 Windows Installer 的登记，判断应用目录是不是由我们的 MSI 装的。便携版 zip 解出来的目录
 * 不在登记里，交给 msiexec 会另装一份到 LocalAppData，而不是更新用户正在用的那份。
 *
 * 按 UpgradeCode 找产品，再比 InstallLocation；UpgradeCode 由构建经系统属性传进来，与 MSI 同源。
 */
internal object WindowsInstaller {
    private const val ERROR_SUCCESS = 0
    private const val ERROR_MORE_DATA = 234
    private const val GUID_CHARS = 39

    fun isInstalledAt(upgradeCode: String, installDir: File): Boolean = runCatching {
        val expected = installDir.canonicalPath.trimEnd('\\')
        installLocations(upgradeCode).any { it.trimEnd('\\').equals(expected, ignoreCase = true) }
    }.getOrDefault(false)

    private fun installLocations(upgradeCode: String): List<String> = Arena.ofConfined().use { arena ->
        val linker = Linker.nativeLinker()
        val msi = SymbolLookup.libraryLookup("msi", arena)
        // UINT MsiEnumRelatedProductsW(LPCWSTR lpUpgradeCode, DWORD dwReserved, DWORD iProductIndex, LPWSTR lpProductBuf)
        val enumRelated = linker.downcallHandle(
            msi.find("MsiEnumRelatedProductsW").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS),
        )
        // UINT MsiGetProductInfoW(LPCWSTR szProduct, LPCWSTR szAttribute, LPWSTR lpValueBuf, LPDWORD pcchValueBuf)
        val productInfo = linker.downcallHandle(
            msi.find("MsiGetProductInfoW").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
        )
        val code = arena.allocateFrom("{${upgradeCode.trim('{', '}').uppercase()}}", UTF_16LE)
        val property = arena.allocateFrom("InstallLocation", UTF_16LE)
        val product = arena.allocate(JAVA_CHAR, GUID_CHARS.toLong())
        buildList {
            var index = 0
            while (enumRelated.invokeWithArguments(code, 0, index, product) as Int == ERROR_SUCCESS) {
                var capacity = 512
                while (true) {
                    val buffer = arena.allocate(JAVA_CHAR, capacity.toLong())
                    val length = arena.allocateFrom(JAVA_INT, capacity)
                    when (productInfo.invokeWithArguments(product, property, buffer, length) as Int) {
                        ERROR_SUCCESS -> add(buffer.getString(0, UTF_16LE))
                        // 返回的长度不含结尾的 0
                        ERROR_MORE_DATA -> {
                            capacity = length.get(JAVA_INT, 0) + 1
                            continue
                        }
                    }
                    break
                }
                index++
            }
        }
    }
}
