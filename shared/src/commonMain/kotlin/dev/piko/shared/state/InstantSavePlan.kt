package dev.piko.shared.state

import dev.piko.shared.data.InstantFileItem

/** 保存走哪条路。 */
enum class SaveRoute {
    /** 秒传：按大小的 15% 扣月度上传额度，约一秒落盘。 */
    INSTANT,

    /** 整包离线：按整包大小扣月度离线额度，下完后删掉未选的文件。 */
    OFFLINE_PACK,
}

/** 空间不够整包离线时，改用秒传只存选中文件的代价。未收录的文件没有 gcid，秒传不了。 */
data class InstantFallback(val fileCount: Int, val uploadCostBytes: Long, val skippedCount: Int)

/**
 * 一次保存的路线与代价，给保存栏的文案用。
 *
 * [fileCount] 是最终会存下的文件数，含随视频打包的字幕。[uploadCostBytes] 只对秒传有意义，
 * [packBytes] 与 [prunedCount] 只对整包离线有意义。
 */
data class SavePlan(
    val route: SaveRoute,
    val fileCount: Int,
    val uploadCostBytes: Long = 0,
    val packBytes: Long = 0,
    val prunedCount: Int = 0,
    /** 整包放不进网盘的剩余空间。余量未知时为 false，不拦。 */
    val lacksSpace: Boolean = false,
    val fallback: InstantFallback? = null,
)

/**
 * 按勾选定路线：只选了一项（一个视频连同它打包的字幕）且都已收录就秒传，其余整包离线。
 *
 * 为什么不是「全部可秒传就秒传」：秒传按大小的 15% 扣上传额度，内容已在账号里也照扣；
 * 离线按全额扣离线额度，而会员每月离线 40 TiB、上传 1 TiB，同样的内容秒传贵约六倍。
 * 秒传赢在一两个文件时快，整季整包时离线更划算，下完再删掉没选的即可。
 *
 * [remainingBytes] 是网盘剩余空间，null 表示未知。离线要先把整包落进网盘才能删，
 * 所以比的是整包大小，不是选中的大小。
 */
fun planSave(
    items: List<InstantFileItem>,
    selected: Set<Int>,
    selectedEntryCount: Int,
    remainingBytes: Long?,
): SavePlan? {
    val chosen = selected.sorted().mapNotNull(items::getOrNull)
    if (chosen.isEmpty()) return null
    if (selectedEntryCount == 1 && chosen.all { it.isInstantReady }) {
        return SavePlan(
            route = SaveRoute.INSTANT,
            fileCount = chosen.size,
            uploadCostBytes = uploadCharge(chosen.sumOf { it.file.size }),
        )
    }
    val packBytes = items.sumOf { it.file.size }
    val lacksSpace = remainingBytes != null && packBytes > remainingBytes
    val ready = chosen.filter { it.isInstantReady }
    return SavePlan(
        route = SaveRoute.OFFLINE_PACK,
        fileCount = chosen.size,
        packBytes = packBytes,
        prunedCount = items.size - chosen.size,
        lacksSpace = lacksSpace,
        fallback = if (lacksSpace && ready.isNotEmpty()) {
            InstantFallback(
                fileCount = ready.size,
                uploadCostBytes = uploadCharge(ready.sumOf { it.file.size }),
                skippedCount = chosen.size - ready.size,
            )
        } else {
            null
        },
    )
}

/** 秒传扣的上传额度：大小的 15%，向上取整。2026-09-24 实测，见 SDK 的 DESIGN-NOTES。 */
fun uploadCharge(bytes: Long): Long = (bytes * 3 + 19) / 20
