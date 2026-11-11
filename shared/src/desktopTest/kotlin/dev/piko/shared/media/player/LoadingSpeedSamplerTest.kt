package dev.piko.shared.media.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class LoadingSpeedSamplerTest {

    @Test
    fun suppressesMetadataAndProbeLowSpeedThenRevealsTrueStreamingRate() {
        val clock = TestTimeSource()
        val sampler = LoadingSpeedSampler(clock)

        // 1. 取流 API 阶段尚未拿到代理流，不闪现 0 KB/s
        assertNull(sampler.sample(token = null, currentBytes = null, isReactivating = false))

        // 2. 代理流刚建好（t = 0）建立基线，不显示 0 KB/s
        val stream = Any()
        assertNull(sampler.sample(token = stream, currentBytes = 0L, isReactivating = false))

        // 3. t = 350 ms：容器头尾探测仅收了 64 KiB，不应折算成 ~180 KB/s 吓到用户
        clock += 350.milliseconds
        assertNull(sampler.sample(token = stream, currentBytes = 64L * 1024L, isReactivating = false))

        // 4. t = 700 ms：进入正片多路并发拉流，过去 350 ms 内收到约 2.8 MiB（8.0 MB/s），
        // 首拍直接展示当前正片拉流速率，不被上一拍的 64 KiB 探测拉低
        clock += 350.milliseconds
        val revealed = sampler.sample(
            token = stream,
            currentBytes = 64L * 1024L + (2868L * 1024L),
            isReactivating = false,
        )
        assertEquals("8.0 MB/s", revealed)
    }

    @Test
    fun revealsZeroRateWhenGenuinelyStalledPastThreshold() {
        val clock = TestTimeSource()
        val sampler = LoadingSpeedSampler(clock)
        val stream = Any()

        assertNull(sampler.sample(token = stream, currentBytes = 100L, isReactivating = false))
        clock += 700.milliseconds
        assertNull(sampler.sample(token = stream, currentBytes = 100L, isReactivating = false))
        clock += 700.milliseconds
        assertNull(sampler.sample(token = stream, currentBytes = 100L, isReactivating = false))

        // 超过 1.5s 仍无数据到达，如实提示 0 KB/s
        clock += 200.milliseconds
        assertEquals("0 KB/s", sampler.sample(token = stream, currentBytes = 100L, isReactivating = false))
    }

    @Test
    fun coldReactivationShowsImmediatelyAndResetsBaselineAfterWake() {
        val clock = TestTimeSource()
        val sampler = LoadingSpeedSampler(clock)
        val stream = Any()

        val waking = sampler.sample(token = stream, currentBytes = 0L, isReactivating = true)
        assertTrue(!waking.isNullOrBlank() && waking != "0 KB/s")

        clock += 5_000.milliseconds
        // 刚唤醒恢复首拍重建基线，不把唤醒等待的 5 秒算进分母
        assertNull(sampler.sample(token = stream, currentBytes = 1024L, isReactivating = false))

        clock += 700.milliseconds
        val postWake = sampler.sample(
            token = stream,
            currentBytes = 1024L + 3584L * 1024L,
            isReactivating = false,
        )
        assertEquals("5.0 MB/s", postWake)
    }
}
