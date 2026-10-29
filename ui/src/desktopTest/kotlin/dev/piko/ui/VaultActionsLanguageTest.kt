package dev.piko.ui

import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.TransferItem
import dev.piko.ui.components.SheetActionRole
import dev.piko.ui.screens.transfers.vaultTransferActions
import kotlin.test.Test
import kotlin.test.assertEquals

class VaultActionsLanguageTest {
    @Test
    fun stopActionsKeepTheirRoleAndCallbackInEveryLanguage() {
        var stopped = 0
        try {
            for (language in AppLanguage.entries.filter { it != AppLanguage.SYSTEM }) {
                PikoLocale.apply(language)
                for (restoring in listOf(false, true)) {
                    val item = TransferItem.Vault(FolderVaultSession.Work(1, "folder", restoring, 1))
                    val action = vaultTransferActions(item, false, { stopped++ }, {}, false, null, {}, {}).single()
                    assertEquals(SheetActionRole.Stop, action.role)
                    action.onClick()
                }
            }
            assertEquals(8, stopped)
        } finally {
            PikoLocale.apply(AppLanguage.ZH_CN)
        }
    }
}
