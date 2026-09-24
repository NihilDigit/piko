package dev.piko.desktop

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TorrentMagnetTest {

    // 由 torf 生成的多文件种子，info 前有 announce、info 内有 private 整数；期望的 infohash 取自 torf 自己的计算
    private val torrent = Base64.getDecoder().decode(
        "ZDg6YW5ub3VuY2UyNDp1ZHA6Ly90cmFja2VyLmV4YW1wbGU6ODA0OmluZm9kNTpmaWxlc2xkNjpsZW5ndGhpMWU0OnBhdGhsODplcDAxLmFz" +
            "c2VlZDY6bGVuZ3RoaTEwZTQ6cGF0aGw4OmVwMDEubWt2ZWVlNDpuYW1lMTA655Wq5YmnIFMwMTEyOnBpZWNlIGxlbmd0aGkxNjM4NGU2OnBp" +
            "ZWNlczIwOs01YLZsKz6P31CQa7ytHSfULefdNzpwcml2YXRlaTBlZWU=",
    )

    @Test
    fun infohashMatchesTheRawInfoBytes() {
        assertEquals(
            "magnet:?xt=urn:btih:500a68f42941ca214ba78490c1f5aafbaec5581e&dn=%E7%95%AA%E5%89%A7%20S01",
            TorrentMagnet.fromBytes(torrent),
        )
    }

    @Test
    fun notATorrent() {
        assertNull(TorrentMagnet.fromBytes("hello".toByteArray()))
        assertNull(TorrentMagnet.fromBytes(torrent.copyOf(40)))
    }
}
