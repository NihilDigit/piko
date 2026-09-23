package dev.piko.shared.data

/**
 * 排序的字段，各带升降两个方向。视图只列字段，方向由再点一次同一字段切换。
 *
 * 默认方向按字段各取最常用的：时间与大小看最新、最大的，名称从 A 开始。
 */
enum class PikoSortField(
    val label: String,
    val ascending: PikoFileSortOrder,
    val descending: PikoFileSortOrder,
    val defaultOrder: PikoFileSortOrder,
) {
    TIME("创建时间", PikoFileSortOrder.TIME_ASC, PikoFileSortOrder.TIME_DESC, PikoFileSortOrder.TIME_DESC),
    NAME("名称", PikoFileSortOrder.NAME_ASC, PikoFileSortOrder.NAME_DESC, PikoFileSortOrder.NAME_ASC),
    SIZE("大小", PikoFileSortOrder.SIZE_ASC, PikoFileSortOrder.SIZE_DESC, PikoFileSortOrder.SIZE_DESC),
    ;

    fun owns(order: PikoFileSortOrder) = order == ascending || order == descending

    /** 选中这个字段后的排序：换字段取默认方向，点当前字段则翻转。 */
    fun selectFrom(current: PikoFileSortOrder): PikoFileSortOrder = when {
        !owns(current) -> defaultOrder
        current == ascending -> descending
        else -> ascending
    }
}

val PikoFileSortOrder.field: PikoSortField get() = PikoSortField.entries.first { it.owns(this) }
val PikoFileSortOrder.isAscending: Boolean get() = this == field.ascending
