package dev.piko.shared.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 网盘页上次停留的路径栈，存进偏好（[dev.piko.data.auth.PikoUserPreferences.saveLastFolder]），重启后恢复。
 *
 * 用 JSON 而不是拼接分隔符：文件夹名可以含分号，原先以「;」与「::」拼接的格式遇到这种名字，
 * 恢复时那一级的名字被截断、其后一段被丢掉，面包屑与「上一级」随之出错。
 */
object LastFolderStack {
    fun encode(stack: List<PikoPathBreadcrumb>): String =
        json.encodeToString(serializer, stack.map { LastFolderCrumb(it.id, it.name) })

    /** 内容为空或损坏时返回空表，由调用方退回只恢复最后一级。 */
    fun decode(serialized: String): List<PikoPathBreadcrumb> {
        if (serialized.isBlank()) return emptyList()
        if (!serialized.startsWith("[")) return decodeLegacy(serialized)
        return runCatching { json.decodeFromString(serializer, serialized) }.getOrDefault(emptyList())
            .map { PikoPathBreadcrumb(it.id, it.name) }
    }

    // 升级前存下的旧格式，读一次即可，下次换目录就改存 JSON。名字含分号的那一级照旧会错，
    // 但丢掉整条路径更糟：用户会回到根目录
    private fun decodeLegacy(serialized: String): List<PikoPathBreadcrumb> =
        serialized.split(";").mapNotNull { entry ->
            val parts = entry.split("::")
            if (parts.size == 2) PikoPathBreadcrumb(parts[0], parts[1]) else null
        }
}

@Serializable
private class LastFolderCrumb(val id: String, val name: String)

private val json = Json { ignoreUnknownKeys = true }
private val serializer = ListSerializer(LastFolderCrumb.serializer())
