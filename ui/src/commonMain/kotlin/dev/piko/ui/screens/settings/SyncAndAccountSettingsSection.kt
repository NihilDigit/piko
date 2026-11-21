package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.i18n.tr
import dev.piko.shared.sync.EncryptedSyncStatus
import dev.piko.shared.sync.PikoSettingsSync
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.PassphraseUpdateResult
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.screens.archive.SavedArchivePasswordsDialog
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal fun hasAccountSectionMatches(searchQuery: String): Boolean {
    if (searchQuery.isBlank()) return true
    return matchesSettingsQuery(
        searchQuery,
        SettingsSection.Account.title,
        "account", "账号", "退出", "切换", "会员", "sign out", "storage",
    )
}

@Composable
internal fun hasDataSectionMatches(searchQuery: String): Boolean {
    if (searchQuery.isBlank()) return true
    val services = LocalPikoServices.current
    val isFreeAccount by services.driveRepository.isFreeAccountFlow.collectAsStateWithLifecycle()
    fun match(vararg terms: String): Boolean = matchesSettingsQuery(searchQuery, *terms)

    val matchSyncSettings = match("同步设置", "Sync settings", "sync", "設定を同期", "설정 동기화", "同步設定")
    val matchSyncHistory = match("同步播放记录", "Sync playback history", "history", "再生履歴", "재생 기록", "播放記錄")
    val matchArchivePw = match("解压密码", "Archive password", "zip", "rar", "解凍パスワード", "압축 해제 비밀번호", "解壓縮密碼")
    val matchEncSync = services.supportsEncryptedSync &&
        match("加密同步口令", "Encrypted sync passphrase", "passphrase", "encrypt", "暗号化同期", "암호화 동기화")
    val matchWebDav = isFreeAccount == false && match("WebDAV", "dav", "挂载", "播放器")
    return matchSyncSettings || matchSyncHistory || matchArchivePw || matchEncSync || matchWebDav
}

internal fun hasAboutSectionMatches(searchQuery: String): Boolean {
    if (searchQuery.isBlank()) return true
    return matchesSettingsQuery(
        searchQuery,
        SettingsSection.About.title,
        "about", "update", "version", "更新", "版本", "日志", "telegram", "关于",
    )
}

@Composable
internal fun AccountSettingsSection(
    searchQuery: String,
    firstSection: SettingsSection?,
    onSectionPositioned: (SettingsSection, Int) -> Unit,
    account: (@Composable ColumnScope.() -> Unit)?,
) {
    if (account != null && hasAccountSectionMatches(searchQuery)) {
        SettingsSectionBlock(
            section = SettingsSection.Account,
            first = firstSection == SettingsSection.Account,
            onPositioned = { onSectionPositioned(SettingsSection.Account, it) },
            content = account,
        )
    }
}

/**
 * 同步与数据：均挂在账号上并随账号切换，不放进「网络」（那里是当前设备自身的连接设置）。
 */
@Composable
internal fun SyncAndDataSettingsSection(
    searchQuery: String,
    firstSection: SettingsSection?,
    onSectionPositioned: (SettingsSection, Int) -> Unit,
    onOpenWebDav: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val services = LocalPikoServices.current
    val sessionManager = services.preferences
    val scope = rememberCoroutineScope()

    val isSyncPlayHistoryEnabled by sessionManager.syncPlayHistoryFlow.collectAsStateWithLifecycle(initialValue = true)
    val isSettingsSyncEnabled by sessionManager.settingsSyncFlow.collectAsStateWithLifecycle(initialValue = true)
    val settingsSync = services.settingsSync
    val syncStatus by settingsSync.status.collectAsStateWithLifecycle()
    val lastSynced by settingsSync.lastSynced.collectAsStateWithLifecycle()
    val isFreeAccount by services.driveRepository.isFreeAccountFlow.collectAsStateWithLifecycle()

    val archivePasswordVault = services.archivePasswords
    val archivePasswords by archivePasswordVault.passwords.collectAsStateWithLifecycle(initialValue = emptyList())
    var showArchivePasswords by remember { mutableStateOf(false) }

    val encryptedSyncStatus by services.encryptedSyncStatus.collectAsStateWithLifecycle()
    val activeClient by services.clientManager.currentClient.collectAsStateWithLifecycle()
    var hasSyncPassphrase by remember(activeClient?.account) { mutableStateOf(false) }
    var showEncryptedSyncDialog by remember { mutableStateOf(false) }
    var encryptedSyncBusy by remember { mutableStateOf(false) }
    var encryptedSyncMismatchError by remember(activeClient?.account) { mutableStateOf<String?>(null) }

    LaunchedEffect(activeClient?.account, showEncryptedSyncDialog) {
        hasSyncPassphrase = services.hasSyncPassphraseForCurrentAccount()
    }

    if (showArchivePasswords) {
        SavedArchivePasswordsDialog(
            passwords = archivePasswords,
            onDelete = { scope.launch { archivePasswordVault.forget(it) } },
            onDismiss = { showArchivePasswords = false },
        )
    }

    if (showEncryptedSyncDialog) {
        EncryptedSyncSettingsDialog(
            status = encryptedSyncStatus,
            hasPassphrase = hasSyncPassphrase,
            busy = encryptedSyncBusy,
            mismatchError = encryptedSyncMismatchError,
            onClearMismatchError = { encryptedSyncMismatchError = null },
            onSavePassphrase = { passphrase ->
                encryptedSyncBusy = true
                encryptedSyncMismatchError = null
                scope.launch {
                    try {
                        val outcome = services.updateSyncPassphrase(passphrase)
                        hasSyncPassphrase = services.hasSyncPassphraseForCurrentAccount()
                        when (outcome) {
                            PassphraseUpdateResult.SavedAndSynced -> {
                                showEncryptedSyncDialog = false
                                snackbarHostState.showSnackbar(
                                    tr(
                                        "已保存同步口令并完成加密同步",
                                        "Sync passphrase saved and synced",
                                        "同期パスフレーズを保存して同期しました",
                                        "동기화 암호문구를 저장하고 동기화했습니다",
                                        "已儲存同步口令並完成加密同步",
                                    ),
                                    withDismissAction = true,
                                )
                            }
                            PassphraseUpdateResult.SavedOffline -> {
                                showEncryptedSyncDialog = false
                                snackbarHostState.showSnackbar(
                                    tr(
                                        "已保存同步口令，待网络恢复后自动同步",
                                        "Sync passphrase saved; will sync when online",
                                        "同期パスフレーズを保存しました（オンライン時に同期）",
                                        "동기화 암호문구 저장됨 (온라인 상태 시 동기화)",
                                        "已儲存同步口令，待網路恢復後自動同步",
                                    ),
                                    withDismissAction = true,
                                )
                            }
                            PassphraseUpdateResult.KeyMismatch -> {
                                encryptedSyncMismatchError = tr(
                                    "口令无法解开云端密文，请核对原口令或在上方备份重置",
                                    "Passphrase does not match cloud ciphertext; check it or reset above",
                                    "パスフレーズがクラウドの暗号化データと一致しません",
                                    "암호문구가 클라우드 암호문과 일치하지 않습니다",
                                    "口令無法解開雲端密文，請核對原口令或在上方備份重設",
                                )
                            }
                            PassphraseUpdateResult.Failed -> {
                                showEncryptedSyncDialog = false
                                snackbarHostState.showSnackbar(
                                    tr(
                                        "同步口令保存失败",
                                        "Failed to save sync passphrase",
                                        "同期パスフレーズの保存に失敗しました",
                                        "동기화 암호문구 저장 실패",
                                        "同步口令儲存失敗",
                                    ),
                                    withDismissAction = true,
                                )
                            }
                        }
                    } finally {
                        encryptedSyncBusy = false
                    }
                }
            },
            onClearPassphrase = {
                encryptedSyncBusy = true
                encryptedSyncMismatchError = null
                scope.launch {
                    try {
                        services.updateSyncPassphrase("")
                        hasSyncPassphrase = services.hasSyncPassphraseForCurrentAccount()
                        showEncryptedSyncDialog = false
                    } finally {
                        encryptedSyncBusy = false
                    }
                }
            },
            onResetCloud = {
                encryptedSyncBusy = true
                encryptedSyncMismatchError = null
                scope.launch {
                    try {
                        val ok = services.resetEncryptedSyncRemote()
                        showEncryptedSyncDialog = false
                        val msg = if (ok) {
                            tr(
                                "已备份旧云端密文并用本机数据重置",
                                "Backed up old ciphertext and reset cloud copy",
                                "旧暗号化データをバックアップしてリセットしました",
                                "기존 암호문을 백업하고 클라우드 데이터를 초기화했습니다",
                                "已備份舊雲端密文並以本機資料重設",
                            )
                        } else {
                            tr(
                                "云端重置未完成，请检查网络或先设置口令",
                                "Cloud reset failed. Check connection or set a passphrase first.",
                                "リセットできませんでした。通信環境またはパスフレーズを確認してください",
                                "초기화하지 못했습니다. 네트워크 또는 암호문구를 확인해 주세요",
                                "雲端重設未完成，請檢查網路或先設定口令",
                            )
                        }
                        snackbarHostState.showSnackbar(msg, withDismissAction = true)
                    } finally {
                        encryptedSyncBusy = false
                    }
                }
            },
            onDismiss = {
                if (!encryptedSyncBusy) {
                    encryptedSyncMismatchError = null
                    showEncryptedSyncDialog = false
                }
            },
        )
    }

    fun match(vararg terms: String): Boolean = matchesSettingsQuery(searchQuery, *terms)

    val matchSyncSettings = match("同步设置", "Sync settings", "sync", "設定を同期", "설정 동기화", "同步設定")
    val matchSyncHistory = match("同步播放记录", "Sync playback history", "history", "再生履歴", "재생 기록", "播放記錄")
    val matchSyncGroup = matchSyncSettings || matchSyncHistory

    val matchArchivePw = match("解压密码", "Archive password", "zip", "rar", "解凍パスワード", "압축 해제 비밀번호", "解壓縮密碼")
    val matchEncSync = services.supportsEncryptedSync &&
        match("加密同步口令", "Encrypted sync passphrase", "passphrase", "encrypt", "暗号化同期", "암호화 동기화")
    val matchWebDav = isFreeAccount == false && match("WebDAV", "dav", "挂载", "播放器")
    val matchCredGroup = matchArchivePw || matchEncSync || matchWebDav

    if (matchSyncGroup || matchCredGroup) {
        SettingsSectionBlock(
            section = SettingsSection.Data,
            first = firstSection == SettingsSection.Data,
            onPositioned = { onSectionPositioned(SettingsSection.Data, it) },
        ) {
            if (matchSyncGroup) {
                SettingsGroup(tr("同步", "Sync", "同期", "동기화", "同步")) {
                    if (matchSyncSettings) {
                        SettingsSyncRow(
                            enabled = isSettingsSyncEnabled,
                            status = syncStatusText(syncStatus, lastSynced),
                            onEnabledChange = { scope.launch { sessionManager.setSettingsSyncEnabled(it) } },
                            onSyncNow = { scope.launch { services.syncAllNow() } },
                        )
                    }
                    if (matchSyncHistory) {
                        SettingsSwitchRow(
                            icon = Icons.Outlined.History,
                            title = tr("同步播放记录", "Sync playback history", "再生履歴を同期", "재생 기록 동기화", "同步播放記錄"),
                            supporting = tr(
                                "与 PikPak 官方客户端共用播放历史与续播进度",
                                "Share playback history and resume positions with official PikPak apps",
                                "PikPak公式アプリと再生履歴、再開位置を共有",
                                "PikPak 공식 앱과 재생 기록 및 이어보기 위치 공유",
                                "與 PikPak 官方用戶端共用播放歷史與續播進度",
                            ),
                            checked = isSyncPlayHistoryEnabled,
                            onCheckedChange = { scope.launch { sessionManager.setSyncPlayHistoryEnabled(it) } },
                        )
                    }
                }
            }
            if (matchCredGroup) {
                SettingsGroup(tr("密码与凭据", "Passwords and credentials", "パスワードと認証情報", "비밀번호 및 인증 정보", "密碼與憑證")) {
                    if (matchArchivePw) {
                        SettingsNavigationRow(
                            icon = Icons.Outlined.Key,
                            title = tr("解压密码", "Archive password", "解凍パスワード", "압축 해제 비밀번호", "解壓縮密碼"),
                            supporting = if (archivePasswords.isEmpty()) {
                                tr("未保存密码", "No saved passwords", "保存済みパスワードはありません", "저장된 비밀번호가 없습니다", "無已儲存密碼")
                            } else {
                                tr("已保存 ${archivePasswords.size} 个", "${archivePasswords.size} saved", "${archivePasswords.size} 件保存済み", "${archivePasswords.size}개 저장됨", "已儲存 ${archivePasswords.size} 個")
                            },
                            onClick = { showArchivePasswords = true },
                            trailingIcon = null,
                        )
                    }
                    if (matchEncSync && services.supportsEncryptedSync) {
                        val syncPassphraseSummary = when (encryptedSyncStatus) {
                            EncryptedSyncStatus.LockedKeyMismatch -> tr(
                                "云端密文与当前密钥不匹配，已暂停覆盖，点击输入口令或重置",
                                "Cloud data encrypted with another key; paused to protect data. Tap to unlock or reset.",
                                "クラウドの暗号化データとキーが一致しません。タップして解除またはリセット",
                                "클라우드 암호문과 키가 일치하지 않아 보호 중. 탭하여 잠금 해제 또는 초기화",
                                "雲端密文與目前金鑰不符，已暫停覆寫，點選輸入口令或重設",
                            )
                            EncryptedSyncStatus.LockedNoKey -> tr(
                                "未设置（Google 登录账号设置后可跨设备加密同步密码与配置）",
                                "Not set (set a passphrase to encrypt and sync passwords & MetaTube on Google accounts)",
                                "未設定（設定すると解凍パスワードやMetaTubeを端末間で暗号化同期できます）",
                                "미설정 (설정 시 압축 해제 비밀번호 및 MetaTube를 기기 간 암호화 동기화)",
                                "未設定（Google 登入帳號設定後可跨裝置加密同步密碼與設定）",
                            )
                            EncryptedSyncStatus.CorruptOrUnsupported -> tr(
                                "云端同步文件需要更新版本或已损坏，已跳过同步以保护数据",
                                "Cloud sync file requires a newer version or is damaged; skipped to preserve data",
                                "クラウドの同期ファイルが新しいバージョン用か破損しているためスキップしました",
                                "클라우드 동기화 파일이 최신 버전용이거나 손상되어 건너뛰었습니다",
                                "雲端同步檔案需要較新版本或已損壞，已略過同步以保護資料",
                            )
                            EncryptedSyncStatus.Idle, EncryptedSyncStatus.Unlocked -> if (hasSyncPassphrase) {
                                tr(
                                    "已启用独立同步口令",
                                    "Custom sync passphrase enabled",
                                    "独自の同期パスフレーズを使用中",
                                    "별도 동기화 암호문구 사용 중",
                                    "已啟用獨立同步口令",
                                )
                            } else {
                                tr(
                                    "默认使用登录密码加密，点击可设置独立同步口令",
                                    "Encrypted with account password by default; tap to set a custom passphrase",
                                    "既定ではログインパスワードを使用。タップして独自パスフレーズを設定",
                                    "기본적으로 로그인 비밀번호 사용. 탭하여 별도 암호문구 설정",
                                    "預設使用登入密碼加密，點選可設定獨立同步口令",
                                )
                            }
                        }
                        SettingsRow(
                            icon = Icons.Outlined.Lock,
                            title = tr("加密同步口令", "Encrypted sync passphrase", "暗号化同期パスフレーズ", "암호화 동기화 암호문구", "加密同步口令"),
                            supporting = syncPassphraseSummary,
                            supportingColor = if (encryptedSyncStatus == EncryptedSyncStatus.LockedKeyMismatch) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color.Unspecified
                            },
                            onClick = {
                                encryptedSyncMismatchError = null
                                showEncryptedSyncDialog = true
                            },
                        )
                    }
                    // WebDAV 仅限会员，免费账号与等级未取到时不出现
                    if (matchWebDav && isFreeAccount == false) {
                        SettingsNavigationRow(
                            icon = Icons.Outlined.Lan,
                            title = "WebDAV",
                            supporting = tr(
                                "供播放器、文件管理器等应用读取网盘",
                                "Access the drive from media players and file managers",
                                "プレーヤーやファイルマネージャーからドライブにアクセス",
                                "플레이어 및 파일 관리자에서 드라이브 접근",
                                "供播放器、檔案管理員等應用程式讀取雲端硬碟",
                            ),
                            onClick = onOpenWebDav,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AboutSettingsSection(
    searchQuery: String,
    firstSection: SettingsSection?,
    onSectionPositioned: (SettingsSection, Int) -> Unit,
    showAbout: Boolean,
    snackbarHostState: SnackbarHostState,
) {
    if (showAbout && hasAboutSectionMatches(searchQuery)) {
        SettingsSectionBlock(
            section = SettingsSection.About,
            first = firstSection == SettingsSection.About,
            onPositioned = { onSectionPositioned(SettingsSection.About, it) },
        ) {
            AboutSection(snackbarHostState)
        }
    }
}

private fun syncStatusText(status: PikoSettingsSync.Status, lastSynced: Long?): String = when (status) {
    PikoSettingsSync.Status.SYNCING -> tr("同步中…", "Syncing…", "同期中…", "동기화 중…", "同步中…")
    PikoSettingsSync.Status.FAILED -> tr(
        "同步失败，将在更改设置或下次启动时重试",
        "Sync failed; will retry when settings change or on next launch",
        "同期に失敗しました。設定変更時または次回起動時に再試行します",
        "동기화 실패. 설정 변경 시 또는 다음 실행 시 다시 시도합니다",
        "同步失敗，將於變更設定或下次啟動時重試",
    )
    else -> lastSynced?.let {
        val time = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault())
        val hh = time.hour.toString().padStart(2, '0')
        val mm = time.minute.toString().padStart(2, '0')
        tr(
            "上次同步于 $hh:$mm",
            "Last synced at $hh:$mm",
            "最終同期 $hh:$mm",
            "마지막 동기화 $hh:$mm",
            "上次同步於 $hh:$mm",
        )
    } ?: tr("登录后自动同步", "Sync automatically after sign-in", "ログイン後に自動同期", "로그인 후 자동 동기화", "登入後自動同步")
}

/**
 * 设置同步：开关与「立即同步」在同一行。立即同步只在开着时有意义，单独占一行是把一件事画成两件。
 * 开着时说明是同步的状态，关着时说明它同步什么、存在哪。
 */
@Composable
private fun SettingsSyncRow(
    enabled: Boolean,
    status: String,
    onEnabledChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
) {
    SettingsSwitchRow(
        icon = Icons.Outlined.CloudSync,
        title = tr("同步设置", "Sync settings", "設定を同期", "설정 동기화", "同步設定"),
        supporting = if (enabled) {
            status
        } else {
            tr(
                "将设置保存在网盘 .piko 文件夹中，在其他设备登录后自动同步",
                "Save settings to the .piko folder in your drive and sync across devices",
                "設定をドライブの .piko フォルダーに保存し、他の端末でログインした際に自動同期します",
                "설정을 드라이브의 .piko 폴더에 저장하여 다른 기기에서 로그인 시 자동으로 동기화합니다",
                "將設定儲存於雲端硬碟 .piko 資料夾中，於其他裝置登入後自動同步",
            )
        },
        checked = enabled,
        onCheckedChange = onEnabledChange,
        extraTrailing = if (enabled) {
            { TooltipIconButton(Icons.Outlined.Sync, tr("立即同步", "Sync now", "今すぐ同期", "지금 동기화", "立即同步"), onSyncNow) }
        } else {
            null
        },
    )
}
