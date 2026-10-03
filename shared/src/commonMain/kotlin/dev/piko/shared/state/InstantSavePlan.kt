package dev.piko.shared.state

import dev.piko.shared.data.InstantFileItem

/** 保存走哪条路。 */
enum class SaveRoute {
    /** 秒传：按大小的 15% 扣月度上传额度，约一秒落盘。 */
    INSTANT,

    /** 整包离线：按整包大小扣月度离线额度，下完后删掉未选的文件。 */
    OFFLINE_PACK,
}

/**
 * 整包离线的替代：只秒传选中文件里已收录的那些。未收录的文件没有 gcid，秒传不了。
 * 离线走不通（空间不够或次数用完）时是唯一的路；免费账号离线走得通时也给，作为二选一。
 */
data class InstantFallback(val fileCount: Int, val uploadCostBytes: Long, val skippedCount: Int)

/**
 * 账号对保存路线的约束。账号类型未知时按会员处理：登录时就会取到，面板打开前通常已经知道。
 */
data class SaveAccount(
    /** 免费账号只要都已收录就秒传，见 [planSave]。 */
    val free: Boolean = false,
    /** 今天还能建几个离线任务。会员不限、或还没查到时为 null。 */
    val offlineLeft: Int? = null,
)

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
    /** 要落进网盘的大小：秒传是选中的文件，离线是整包，下完才删掉没选的。 */
    val neededBytes: Long = 0,
    /** [neededBytes] 放不进网盘的剩余空间。余量未知时为 false，不拦。 */
    val lacksSpace: Boolean = false,
    /** 要离线，而今天的离线次数已经用完。 */
    val lacksOfflineCount: Boolean = false,
    val fallback: InstantFallback? = null,
) {
    val blocked: Boolean get() = lacksSpace || lacksOfflineCount
}

/**
 * 按勾选定路线。会员只选了一项（一个视频连同它打包的字幕）且都已收录就秒传，其余整包离线；
 * 免费账号只要选中的都已收录就秒传，有未收录的才整包离线。
 *
 * 会员为什么不是「全部可秒传就秒传」：秒传按大小的 15% 扣上传额度，内容已在账号里也照扣；
 * 离线按全额扣离线额度，而会员每月离线 40 TiB、上传 1 TiB，同样的内容秒传贵约六倍。
 * 秒传赢在一两个文件时快，整季整包时离线更划算，下完再删掉没选的即可。
 * 免费账号的离线额度一样是 40 TiB，但每天只能建三个任务，整包还要先装进 6 GB，
 * 而秒传不算离线次数，只存选中的，所以能秒传就秒传；有未收录的要整包离线时，
 * 一并给出只秒传已收录部分的 [SavePlan.fallback]，保存前让用户选。
 *
 * [remainingBytes] 是网盘剩余空间，null 表示未知。离线要先把整包落进网盘才能删，
 * 所以比的是整包大小，不是选中的大小。
 */
fun planSave(
    items: List<InstantFileItem>,
    selected: Set<Int>,
    selectedEntryCount: Int,
    remainingBytes: Long?,
    account: SaveAccount = SaveAccount(),
): SavePlan? {
    val chosen = selected.sorted().mapNotNull(items::getOrNull)
    if (chosen.isEmpty()) return null
    if (chosen.all { it.isInstantReady } && (selectedEntryCount == 1 || account.free)) {
        val chosenBytes = chosen.sumOf { it.file.size }
        val neededBytes = chosenBytes
        return SavePlan(
            route = SaveRoute.INSTANT,
            fileCount = chosen.size,
            uploadCostBytes = uploadCharge(chosenBytes),
            neededBytes = neededBytes,
            lacksSpace = remainingBytes != null && neededBytes > remainingBytes,
        )
    }
    val packBytes = items.sumOf { it.file.size }
    val lacksSpace = remainingBytes != null && packBytes > remainingBytes
    val lacksOfflineCount = account.offlineLeft == 0
    val ready = chosen.filter { it.isInstantReady }
    return SavePlan(
        route = SaveRoute.OFFLINE_PACK,
        fileCount = chosen.size,
        packBytes = packBytes,
        prunedCount = items.size - chosen.size,
        neededBytes = packBytes,
        lacksSpace = lacksSpace,
        lacksOfflineCount = lacksOfflineCount,
        // 免费账号离线走得通也给：未收录的那几个未必值一次离线，由用户挑
        fallback = if ((lacksSpace || lacksOfflineCount || account.free) && ready.isNotEmpty()) {
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
