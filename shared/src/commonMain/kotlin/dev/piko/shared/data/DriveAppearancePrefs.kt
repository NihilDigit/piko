package dev.piko.shared.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 网盘三种视图的独立外观模式。 */
enum class DriveAppearanceMode {
    LIST,
    GRID,
    GALLERY,
}

/** 文件夹在列表、网格与瀑布流中的视觉形态。 */
enum class FolderStyle {
    /** 拟物文件夹折页与叠层预览。 */
    SKEUOMORPHIC,

    /** 极简圆角封面与线性图标，减少遮挡。 */
    MINIMAL_ICON,
}

/** 缩略图在封面容器内的缩放方式。 */
enum class ThumbnailScale {
    /** 裁切填满容器（默认）。 */
    CROP,

    /** 完整显示不裁剪（两侧或上下保留中性底色）。 */
    FIT,
}

/** 缩略图清晰度档位（同时影响 PikPak 请求尺寸档位与 Coil 本地解码上限）。 */
enum class ThumbnailClarity(
    val decodeMaxPx: Int,
    val requestLarge: Boolean,
    val diskTierKey: String,
) {
    /** 省流：较小解码尺寸，节省内存与解码耗时。 */
    LOW(decodeMaxPx = 256, requestLarge = false, diskTierKey = "sd"),

    /** 标准：平衡清晰度与流量（默认）。 */
    STANDARD(decodeMaxPx = 512, requestLarge = false, diskTierKey = "sd"),

    /** 高清：优先请求 PikPak 720/2048 高清缩略图并按更高分辨率解码。 */
    HIGH(decodeMaxPx = 1024, requestLarge = true, diskTierKey = "hd"),
}

/** 网格与瀑布流封面四个角落可配置的徽标类型。 */
enum class CardBadgeKind {
    NONE,
    DURATION,
    RESOLUTION,
    PARSED_TAGS,
    FILE_SIZE,
    STATUS_BADGES,
    EXTENSION,
}

/** 卡片封面四个角落槽位。 */
enum class CornerPosition {
    TOP_START,
    TOP_END,
    BOTTOM_START,
    BOTTOM_END,
}

/**
 * 封面四角徽标槽位配置。
 * 调用 [assign] 分配非 [CardBadgeKind.NONE] 徽标时会自动清空其他角落的同名徽标，防止同一张卡片重复展示同一信息。
 */
@Immutable
data class CornerSlots(
    val topStart: CardBadgeKind = CardBadgeKind.NONE,
    val topEnd: CardBadgeKind = CardBadgeKind.NONE,
    val bottomStart: CardBadgeKind = CardBadgeKind.NONE,
    val bottomEnd: CardBadgeKind = CardBadgeKind.NONE,
) {
    operator fun get(position: CornerPosition): CardBadgeKind = when (position) {
        CornerPosition.TOP_START -> topStart
        CornerPosition.TOP_END -> topEnd
        CornerPosition.BOTTOM_START -> bottomStart
        CornerPosition.BOTTOM_END -> bottomEnd
    }

    fun assign(position: CornerPosition, badge: CardBadgeKind): CornerSlots {
        fun cleared(current: CardBadgeKind): CardBadgeKind =
            if (badge != CardBadgeKind.NONE && current == badge) CardBadgeKind.NONE else current
        return when (position) {
            CornerPosition.TOP_START -> copy(
                topStart = badge,
                topEnd = cleared(topEnd),
                bottomStart = cleared(bottomStart),
                bottomEnd = cleared(bottomEnd),
            )
            CornerPosition.TOP_END -> copy(
                topStart = cleared(topStart),
                topEnd = badge,
                bottomStart = cleared(bottomStart),
                bottomEnd = cleared(bottomEnd),
            )
            CornerPosition.BOTTOM_START -> copy(
                topStart = cleared(topStart),
                topEnd = cleared(topEnd),
                bottomStart = badge,
                bottomEnd = cleared(bottomEnd),
            )
            CornerPosition.BOTTOM_END -> copy(
                topStart = cleared(topStart),
                topEnd = cleared(topEnd),
                bottomStart = cleared(bottomStart),
                bottomEnd = badge,
            )
        }
    }
}

/** 列表行与卡片底部详情行可勾选的有序元信息字段。 */
enum class DetailField {
    SIZE,
    DURATION,
    ADDED_TIME,
    PARSED_TAGS,
    ITEM_COUNT,
    EXTENSION,
    VAULT_STATUS,
}

/** 在 [fields] 中切换 [field] 开关，保留原有顺序并在新增时追加至末尾（自动去重）。 */
fun toggleDetailField(fields: List<DetailField>, field: DetailField): List<DetailField> =
    if (field in fields) fields.filterNot { it == field } else fields.distinct() + field

@Immutable
data class ListViewStylePrefs(
    val folderStyle: FolderStyle = FolderStyle.MINIMAL_ICON,
    val showParsedTags: Boolean = true,
    val detailFields: List<DetailField> = DEFAULT_DETAIL_FIELDS,
    val showSecondaryName: Boolean = true,
    val compactDensity: Boolean = false,
) {
    companion object {
        val DEFAULT_DETAIL_FIELDS: List<DetailField> = listOf(
            DetailField.SIZE,
            DetailField.DURATION,
            DetailField.ITEM_COUNT,
            DetailField.ADDED_TIME,
        )
        val DEFAULT = ListViewStylePrefs()
    }
}

@Immutable
data class GridViewStylePrefs(
    val folderStyle: FolderStyle = FolderStyle.SKEUOMORPHIC,
    val corners: CornerSlots = DEFAULT_CORNERS,
    val showTitle: Boolean = true,
    val showDetailLine: Boolean = true,
    val detailFields: List<DetailField> = DEFAULT_DETAIL_FIELDS,
) {
    companion object {
        val DEFAULT_CORNERS = CornerSlots(
            topStart = CardBadgeKind.STATUS_BADGES,
            topEnd = CardBadgeKind.RESOLUTION,
            bottomStart = CardBadgeKind.PARSED_TAGS,
            bottomEnd = CardBadgeKind.DURATION,
        )
        val DEFAULT_DETAIL_FIELDS: List<DetailField> = listOf(
            DetailField.SIZE,
            DetailField.ITEM_COUNT,
            DetailField.ADDED_TIME,
        )
        val DEFAULT = GridViewStylePrefs()
    }
}

@Immutable
data class GalleryViewStylePrefs(
    /** 保留字段以维持旧版 JSON 与画布调用点兼容；瀑布流仅展示媒体条目，不呈现文件夹。 */
    val folderStyle: FolderStyle = FolderStyle.SKEUOMORPHIC,
    val corners: CornerSlots = DEFAULT_CORNERS,
    val showTitle: Boolean = false,
    val showDetailLine: Boolean = false,
    val detailFields: List<DetailField> = DEFAULT_DETAIL_FIELDS,
) {
    companion object {
        val DEFAULT_CORNERS = CornerSlots(
            topStart = CardBadgeKind.STATUS_BADGES,
            topEnd = CardBadgeKind.RESOLUTION,
            bottomStart = CardBadgeKind.NONE,
            bottomEnd = CardBadgeKind.DURATION,
        )
        val DEFAULT_DETAIL_FIELDS: List<DetailField> = listOf(
            DetailField.SIZE,
            DetailField.DURATION,
            DetailField.ITEM_COUNT,
        )
        val DEFAULT = GalleryViewStylePrefs()
    }
}

/**
 * 网盘三视图（列表 / 网格 / 瀑布流）文件外观自定义偏好。
 * 采用容错版本化 JSON 编解码：未知字段或损坏枚举值逐字段回退至 [DEFAULT]，不丢失其他有效项。
 */
@Immutable
data class DriveAppearancePrefs(
    val list: ListViewStylePrefs = ListViewStylePrefs.DEFAULT,
    val grid: GridViewStylePrefs = GridViewStylePrefs.DEFAULT,
    val gallery: GalleryViewStylePrefs = GalleryViewStylePrefs.DEFAULT,
    val thumbnailScale: ThumbnailScale = ThumbnailScale.CROP,
    val thumbnailClarity: ThumbnailClarity = ThumbnailClarity.STANDARD,
) {
    fun reset(mode: DriveAppearanceMode): DriveAppearancePrefs = when (mode) {
        DriveAppearanceMode.LIST -> copy(list = ListViewStylePrefs.DEFAULT)
        DriveAppearanceMode.GRID -> copy(grid = GridViewStylePrefs.DEFAULT)
        DriveAppearanceMode.GALLERY -> copy(gallery = GalleryViewStylePrefs.DEFAULT)
    }

    fun encodeJson(): String = buildJsonObject {
        put("v", SCHEMA_VERSION)
        put("thumbnailScale", thumbnailScale.name)
        put("thumbnailClarity", thumbnailClarity.name)
        put("list", buildJsonObject {
            put("folderStyle", list.folderStyle.name)
            put("showParsedTags", list.showParsedTags)
            put("detailFields", encodeFields(list.detailFields))
            put("showSecondaryName", list.showSecondaryName)
            put("compactDensity", list.compactDensity)
        })
        put("grid", buildJsonObject {
            put("folderStyle", grid.folderStyle.name)
            put("corners", encodeCorners(grid.corners))
            put("showTitle", grid.showTitle)
            put("showDetailLine", grid.showDetailLine)
            put("detailFields", encodeFields(grid.detailFields))
        })
        put("gallery", buildJsonObject {
            put("folderStyle", gallery.folderStyle.name)
            put("corners", encodeCorners(gallery.corners))
            put("showTitle", gallery.showTitle)
            put("showDetailLine", gallery.showDetailLine)
            put("detailFields", encodeFields(gallery.detailFields))
        })
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 1
        val DEFAULT = DriveAppearancePrefs()

        private val json = Json { ignoreUnknownKeys = true }

        fun decodeJson(raw: String?): DriveAppearancePrefs {
            if (raw.isNullOrBlank()) return DEFAULT
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return DEFAULT
            return DriveAppearancePrefs(
                list = decodeList(root["list"] as? JsonObject),
                grid = decodeGrid(root["grid"] as? JsonObject),
                gallery = decodeGallery(root["gallery"] as? JsonObject),
                thumbnailScale = root.enumOrDefault("thumbnailScale", DEFAULT.thumbnailScale),
                thumbnailClarity = root.enumOrDefault("thumbnailClarity", DEFAULT.thumbnailClarity),
            )
        }

        private fun encodeCorners(corners: CornerSlots): JsonObject = buildJsonObject {
            put("topStart", corners.topStart.name)
            put("topEnd", corners.topEnd.name)
            put("bottomStart", corners.bottomStart.name)
            put("bottomEnd", corners.bottomEnd.name)
        }

        private fun encodeFields(fields: List<DetailField>): JsonArray = buildJsonArray {
            fields.distinct().forEach { add(JsonPrimitive(it.name)) }
        }

        private fun decodeList(obj: JsonObject?): ListViewStylePrefs {
            if (obj == null) return ListViewStylePrefs.DEFAULT
            return ListViewStylePrefs(
                folderStyle = obj.enumOrDefault("folderStyle", ListViewStylePrefs.DEFAULT.folderStyle),
                showParsedTags = obj.boolOrDefault("showParsedTags", ListViewStylePrefs.DEFAULT.showParsedTags),
                detailFields = obj.fieldsOrDefault("detailFields", ListViewStylePrefs.DEFAULT_DETAIL_FIELDS),
                showSecondaryName = obj.boolOrDefault("showSecondaryName", ListViewStylePrefs.DEFAULT.showSecondaryName),
                compactDensity = obj.boolOrDefault("compactDensity", ListViewStylePrefs.DEFAULT.compactDensity),
            )
        }

        private fun decodeGrid(obj: JsonObject?): GridViewStylePrefs {
            if (obj == null) return GridViewStylePrefs.DEFAULT
            return GridViewStylePrefs(
                folderStyle = obj.enumOrDefault("folderStyle", GridViewStylePrefs.DEFAULT.folderStyle),
                corners = decodeCorners(obj["corners"] as? JsonObject, GridViewStylePrefs.DEFAULT_CORNERS),
                showTitle = obj.boolOrDefault("showTitle", GridViewStylePrefs.DEFAULT.showTitle),
                showDetailLine = obj.boolOrDefault("showDetailLine", GridViewStylePrefs.DEFAULT.showDetailLine),
                detailFields = obj.fieldsOrDefault("detailFields", GridViewStylePrefs.DEFAULT_DETAIL_FIELDS),
            )
        }

        private fun decodeGallery(obj: JsonObject?): GalleryViewStylePrefs {
            if (obj == null) return GalleryViewStylePrefs.DEFAULT
            return GalleryViewStylePrefs(
                folderStyle = obj.enumOrDefault("folderStyle", GalleryViewStylePrefs.DEFAULT.folderStyle),
                corners = decodeCorners(obj["corners"] as? JsonObject, GalleryViewStylePrefs.DEFAULT_CORNERS),
                showTitle = obj.boolOrDefault("showTitle", GalleryViewStylePrefs.DEFAULT.showTitle),
                showDetailLine = obj.boolOrDefault("showDetailLine", GalleryViewStylePrefs.DEFAULT.showDetailLine),
                detailFields = obj.fieldsOrDefault("detailFields", GalleryViewStylePrefs.DEFAULT_DETAIL_FIELDS),
            )
        }

        private fun decodeCorners(obj: JsonObject?, default: CornerSlots): CornerSlots {
            if (obj == null) return default
            return CornerSlots(
                topStart = obj.enumOrDefault("topStart", default.topStart),
                topEnd = obj.enumOrDefault("topEnd", default.topEnd),
                bottomStart = obj.enumOrDefault("bottomStart", default.bottomStart),
                bottomEnd = obj.enumOrDefault("bottomEnd", default.bottomEnd),
            )
        }

        private inline fun <reified E : Enum<E>> JsonObject.enumOrDefault(key: String, default: E): E {
            val raw = (this[key] as? JsonPrimitive)?.content ?: return default
            return enumValues<E>().firstOrNull { it.name == raw } ?: default
        }

        private fun JsonObject.boolOrDefault(key: String, default: Boolean): Boolean =
            (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

        private fun JsonObject.fieldsOrDefault(key: String, default: List<DetailField>): List<DetailField> {
            val arr = this[key] as? JsonArray ?: return default
            val parsed = buildList {
                for (el in arr) {
                    val name = runCatching { el.jsonPrimitive.content }.getOrNull() ?: continue
                    DetailField.entries.firstOrNull { it.name == name }?.let { if (it !in this) add(it) }
                }
            }
            return parsed
        }
    }
}
