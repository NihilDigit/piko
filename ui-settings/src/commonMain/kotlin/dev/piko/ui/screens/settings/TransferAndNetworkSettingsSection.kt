package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.data.auth.DownloadConcurrencyDefaults
import dev.piko.data.auth.SnailMode
import dev.piko.shared.i18n.tr
import dev.piko.shared.net.ProxySetting
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.tabular
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun hasTransferSectionMatches(searchQuery: String): Boolean {
    if (searchQuery.isBlank()) return true
    val sessionManager = LocalPikoServices.current.preferences
    val platform = LocalPikoPlatform.current
    val downloadLocation = platform.downloadLocation
    val downloadDirPath by sessionManager.downloadDirPathFlow.collectAsStateWithLifecycle(initialValue = "")
    val resolvedDownloadPath = remember(downloadDirPath) { downloadLocation.displayName(downloadDirPath) }
    val snailMode by sessionManager.snailModeFlow.collectAsStateWithLifecycle(initialValue = SnailMode())
    fun match(vararg terms: String): Boolean = matchesSettingsQuery(searchQuery, *terms)

    val matchDlDir = match("下载位置", "Download location", "folder", "path", "ダウンロード先", "다운로드 위치", "下載位置", resolvedDownloadPath)
    val matchAskDlLoc = downloadLocation.canChoose && match(
        "每次下载前选择位置",
        "Ask where to save each download",
        "ダウンロードごとに保存先を選択",
        "다운로드할 때마다 저장 위치 선택",
        "每次下載前選擇位置",
        "folder",
        "location",
        "ask",
    )
    val matchDlQuality = match("默认下载画质", "Default download quality", "ダウンロード画質", "기본 다운로드 화질", "預設下載畫質")
    val matchDlConcurrency = match("同时下载", "同时下载数", "并发下载", "Concurrent downloads", "simultaneous", "同時ダウンロード", "동시 다운로드", "同時下載")
    val matchBgTransfer = platform.backgroundTransferAccess != null &&
        match("后台持续传输", "Unrestricted background transfers", "battery", "バックグラウンド", "백그라운드", "背景持續傳輸")
    val matchSnail = match("蜗牛模式", "Snail mode", "speed limit", "限速", "低速モード", "저속 모드", "蝸牛模式")
    val matchSnailLimit = snailMode.enabled && match("速度上限", "Speed limit", "速度上限", "속도 한도")
    return matchDlDir || matchAskDlLoc || matchDlQuality || matchDlConcurrency || matchBgTransfer || matchSnail || matchSnailLimit
}

internal fun hasStorageSectionMatches(searchQuery: String): Boolean {
    if (searchQuery.isBlank()) return true
    return matchesSettingsQuery(
        searchQuery,
        SettingsSection.Storage.title,
        "存储", "缓存", "清理", "缩略图", "播放缓存", "进度条预览图", "预览图", "信息流", "索引",
        "storage", "cache", "clear", "cleanup", "thumbnail", "playback", "seekbar", "preview", "ttl", "limit",
        "ストレージ", "キャッシュ", "消去", "シークバープレビュー", "저장공간", "캐시", "정리", "탐색 미리보기", "儲存", "快取", "進度條預覽圖",
    )
}

internal fun hasNetworkSectionMatches(searchQuery: String): Boolean {
    if (searchQuery.isBlank()) return true
    val matchProxy = matchesSettingsQuery(searchQuery, "网络代理", "Network proxy", "socks", "http", "proxy", "プロキシ", "프록시", "網路代理")
    val matchDomain = matchesSettingsQuery(searchQuery, "服务器域名", "Server domain", "dns", "host", "ドメイン", "도메인", "伺服器網域")
    return matchProxy || matchDomain
}

@Composable
internal fun TransferSettingsSection(
    searchQuery: String,
    firstSection: SettingsSection?,
    onSectionPositioned: (SettingsSection, Int) -> Unit,
) {
    val sessionManager = LocalPikoServices.current.preferences
    val platform = LocalPikoPlatform.current
    val scope = rememberCoroutineScope()

    val downloadDirPath by sessionManager.downloadDirPathFlow.collectAsStateWithLifecycle(initialValue = "")
    val askDownloadLocation by sessionManager.askDownloadLocationFlow.collectAsStateWithLifecycle(
        initialValue = sessionManager.currentSnapshot.askDownloadLocation,
    )
    val maxConcurrentDownloads by sessionManager.maxConcurrentDownloadsFlow.collectAsStateWithLifecycle(
        initialValue = sessionManager.currentSnapshot.maxConcurrentDownloads,
    )
    var showDownloadQuality by remember { mutableStateOf(false) }
    val downloadMaxHeight by sessionManager.downloadMaxHeightFlow.collectAsStateWithLifecycle(initialValue = null)
    var showDownloadDirDialog by remember { mutableStateOf(false) }
    val snailMode by sessionManager.snailModeFlow.collectAsStateWithLifecycle(initialValue = SnailMode())
    var showSnailDialog by remember { mutableStateOf(false) }

    val downloadLocation = platform.downloadLocation
    val resolvedDownloadPath = remember(downloadDirPath) { downloadLocation.displayName(downloadDirPath) }
    // 选完不关对话框，让用户在对话框里看到新位置
    val pickDownloadDir = downloadLocation.rememberLauncher { picked ->
        scope.launch { sessionManager.setDownloadDirPath(picked) }
    }

    val backgroundTransferAccess = platform.backgroundTransferAccess
    val isWindowFocused = LocalWindowInfo.current.isWindowFocused
    var isBackgroundUnrestricted by remember(backgroundTransferAccess) {
        mutableStateOf(backgroundTransferAccess?.isUnrestricted() ?: true)
    }
    if (backgroundTransferAccess != null) {
        LaunchedEffect(backgroundTransferAccess, isWindowFocused) {
            if (isWindowFocused) isBackgroundUnrestricted = backgroundTransferAccess.isUnrestricted()
        }
    }

    if (showSnailDialog) {
        SnailModeDialog(
            current = snailMode,
            onSave = { mode ->
                showSnailDialog = false
                scope.launch { sessionManager.setSnailMode(mode) }
            },
            onDismiss = { showSnailDialog = false },
        )
    }

    if (showDownloadQuality) {
        PlaybackQualityDialog(
            maxHeight = downloadMaxHeight ?: 0,
            onSelect = { scope.launch { sessionManager.setDownloadMaxHeight(it) } },
            onDismiss = { showDownloadQuality = false },
            title = tr("默认下载画质", "Default download quality", "既定のダウンロード画質", "기본 다운로드 화질", "預設下載畫質"),
            description = tr(
                "普通下载默认采用此画质，也可通过「下载指定画质」单次选择。优先选用不高于所选上限的最高画质，若均超出上限则选用最低画质；转码视频保存为 MP4 格式。",
                "Standard downloads use this quality by default; use Download at chosen quality to override per download. Selects the highest quality within the limit, or the lowest available if all exceed it. Transcoded videos are saved as MP4.",
                "通常のダウンロードではこの画質を使用し、「画質を指定してダウンロード」で個別に変更できます。上限以下で最も高い画質を選択し、すべて上限を超える場合は最低画質を選択します。変換動画はMP4形式で保存されます。",
                "일반 다운로드 시 기본적으로 이 화질을 사용하며 '화질을 선택하여 다운로드'로 개별 선택할 수 있습니다. 선택한 한도 이하의 최고 화질을 사용하며 모두 초과할 경우 최저 화질을 사용합니다. 변환 동영상은 MP4 형식으로 저장됩니다.",
                "一般下載預設採用此畫質，亦可透過「下載指定畫質」單次選擇。優先選用不高於所選上限的最高畫質，若均超出上限則選用最低畫質；轉碼影片儲存為 MP4 格式。",
            ),
        )
    }

    if (showDownloadDirDialog) {
        DownloadLocationDialog(
            description = downloadLocation.description,
            defaultPath = remember { downloadLocation.displayName("") },
            customPath = resolvedDownloadPath.takeIf { downloadDirPath.isNotEmpty() },
            onUseDefault = { scope.launch { sessionManager.setDownloadDirPath("") } },
            onPickFolder = pickDownloadDir,
            onDismiss = { showDownloadDirDialog = false },
        )
    }

    fun match(vararg terms: String): Boolean = matchesSettingsQuery(searchQuery, *terms)

    val matchDlDir = match("下载位置", "Download location", "folder", "path", "ダウンロード先", "다운로드 위치", "下載位置", resolvedDownloadPath)
    val matchAskDlLoc = downloadLocation.canChoose && match(
        "每次下载前选择位置",
        "Ask where to save each download",
        "ダウンロードごとに保存先を選択",
        "다운로드할 때마다 저장 위치 선택",
        "每次下載前選擇位置",
        "folder",
        "location",
        "ask",
    )
    val matchDlQuality = match("默认下载画质", "Default download quality", "ダウンロード画質", "기본 다운로드 화질", "預設下載畫質")
    val matchDlConcurrency = match("同时下载", "同时下载数", "并发下载", "Concurrent downloads", "simultaneous", "同時ダウンロード", "동시 다운로드", "同時下載")
    val matchBgTransfer = backgroundTransferAccess != null &&
        match("后台持续传输", "Unrestricted background transfers", "battery", "バックグラウンド", "백그라운드", "背景持續傳輸")
    val matchDlGroup = matchDlDir || matchAskDlLoc || matchDlQuality || matchDlConcurrency || matchBgTransfer

    val matchSnail = match("蜗牛模式", "Snail mode", "speed limit", "限速", "低速モード", "저속 모드", "蝸牛模式")
    val matchSnailLimit = snailMode.enabled && match("速度上限", "Speed limit", "速度上限", "속도 한도")
    val matchSnailGroup = matchSnail || matchSnailLimit

    if (matchDlGroup || matchSnailGroup) {
        SettingsSectionBlock(
            section = SettingsSection.Transfer,
            first = firstSection == SettingsSection.Transfer,
            onPositioned = { onSectionPositioned(SettingsSection.Transfer, it) },
        ) {
            if (matchDlGroup) {
                SettingsGroup(tr("下载", "Downloads", "ダウンロード", "다운로드", "下載")) {
                    if (matchDlDir) {
                        SettingsNavigationRow(
                            icon = Icons.Outlined.FolderOpen,
                            title = tr("下载位置", "Download location", "ダウンロード先", "다운로드 위치", "下載位置"),
                            supporting = resolvedDownloadPath,
                            onClick = { showDownloadDirDialog = true },
                            trailingIcon = null,
                        )
                    }
                    if (matchAskDlLoc) {
                        SettingsSwitchRow(
                            icon = Icons.Outlined.DriveFileMove,
                            title = tr(
                                "每次下载前选择位置",
                                "Ask where to save each download",
                                "ダウンロードごとに保存先を選択",
                                "다운로드할 때마다 저장 위치 선택",
                                "每次下載前選擇位置",
                            ),
                            supporting = null,
                            checked = askDownloadLocation,
                            onCheckedChange = { scope.launch { sessionManager.setAskDownloadLocation(it) } },
                        )
                    }
                    if (matchDlQuality) {
                        SettingsNavigationRow(
                            icon = Icons.Outlined.HighQuality,
                            title = tr("默认下载画质", "Default download quality", "既定のダウンロード画質", "기본 다운로드 화질", "預設下載畫質"),
                            supporting = downloadQualitySummary(downloadMaxHeight),
                            onClick = { showDownloadQuality = true },
                            trailingIcon = null,
                        )
                    }
                    if (matchDlConcurrency) {
                        val effectiveConcurrency = DownloadConcurrencyDefaults.sanitize(maxConcurrentDownloads)
                        val min = DownloadConcurrencyDefaults.MIN
                        val max = DownloadConcurrencyDefaults.MAX
                        SettingsRow(
                            icon = Icons.Outlined.Download,
                            title = tr("同时下载", "Concurrent downloads", "同時ダウンロード数", "동시 다운로드 수", "同時下載"),
                            supporting = tr(
                                "调小可集中带宽先下完单个文件，调大可加快多文件队列下载",
                                "Lower to finish one file at a time on slower links; raise to speed up multi-file queues",
                                "小さくすると帯域を集中して1ファイルずつ完了し、大きくすると複数ファイルの並列転送を速めます",
                                "줄이면 대역폭을 집중해 한 파일씩 먼저 완료하고, 늘리면 여러 파일 대기열 전송을 앞당깁니다",
                                "調小可集中頻寬先下完單一檔案，調大可加快多檔案佇列下載",
                            ),
                            trailing = {
                                Text(
                                    text = tr("$effectiveConcurrency 个", "$effectiveConcurrency", "$effectiveConcurrency 件", "${effectiveConcurrency}개", "$effectiveConcurrency 個"),
                                    style = MaterialTheme.typography.titleMedium.tabular,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            below = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Slider(
                                        value = effectiveConcurrency.toFloat(),
                                        onValueChange = { raw ->
                                            val snapped = raw.roundToInt().coerceIn(min, max)
                                            if (snapped != effectiveConcurrency) {
                                                scope.launch { sessionManager.setMaxConcurrentDownloads(snapped) }
                                            }
                                        },
                                        valueRange = min.toFloat()..max.toFloat(),
                                        steps = (max - min - 1).coerceAtLeast(0),
                                        modifier = Modifier.weight(1f).widthIn(min = 160.dp),
                                    )
                                }
                            },
                        )
                    }
                    if (matchBgTransfer && backgroundTransferAccess != null) {
                        SettingsNavigationRow(
                            icon = Icons.Outlined.PowerSettingsNew,
                            title = tr("后台持续传输", "Unrestricted background transfers", "バックグラウンド継続転送", "백그라운드 지속 전송", "背景持續傳輸"),
                            supporting = if (isBackgroundUnrestricted) {
                                tr(
                                    "已将系统电池优化设为「无限制」",
                                    "System battery optimization set to Unrestricted",
                                    "システムのバッテリー最適化が「制限なし」に設定されています",
                                    "시스템 배터리 최적화가 '제한 없음'으로 설정됨",
                                    "已將系統電池最佳化設為「無限制」",
                                )
                            } else {
                                tr(
                                    "受系统电池优化限制，切到后台可能被暂停，点击前往应用设置将电池设为「无限制」",
                                    "Restricted by system battery optimization; tap to set battery usage to Unrestricted in app settings",
                                    "システムのバッテリー最適化により制限されています。タップしてアプリ設定からバッテリーを「制限なし」に変更",
                                    "시스템 배터리 최적화로 제한됨. 탭하여 앱 설정에서 배터리를 '제한 없음'으로 변경",
                                    "受系統電池最佳化限制，切至背景可能被暫停，點擊前往應用程式設定將電池設為「無限制」",
                                )
                            },
                            onClick = { backgroundTransferAccess.requestUnrestricted() },
                            trailingIcon = if (isBackgroundUnrestricted) Icons.Outlined.Check else null,
                        )
                    }
                }
            }
            if (matchSnailGroup) {
                SettingsGroup(tr("限速", "Speed limits", "速度制限", "속도 제한", "限速")) {
                    if (matchSnail) {
                        SettingsSwitchRow(
                            icon = Icons.Outlined.SlowMotionVideo,
                            title = tr("蜗牛模式", "Snail mode", "低速モード", "저속 모드", "蝸牛模式"),
                            supporting = tr(
                                "限制下载与上传速度，不影响在线播放",
                                "Limit transfer speeds without affecting streaming",
                                "ダウンロードとアップロードの速度を制限。ストリーミングには影響しません",
                                "다운로드 및 업로드 속도를 제한하며 스트리밍에는 영향 없음",
                                "限制下載與上傳速度，不影響線上播放",
                            ),
                            checked = snailMode.enabled,
                            onCheckedChange = { scope.launch { sessionManager.setSnailMode(snailMode.copy(enabled = it)) } },
                        )
                    }
                    if (matchSnailLimit) {
                        DependentRow(visible = snailMode.enabled) {
                            SettingsNavigationRow(
                                icon = Icons.Outlined.Tune,
                                title = tr("速度上限", "Speed limit", "速度上限", "속도 한도", "速度上限"),
                                supporting = snailMode.limitSummary(),
                                onClick = { showSnailDialog = true },
                                trailingIcon = null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StorageSettingsSection(
    searchQuery: String,
    firstSection: SettingsSection?,
    onSectionPositioned: (SettingsSection, Int) -> Unit,
    snackbarHostState: SnackbarHostState,
    onOpenCachedVideo: ((fileId: String, fileName: String) -> Unit)? = null,
) {
    if (hasStorageSectionMatches(searchQuery)) {
        val services = LocalPikoServices.current
        SettingsSectionBlock(
            section = SettingsSection.Storage,
            first = firstSection == SettingsSection.Storage,
            onPositioned = { onSectionPositioned(SettingsSection.Storage, it) },
        ) {
            CacheSectionContent(
                cacheManager = services.cacheManager,
                preferences = services.preferences,
                snackbarHostState = snackbarHostState,
                currentAccount = services.driveRepository.currentAccount,
                driveRepository = services.driveRepository,
                onOpenCachedVideo = onOpenCachedVideo,
            )
        }
    }
}

@Composable
internal fun NetworkSettingsSection(
    searchQuery: String,
    firstSection: SettingsSection?,
    onSectionPositioned: (SettingsSection, Int) -> Unit,
) {
    val services = LocalPikoServices.current
    val sessionManager = services.preferences
    val scope = rememberCoroutineScope()

    val proxySetting by sessionManager.proxySettingFlow.collectAsStateWithLifecycle(initialValue = ProxySetting())
    var showProxyDialog by remember { mutableStateOf(false) }

    val domainSelector = services.domainSelector
    val domainChoice by sessionManager.pikpakDomainFlow.collectAsStateWithLifecycle(initialValue = "")
    val activeDomain by domainSelector.active.collectAsStateWithLifecycle()
    val domainProbes by domainSelector.probes.collectAsStateWithLifecycle()
    val domainProbing by domainSelector.probing.collectAsStateWithLifecycle()
    var showDomainDialog by remember { mutableStateOf(false) }

    if (showProxyDialog) {
        ProxySettingsDialog(
            current = proxySetting,
            onSave = { setting ->
                showProxyDialog = false
                scope.launch { sessionManager.saveProxySetting(setting) }
            },
            onDismiss = { showProxyDialog = false },
        )
    }

    if (showDomainDialog) {
        PikPakDomainDialog(
            choice = domainChoice,
            active = activeDomain,
            probes = domainProbes,
            probing = domainProbing,
            onChoose = { root -> scope.launch { sessionManager.setPikpakDomain(root) } },
            onProbeAgain = domainSelector::probeAgain,
            onDismiss = { showDomainDialog = false },
        )
    }

    val matchProxy = matchesSettingsQuery(searchQuery, "网络代理", "Network proxy", "socks", "http", "proxy", "プロキシ", "프록시", "網路代理")
    val matchDomain = matchesSettingsQuery(searchQuery, "服务器域名", "Server domain", "dns", "host", "ドメイン", "도메인", "伺服器網域")
    if (matchProxy || matchDomain) {
        SettingsSectionBlock(
            section = SettingsSection.Network,
            first = firstSection == SettingsSection.Network,
            onPositioned = { onSectionPositioned(SettingsSection.Network, it) },
        ) {
            SettingsGroup(null) {
                if (matchProxy) {
                    SettingsNavigationRow(
                        icon = Icons.Outlined.Public,
                        title = tr("网络代理", "Network proxy", "ネットワークプロキシ", "네트워크 프록시", "網路代理"),
                        supporting = proxySetting.summary(),
                        onClick = { showProxyDialog = true },
                        trailingIcon = null,
                    )
                }
                if (matchDomain) {
                    SettingsNavigationRow(
                        icon = Icons.Outlined.Dns,
                        title = tr("服务器域名", "Server domain", "サーバードメイン", "서버 도메인", "伺服器網域"),
                        supporting = domainSummary(domainChoice, activeDomain, domainProbes),
                        onClick = { showDomainDialog = true },
                        trailingIcon = null,
                    )
                }
            }
        }
    }
}

/**
 * 下载位置：两个单选项，默认位置与自定义位置，各带路径。「恢复默认」就是选中默认那一项，「更改」是点自定义那一项。
 * [customPath] 为 null 表示正用默认位置。已选自定义时再点它，照旧弹目录选择框，换一个文件夹。
 */
@Composable
private fun DownloadLocationDialog(
    description: String,
    defaultPath: String,
    customPath: String?,
    onUseDefault: () -> Unit,
    onPickFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsChoiceDialog(
        icon = Icons.Outlined.FolderOpen,
        title = tr("下载位置", "Download location", "ダウンロード先", "다운로드 위치", "下載位置"),
        description = description,
        onDismiss = onDismiss,
    ) {
        SettingsChoiceOption(
            title = tr("默认位置", "Default location", "既定の保存先", "기본 위치", "預設位置"),
            supporting = defaultPath,
            selected = customPath == null,
            onClick = onUseDefault,
        )
        SettingsChoiceOption(
            title = tr("自定义位置", "Custom location", "指定した保存先", "사용자 지정 위치", "自訂位置"),
            supporting = customPath ?: tr("选择一个文件夹", "Choose a folder", "フォルダーを選択", "폴더 선택", "選擇資料夾"),
            selected = customPath != null,
            onClick = onPickFolder,
            // 已选中时这一行点下去是换文件夹，不是切换，给一个编辑的提示
            trailingIcon = if (customPath != null) Icons.Outlined.Edit else null,
        )
    }
}
