package dev.piko.shared.naming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SiteNoiseNativeTest {
    @Test
    fun initializesRulesWhenDescribingFolder() {
        assertEquals("example.com" to 12, leadingDomain("example.com-Folder"))
        assertNotNull(describeFolder("Example folder"))
    }

    @Test
    fun stripsChannelPromotionWithHanCharacters() {
        assertEquals("Title", stripSiteNoise("更多视频请在Telegram收藏夹发送@sample丨Title"))
        assertEquals("129507", stripSiteNoise("129507 TG频道@sample"))
    }
}
