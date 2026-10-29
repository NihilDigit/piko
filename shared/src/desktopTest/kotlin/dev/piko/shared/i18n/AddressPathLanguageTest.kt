package dev.piko.shared.i18n

import dev.piko.data.auth.AppLanguage
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.addressPathNames
import kotlin.test.Test
import kotlin.test.assertEquals

class AddressPathLanguageTest {
    @Test
    fun rootAliasesRemainValidAcrossLanguagesAndDoNotReplaceFolderNames() {
        try {
            PikoLocale.apply(AppLanguage.KO)
            for (root in listOf("网盘", "我的网盘", "My drive", "マイドライブ", "내 드라이브")) {
                assertEquals(listOf("目录"), addressPathNames("$root/目录", emptyList()), root)
            }
            assertEquals(listOf("Drive", "目录"), addressPathNames("Drive/目录", emptyList()))
            assertEquals(listOf("My drive", "目录"), addressPathNames("/My drive/目录", emptyList()))
            assertEquals(listOf("Driveway", "目录"), addressPathNames("Driveway/目录", emptyList()))
            val current = listOf(PikoPathBreadcrumb("", "我的网盘"), PikoPathBreadcrumb("parent", "existing"))
            assertEquals(listOf("existing", "Drive", "目录"), addressPathNames("./Drive/目录", current))
        } finally {
            PikoLocale.apply(AppLanguage.ZH_CN)
        }
    }
}
