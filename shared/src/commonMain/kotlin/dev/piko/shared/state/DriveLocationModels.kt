package dev.piko.shared.state



import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.search.cjkMatches
import dev.piko.shared.search.normalizeCjk
import io.github.nihildigit.pikpak.FileStat
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json


class FolderMapNode(val crumb: PikoPathBreadcrumb, val isArchive: Boolean, val file: FileStat? = null) {
    val expandable: Boolean get() = file == null
}

/** 目录图里一个节点下的一层，见 [DriveScreenState.folderMapLevel]。 */
sealed interface FolderMapLevel {
    class Loaded(val nodes: List<FolderMapNode>) : FolderMapLevel

    /** 加密的压缩包，存过的密码都不对。 */
    data object NeedsPassword : FolderMapLevel

    class Failed(val message: String) : FolderMapLevel
}

/**
 * 地址栏补全的结果。[matches] 是完整路径栈（含根），末项是匹配 [partial] 的文件夹。
 * [parentFound] 为 false 时输入的上级不存在，这时的输入多半不是路径。
 */
class AddressCompletion(
    val partial: String,
    val parentFound: Boolean,
    val matches: List<List<PikoPathBreadcrumb>>,
)

internal const val ADDRESS_COMPLETION_LIMIT = 50

/**
 * 地址栏的输入换成从根起的文件夹名。分隔符 / 与 \ 都认；以 .. 或 . 开头的相对当前位置，
 * 其余一律从根起，开头的 / 与「网盘」可写可不写。.. 可以叠用、也可以写在中间，退过根就停在根。
 */
fun addressPathNames(text: String, current: List<PikoPathBreadcrumb>): List<String> {
    val trimmed = text.trimStart()
    val hasLeadingSlash = trimmed.startsWith('/') || trimmed.startsWith('\\')
    val segments = text.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }
    val relative = !hasLeadingSlash && segments.firstOrNull().let { it == ".." || it == "." }
    val names = if (relative) current.drop(1).mapTo(ArrayList()) { it.name } else ArrayList()
    segments.forEachIndexed { index, segment ->
        when {
            segment == ".." -> names.removeLastOrNull()
            segment == "." -> Unit
            index == 0 && !relative && !hasLeadingSlash &&
                (segment == PikoDriveRepository.ROOT_BREADCRUMB.name || ROOT_PATH_ALIASES.any { it.equals(segment, ignoreCase = true) }) -> Unit
            else -> names += segment
        }
    }
    return names
}

private val ROOT_PATH_ALIASES = setOf("网盘", "雲端硬碟", "我的雲端硬碟", "網盤", "我的網盤", "My drive", "マイドライブ", "내 드라이브")

/** 名字含 [partial] 的（支持 CJK 归一与模糊匹配），开头相同的在前，各自保持原来的顺序；[partial] 为空时全部。 */
internal fun rankByName(folders: List<PikoPathBreadcrumb>, partial: String): List<PikoPathBreadcrumb> {
    if (partial.isEmpty()) return folders
    val normPartial = normalizeCjk(partial)
    val (prefixed, rest) = folders.filter { cjkMatches(it.name, partial) }
        .partition { normalizeCjk(it.name).startsWith(normPartial) }
    return prefixed + rest
}

internal const val MAX_FOLDER_SORT_ENTRIES = 500
private val folderSortJson = Json { ignoreUnknownKeys = true }
private val folderSortMapSerializer = MapSerializer(String.serializer(), String.serializer())

internal fun encodeFolderSortOrders(orders: Map<String, PikoFileSortOrder>): String =
    if (orders.isEmpty()) ""
    else folderSortJson.encodeToString(folderSortMapSerializer, orders.mapValues { it.value.name })

internal fun decodeFolderSortOrders(serialized: String): Map<String, PikoFileSortOrder> {
    if (serialized.isBlank()) return emptyMap()
    val raw = runCatching { folderSortJson.decodeFromString(folderSortMapSerializer, serialized) }.getOrDefault(emptyMap())
    return buildMap {
        for ((folderId, name) in raw) {
            val order = PikoFileSortOrder.entries.firstOrNull { it.name == name } ?: continue
            put(folderId, order)
        }
    }
}
