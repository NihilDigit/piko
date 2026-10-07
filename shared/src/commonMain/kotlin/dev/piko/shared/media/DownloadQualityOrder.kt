package dev.piko.shared.media

import dev.piko.data.auth.PlayerGestureDefaults

/**
 * 按画质上限 [maxHeight] 排出这个视频各档的先后，排在最前的就是要下的一档；0 是原画，只排原画。
 * 返回先后而不是一档：下载开始前还要逐档探测，探出读不出的跳到下一个。读不出的档（[DownloadQuality.unreadable]）不参加。
 *
 * 规则是「不高于上限的最高一档；没有就取最低的一档」。
 * - 原画与转码按画面高度比，同一把尺子。档名比不出高低：转码叫「1080P」，原画叫「Original」。
 *   原画的高度未知（0，服务端没抽出元数据）时当作最高：转码由原画压出来，不会比它高。
 * - 同高时，不高于上限的一侧原画在前：画面相同，原画没有经过二次压缩，也不用转封装；高于上限的一侧转码在前，它更小。
 * - 没有不高于上限的档（只剩比所选高的转码，或原画本身就高于所选而没有更低的转码）时取最低的一档，不退回原画：
 *   选低一档是为了省流量与空间，剩下几档里最低的离所选最近、也最小，原画是最大的。没有转码的视频只有原画，取原画。
 */
fun downloadQualityOrder(options: List<DownloadQuality>, maxHeight: Int): List<DownloadQuality> {
    val usable = options.filter { !it.unreadable }
    if (maxHeight <= 0) return usable.filter { it.isOriginal }
    val ascending = usable.sortedWith(compareBy<DownloadQuality> { it.rank }.thenBy { it.isOriginal })
    val (within, above) = ascending.partition { it.rank <= maxHeight }
    return within.sortedWith(compareByDescending<DownloadQuality> { it.rank }.thenByDescending { it.isOriginal }) + above
}

/** 按上限要下的那一档，见 [downloadQualityOrder]。 */
fun chooseDownloadQuality(options: List<DownloadQuality>, maxHeight: Int): DownloadQuality? =
    downloadQualityOrder(options, maxHeight).firstOrNull()

/**
 * 单个视频里选定的一档换成设置里的画质上限，「以后不再询问」时存下。取不低于这一档的最小一级：
 * 转码的高度不一定恰好是 1080、720、480，取低一级的话，这个视频本身以后就挑不到它了。高于所有级别的算原画。
 */
fun downloadMaxHeightFor(quality: DownloadQuality): Int {
    if (quality.isOriginal) return 0
    return PlayerGestureDefaults.MaxHeightChoices.filter { it > 0 && it >= quality.height }.minOrNull() ?: 0
}

private val DownloadQuality.rank: Int
    get() = if (isOriginal && height <= 0) Int.MAX_VALUE else height
