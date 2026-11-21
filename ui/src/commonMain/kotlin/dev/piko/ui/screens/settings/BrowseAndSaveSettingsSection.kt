package dev.piko.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.i18n.tr
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.platform.LinkAssociation
import dev.piko.ui.platform.LinkAssociationState
import dev.piko.ui.platform.LocalPikoPlatform
import kotlinx.coroutines.launch

@Composable
internal fun hasBrowseSectionMatches(searchQuery: String): Boolean {
    if (searchQuery.isBlank()) return true
    val sessionManager = LocalPikoServices.current.preferences
    val isNameParsingEnabled by sessionManager.nameParsingFlow.collectAsStateWithLifecycle(initialValue = true)
    fun match(vararg terms: String): Boolean = matchesSettingsQuery(searchQuery, *terms)

    val matchParse = match("文件名解析", "Parse file names", "ファイル名を解析", "파일 이름 분석", "檔案名稱解析")
    val matchHeuristic = isNameParsingEnabled && match("启发式折叠", "Smart grouping", "不要な項目を折りたたむ", "스마트 접기", "智慧摺疊")
    val matchBlur = match("缩略图防窥", "Blur thumbnails", "spoiler", "サムネイルをぼかす", "미리 보기 흐리게", "縮圖防窺")
    val matchFoldersFirst = match("文件夹置顶", "Folders on top", "sort", "フォルダーを上部に固定", "폴더 상단 고정", "資料夾置頂")
    val matchPerFolderSort = match("各文件夹独立排序", "Sort each folder separately", "フォルダーごとに並べ替え", "폴더별 정렬", "各資料夾獨立排序")
    val matchPlayQuality = match("播放画质", "Playback quality", "resolution", "再生画質", "재생 화질", "播放畫質")
    val matchHwDec = match("硬件解码", "Hardware decoding", "mpv", "hwdec", "ハードウェアデコード", "하드웨어 디코딩", "硬體解碼")
    val matchClipAuto = match("信息流自动续播", "Auto-extend feed clips", "clips", "feed", "フィード", "피드 클립", "資訊流自動續播")
    val matchExitConfirm = match("退出瀑布流与信息流时确认", "Confirm before exiting", "gallery", "clips", "feed", "瀑布流", "信息流", "退出确认", "終了時に確認", "종료 시 확인", "結束時確認")
    return matchParse || matchHeuristic || matchBlur || matchFoldersFirst || matchPerFolderSort ||
        matchPlayQuality || matchHwDec || matchClipAuto || matchExitConfirm
}

@Composable
internal fun hasSavingSectionMatches(searchQuery: String): Boolean {
    if (searchQuery.isBlank()) return true
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    fun match(vararg terms: String): Boolean = matchesSettingsQuery(searchQuery, *terms)

    val matchClipboard = match("自动检测剪贴板链接", "Detect clipboard links", "magnet", "clipboard", "クリップボード", "클립보드", "剪貼簿")
    val matchLinkAssoc = platform.linkAssociation != null &&
        match("磁力链接与种子文件", "Magnet links and torrent files", "torrent", "magnet", "マグネットリンク", "마그넷 링크", "磁力連結")
    val matchAppLinks = platform.openAppLinkSettings != null &&
        match("默认打开分享与深链", "Open shares and deep links by default", "deep link", "ディープリンク", "딥 링크", "深層連結")
    val matchSubtitles = match("保存配套字幕", "Save matching subtitles", "subtitle", "字幕", "자막")
    val matchCleanNames = match("自动修正名称", "Clean names automatically", "rename", "名前を自動修正", "이름 자동 수정", "自動修正名稱")
    val matchMetaTube = services.metaTube != null && match("MetaTube", "番号", "catalog", "刮削", "作品番号", "품번")
    return matchClipboard || matchLinkAssoc || matchAppLinks || matchSubtitles || matchCleanNames || matchMetaTube
}

@Composable
internal fun BrowseSettingsSection(
    searchQuery: String,
    firstSection: SettingsSection?,
    onSectionPositioned: (SettingsSection, Int) -> Unit,
) {
    val sessionManager = LocalPikoServices.current.preferences
    val scope = rememberCoroutineScope()

    val isSpoilerBlurEnabled by sessionManager.spoilerBlurFlow.collectAsStateWithLifecycle(
        initialValue = sessionManager.currentSnapshot.spoilerBlurEnabled,
    )
    val isHeuristicFilterEnabled by sessionManager.heuristicFilterFlow.collectAsStateWithLifecycle(initialValue = true)
    val isNameParsingEnabled by sessionManager.nameParsingFlow.collectAsStateWithLifecycle(initialValue = true)
    val foldersFirst by sessionManager.foldersFirstFlow.collectAsStateWithLifecycle(initialValue = true)
    val isPerFolderSortEnabled by sessionManager.perFolderSortFlow.collectAsStateWithLifecycle(initialValue = false)
    val isHardwareDecodingEnabled by sessionManager.hardwareDecodingFlow.collectAsStateWithLifecycle(initialValue = true)
    val isClipAutoExtendEnabled by sessionManager.clipAutoExtendFlow.collectAsStateWithLifecycle(initialValue = true)
    val skipFeedExitConfirm by sessionManager.skipFeedExitConfirmFlow.collectAsStateWithLifecycle(
        initialValue = sessionManager.currentSnapshot.skipFeedExitConfirm,
    )
    var showPlaybackQuality by remember { mutableStateOf(false) }
    val playbackMaxHeight by sessionManager.playbackMaxHeightFlow.collectAsStateWithLifecycle(
        initialValue = sessionManager.currentSnapshot.playbackMaxHeight,
    )

    if (showPlaybackQuality) {
        PlaybackQualityDialog(
            maxHeight = playbackMaxHeight,
            onSelect = { height -> if (height != null) scope.launch { sessionManager.setPlaybackMaxHeight(height) } },
            onDismiss = { showPlaybackQuality = false },
            showAutoOption = true,
        )
    }

    fun match(vararg terms: String): Boolean = matchesSettingsQuery(searchQuery, *terms)

    val matchParse = match("文件名解析", "Parse file names", "ファイル名を解析", "파일 이름 분석", "檔案名稱解析")
    val matchHeuristic = isNameParsingEnabled && match("启发式折叠", "Smart grouping", "不要な項目を折りたたむ", "스마트 접기", "智慧摺疊")
    val matchBlur = match("缩略图防窥", "Blur thumbnails", "spoiler", "サムネイルをぼかす", "미리 보기 흐리게", "縮圖防窺")
    val matchFoldersFirst = match("文件夹置顶", "Folders on top", "sort", "フォルダーを上部に固定", "폴더 상단 고정", "資料夾置頂")
    val matchPerFolderSort = match("各文件夹独立排序", "Sort each folder separately", "フォルダーごとに並べ替え", "폴더별 정렬", "各資料夾獨立排序")
    val matchFilesGroup = matchParse || matchHeuristic || matchBlur || matchFoldersFirst || matchPerFolderSort

    val matchPlayQuality = match("播放画质", "Playback quality", "resolution", "再生画質", "재생 화질", "播放畫質")
    val matchHwDec = match("硬件解码", "Hardware decoding", "mpv", "hwdec", "ハードウェアデコード", "하드웨어 디코딩", "硬體解碼")
    val matchClipAuto = match("信息流自动续播", "Auto-extend feed clips", "clips", "feed", "フィード", "피드 클립", "資訊流自動續播")
    val matchExitConfirm = match("退出瀑布流与信息流时确认", "Confirm before exiting", "gallery", "clips", "feed", "瀑布流", "信息流", "退出确认", "終了時に確認", "종료 시 확인", "結束時確認")
    val matchPlayGroup = matchPlayQuality || matchHwDec || matchClipAuto || matchExitConfirm

    if (matchFilesGroup || matchPlayGroup) {
        SettingsSectionBlock(
            section = SettingsSection.Browse,
            first = firstSection == SettingsSection.Browse,
            onPositioned = { onSectionPositioned(SettingsSection.Browse, it) },
        ) {
            if (matchFilesGroup) {
                SettingsGroup(tr("文件", "Files", "ファイル", "파일", "檔案")) {
                    if (matchParse) {
                        SettingsSwitchRow(
                            icon = Icons.Outlined.TextFields,
                            title = tr("文件名解析", "Parse file names", "ファイル名を解析", "파일 이름 분석", "檔案名稱解析"),
                            supporting = tr(
                                "按作品、分区与集数整理，标出发布组与清晰度",
                                "Organize by title, section and episode; show release groups and quality",
                                "作品、区分、話数で整理し、リリースグループと画質を表示",
                                "작품, 분류, 회차별로 정리하고 배포 그룹 및 화질 표시",
                                "依作品、分區與集數整理，標示發布群組與畫質",
                            ),
                            checked = isNameParsingEnabled,
                            onCheckedChange = { scope.launch { sessionManager.setNameParsingEnabled(it) } },
                        )
                    }
                    if (matchHeuristic) {
                        // 只在解析开着时有意义，关掉解析就收起，不留一行灰掉的开关
                        DependentRow(visible = isNameParsingEnabled) {
                            SettingsSwitchRow(
                                icon = Icons.Outlined.AutoFixHigh,
                                title = tr("启发式折叠", "Smart grouping", "不要な項目を折りたたむ", "스마트 접기", "智慧摺疊"),
                                supporting = tr(
                                    "收起广告、样片、说明文件等次要项",
                                    "Collapse ads, samples, readme files and other extras",
                                    "広告、サンプル、説明ファイルなどを折りたたむ",
                                    "광고, 샘플, 설명 파일 등 부가 항목 접기",
                                    "收合廣告、樣片、說明檔案等次要項目",
                                ),
                                checked = isHeuristicFilterEnabled,
                                onCheckedChange = { scope.launch { sessionManager.setHeuristicFilterEnabled(it) } },
                            )
                        }
                    }
                    if (matchBlur) {
                        SettingsSwitchRow(
                            icon = Icons.Outlined.VisibilityOff,
                            title = tr("缩略图防窥", "Blur thumbnails", "サムネイルをぼかす", "미리 보기 흐리게", "縮圖防窺"),
                            supporting = tr(
                                "模糊显示缩略图与封面",
                                "Blur thumbnails and covers",
                                "サムネイルとカバーをぼかして表示",
                                "미리 보기 및 표지 흐리게 표시",
                                "模糊顯示縮圖與封面",
                            ),
                            checked = isSpoilerBlurEnabled,
                            onCheckedChange = { scope.launch { sessionManager.setSpoilerBlurEnabled(it) } },
                        )
                    }
                    if (matchFoldersFirst) {
                        SettingsSwitchRow(
                            icon = Icons.AutoMirrored.Outlined.Sort,
                            title = tr("文件夹置顶", "Folders on top", "フォルダーを上部に固定", "폴더 상단 고정", "資料夾置頂"),
                            supporting = tr(
                                "关闭后文件夹与文件按所选顺序混合排列",
                                "Turn off to mix folders and files together in sort order",
                                "オフにするとフォルダーとファイルを混在して並べ替えます",
                                "끄면 폴더와 파일을 선택한 정렬 순서대로 함께 배열합니다",
                                "關閉後資料夾與檔案依所選順序混合排列",
                            ),
                            checked = foldersFirst,
                            onCheckedChange = { scope.launch { sessionManager.setFoldersFirst(it) } },
                        )
                    }
                    if (matchPerFolderSort) {
                        SettingsSwitchRow(
                            icon = Icons.AutoMirrored.Outlined.Sort,
                            title = tr("各文件夹独立排序", "Sort each folder separately", "フォルダーごとに並べ替え", "폴더별 정렬", "各資料夾獨立排序"),
                            supporting = tr(
                                "为每个文件夹分别保存排序方式",
                                "Save sort order independently for each folder",
                                "フォルダーごとに並び順を個別に保存します",
                                "폴더마다 정렬 방식을 개별적으로 저장합니다",
                                "為每個資料夾分別儲存排序方式",
                            ),
                            checked = isPerFolderSortEnabled,
                            onCheckedChange = { scope.launch { sessionManager.setPerFolderSort(it) } },
                        )
                    }
                }
            }
            if (matchPlayGroup) {
                SettingsGroup(tr("播放", "Play", "再生", "재생", "播放")) {
                    if (matchPlayQuality) {
                        SettingsNavigationRow(
                            icon = Icons.Outlined.HighQuality,
                            title = tr("播放画质", "Playback quality", "再生画質", "재생 화질", "播放畫質"),
                            supporting = playbackQualitySummary(playbackMaxHeight),
                            onClick = { showPlaybackQuality = true },
                            trailingIcon = null,
                        )
                    }
                    if (matchHwDec) {
                        SettingsSwitchRow(
                            icon = Icons.Outlined.Memory,
                            title = tr("硬件解码", "Hardware decoding", "ハードウェアデコード", "하드웨어 디코딩", "硬體解碼"),
                            supporting = tr(
                                "画面异常或卡顿时可关闭，改用软件解码",
                                "Disable to use software decoding if video artifacts or freezes occur",
                                "映像の乱れや停止が発生する場合は無効にしてソフトウェアデコードを使用します",
                                "화면 깨짐이나 멈춤이 발생하면 끄고 소프트웨어 디코딩을 사용하세요",
                                "畫面異常或停頓時可關閉，改用軟體解碼",
                            ),
                            checked = isHardwareDecodingEnabled,
                            onCheckedChange = { scope.launch { sessionManager.setHardwareDecoding(it) } },
                        )
                    }
                    if (matchClipAuto) {
                        SettingsSwitchRow(
                            icon = Icons.Outlined.SwipeVertical,
                            title = tr("信息流自动续播", "Auto-extend feed clips", "フィードの自動延長再生", "피드 클립 자동 연장", "資訊流自動續播"),
                            supporting = tr(
                                "30 秒片段播完后自动顺延播放当前视频后续内容；关闭后循环播放当前片段",
                                "Continue playing subsequent segments of the video after 30 seconds; loop the current clip when disabled",
                                "30秒の再生後に同じ動画の続きを自動再生します。無効時は現在のクリップをループ再生します",
                                "30초 재생 후 현재 동영상의 다음 구간을 이어서 재생하며, 끄면 현재 클립을 반복 재생합니다",
                                "30 秒片段播完後自動順延播放目前影片後續內容；關閉後循環播放目前片段",
                            ),
                            checked = isClipAutoExtendEnabled,
                            onCheckedChange = { scope.launch { sessionManager.setClipAutoExtend(it) } },
                        )
                    }
                    if (matchExitConfirm) {
                        SettingsSwitchRow(
                            icon = Icons.Outlined.Close,
                            title = tr("退出瀑布流与信息流时确认", "Confirm before exiting Gallery & Clips", "ギャラリーやフィード終了時に確認", "갤러리 및 피드 종료 시 확인", "結束瀑布流與資訊流時確認"),
                            supporting = tr(
                                "关闭后退出瀑布流或信息流不再弹窗提醒",
                                "Exit Gallery or Clips directly without a confirmation dialog when off",
                                "オフにすると確認ダイアログなしで直接終了します",
                                "끄면 확인 창 없이 바로 종료합니다",
                                "關閉後結束瀑布流或資訊流不再跳出確認視窗",
                            ),
                            checked = !skipFeedExitConfirm,
                            onCheckedChange = { confirm -> scope.launch { sessionManager.setSkipFeedExitConfirm(!confirm) } },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 存进网盘时的处理：链接由谁打开、带不带字幕、存成什么名字。
 * 配套字幕只在添加链接时起作用，与本机下载无关。
 */
@Composable
internal fun SavingSettingsSection(
    searchQuery: String,
    firstSection: SettingsSection?,
    onSectionPositioned: (SettingsSection, Int) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val sessionManager = services.preferences
    val scope = rememberCoroutineScope()

    val isNameParsingEnabled by sessionManager.nameParsingFlow.collectAsStateWithLifecycle(initialValue = true)
    val isBundleSubtitlesEnabled by sessionManager.bundleSubtitlesFlow.collectAsStateWithLifecycle(initialValue = true)
    val isAutoCleanNamesEnabled by sessionManager.autoCleanNamesFlow.collectAsStateWithLifecycle(initialValue = false)

    val metaTube = services.metaTube
    val metaTubeUrl by sessionManager.metaTubeUrlFlow.collectAsStateWithLifecycle(initialValue = "")
    var showMetaTubeDialog by remember { mutableStateOf(false) }

    val linkAssociation = platform.linkAssociation
    var linkAssociationState by remember { mutableStateOf(LinkAssociationState.Unavailable) }
    val isWindowFocused = LocalWindowInfo.current.isWindowFocused
    if (linkAssociation != null) {
        // 默认应用在系统设置里改，改完切回来窗口重新获得焦点，借此刷新
        LaunchedEffect(linkAssociation, isWindowFocused) {
            if (isWindowFocused) linkAssociationState = linkAssociation.state()
        }
    }

    if (showMetaTubeDialog && metaTube != null) {
        // 令牌在打开对话框时才读：桌面端从系统保管处取，不必在设置页一打开就碰钥匙串
        val token by sessionManager.metaTubeTokenFlow.collectAsStateWithLifecycle(initialValue = null)
        token?.let { current ->
            MetaTubeSettingsDialog(
                service = metaTube,
                currentUrl = metaTubeUrl,
                currentToken = current,
                onSave = { url, newToken ->
                    showMetaTubeDialog = false
                    scope.launch {
                        sessionManager.setMetaTubeUrl(url)
                        sessionManager.setMetaTubeToken(newToken)
                    }
                },
                onDismiss = { showMetaTubeDialog = false },
            )
        }
    }

    fun match(vararg terms: String): Boolean = matchesSettingsQuery(searchQuery, *terms)

    val matchClipboard = match("自动检测剪贴板链接", "Detect clipboard links", "magnet", "clipboard", "クリップボード", "클립보드", "剪貼簿")
    val matchLinkAssoc = linkAssociation != null &&
        match("磁力链接与种子文件", "Magnet links and torrent files", "torrent", "magnet", "マグネットリンク", "마그넷 링크", "磁力連結")
    val matchAppLinks = platform.openAppLinkSettings != null &&
        match("默认打开分享与深链", "Open shares and deep links by default", "deep link", "ディープリンク", "딥 링크", "深層連結")
    val matchSubtitles = match("保存配套字幕", "Save matching subtitles", "subtitle", "字幕", "자막")
    val matchCleanNames = match("自动修正名称", "Clean names automatically", "rename", "名前を自動修正", "이름 자동 수정", "自動修正名稱")
    val matchMetaTube = metaTube != null && match("MetaTube", "番号", "catalog", "刮削", "作品番号", "품번")

    if (matchClipboard || matchLinkAssoc || matchAppLinks || matchSubtitles || matchCleanNames || matchMetaTube) {
        SettingsSectionBlock(
            section = SettingsSection.Saving,
            first = firstSection == SettingsSection.Saving,
            onPositioned = { onSectionPositioned(SettingsSection.Saving, it) },
        ) {
            SettingsGroup(null) {
                if (matchClipboard) {
                    val detectClipboard by sessionManager.autoDetectClipboardLinksFlow.collectAsStateWithLifecycle(initialValue = false)
                    SettingsSwitchRow(
                        icon = Icons.Outlined.ContentPaste,
                        title = tr("自动检测剪贴板链接", "Detect clipboard links", "クリップボードのリンクを検出", "클립보드 링크 자동 감지", "自動偵測剪貼簿連結"),
                        supporting = tr(
                            "回到前台时提示磁力或 PikPak 分享链接，确认后解析",
                            "Offer to open magnet or PikPak share links when returning to Piko",
                            "復帰時にマグネットやPikPak共有リンクを検出し、確認後に解析",
                            "앱으로 돌아오면 마그넷 또는 PikPak 공유 링크를 감지하고 확인 후 분석",
                            "回到前景時提示磁力或 PikPak 分享連結，確認後解析",
                        ),
                        checked = detectClipboard,
                        onCheckedChange = { scope.launch { sessionManager.setAutoDetectClipboardLinks(it) } },
                    )
                }
                if (matchLinkAssoc && linkAssociation != null) {
                    LinkAssociationRow(
                        association = linkAssociation,
                        state = linkAssociationState,
                        onStateChange = { linkAssociationState = it },
                        onFailure = { message -> scope.launch { snackbarHostState.showSnackbar(message, withDismissAction = true) } },
                    )
                }
                if (matchAppLinks && platform.openAppLinkSettings != null) {
                    SettingsNavigationRow(
                        icon = Icons.Outlined.Link,
                        title = tr("默认打开分享与深链", "Open shares and deep links by default", "共有とディープリンクを既定で開く", "공유 및 딥 링크 기본 열기", "預設開啟分享與深層連結"),
                        supporting = tr(
                            "在系统设置中添加支持的链接域名，点击 PikPak 分享或跳转链接时直接用 Piko 打开",
                            "Enable supported link domains in system settings to open PikPak shares and deep links in Piko",
                            "システム設定で対応ドメインを有効にすると、PikPakの共有やディープリンクをPikoで開けます",
                            "시스템 설정에서 지원 도메인을 추가하면 PikPak 공유 및 딥 링크를 Piko에서 바로 엽니다",
                            "在系統設定中新增支援的連結網域，點選 PikPak 分享或跳轉連結時直接用 Piko 開啟",
                        ),
                        onClick = platform.openAppLinkSettings!!,
                    )
                }
                if (matchSubtitles) {
                    // 这一项靠文件名解析认出字幕。解析在另一类，关掉时留着并写明原因，收起的话人找不到它去了哪
                    SettingsSwitchRow(
                        icon = Icons.Outlined.Subtitles,
                        title = tr("保存配套字幕", "Save matching subtitles", "対応する字幕も保存", "관련 자막 함께 저장", "儲存對應字幕"),
                        supporting = if (isNameParsingEnabled) {
                            tr("保存视频时一并保存外挂字幕", "Save external subtitles with videos", "動画の保存時に外部字幕も保存", "동영상 저장 시 외부 자막 함께 저장", "儲存影片時一併儲存外掛字幕")
                        } else {
                            tr("需先开启文件名解析", "Enable file name parsing first", "先にファイル名の解析を有効にしてください", "먼저 파일 이름 분석을 켜 주세요", "需先開啟檔案名稱解析")
                        },
                        checked = isBundleSubtitlesEnabled,
                        onCheckedChange = { scope.launch { sessionManager.setBundleSubtitlesEnabled(it) } },
                        enabled = isNameParsingEnabled,
                    )
                }
                if (matchCleanNames) {
                    SettingsSwitchRow(
                        icon = Icons.Outlined.CleaningServices,
                        title = tr("自动修正名称", "Clean names automatically", "名前を自動修正", "이름 자동 수정", "自動修正名稱"),
                        supporting = tr(
                            "新建或重命名时自动移除不受支持的字符",
                            "Automatically remove unsupported characters when creating or renaming",
                            "新規作成や名前変更時に使用できない文字を自動除去します",
                            "새로 만들거나 이름을 변경할 때 지원하지 않는 문자를 자동으로 제거합니다",
                            "新增或重新命名時自動移除不支援的字元",
                        ),
                        checked = isAutoCleanNamesEnabled,
                        onCheckedChange = { scope.launch { sessionManager.setAutoCleanNamesEnabled(it) } },
                    )
                }
                if (matchMetaTube && metaTube != null) {
                    SettingsNavigationRow(
                        icon = Icons.Outlined.TravelExplore,
                        title = "MetaTube",
                        supporting = metaTubeUrl.ifBlank {
                            tr(
                                "未配置，按番号命名时不包含片名",
                                "Not configured; titles omitted when naming by catalog code",
                                "未設定（作品番号での命名時にタイトルを含めません）",
                                "미설정 (품번으로 이름 정리 시 제목 제외)",
                                "未設定，依番號命名時不包含片名",
                            )
                        },
                        onClick = { showMetaTubeDialog = true },
                        trailingIcon = null,
                    )
                }
            }
        }
    }
}

/**
 * 磁力链接与种子文件的默认打开方式：说明眼下是谁在打开，行尾是对应的按钮。
 * 整行不可点：两个方向的动作都有后果，放在明写着的按钮上。
 */
@Composable
private fun LinkAssociationRow(
    association: LinkAssociation,
    state: LinkAssociationState,
    onStateChange: (LinkAssociationState) -> Unit,
    onFailure: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val isDefault = state == LinkAssociationState.Default
    val isRegistered = isDefault || state == LinkAssociationState.Registered
    val confirmHint = if (association.needsSystemConfirmation) {
        tr("，设为默认需到系统设置确认", "; confirm the default app in system settings", "。既定にするにはシステム設定で確認が必要です", ". 기본 앱 설정은 시스템 설정에서 확인 필요", "，設為預設需至系統設定確認")
    } else {
        ""
    }
    val act: (Boolean) -> Unit = { register ->
        busy = true
        scope.launch {
            val done = if (register) association.register() else association.unregister()
            if (!done) {
                onFailure(
                    if (register) {
                        tr("无法设为默认打开方式", "Unable to set as default app", "既定のアプリに設定できません", "기본 앱으로 설정할 수 없습니다", "無法設為預設開啟方式")
                    } else {
                        tr("无法取消关联", "Unable to remove association", "関連付けを解除できません", "연결을 해제할 수 없습니다", "無法取消關聯")
                    },
                )
            }
            // 不经系统设置的平台当场就改好了，窗口不会失焦再回来，这里重读一次
            onStateChange(association.state())
            busy = false
        }
    }
    val enabled = state != LinkAssociationState.Unavailable && !busy
    SettingsRow(
        title = tr("磁力链接与种子文件", "Magnet links and torrent files", "マグネットリンクとトレントファイル", "마그넷 링크 및 토렌트 파일", "磁力連結與種子檔案"),
        icon = Icons.Outlined.Link,
        supporting = when {
            state == LinkAssociationState.Unavailable -> tr("仅安装版与便携版可设为默认打开方式", "Only installed and portable builds can be the default app", "インストール版とポータブル版のみ既定のアプリに設定できます", "설치 버전과 포터블 버전만 기본 앱으로 설정 가능", "僅安裝版與可攜版可設為預設開啟方式")
            isDefault && !association.canUnregister -> tr("当前由 Piko 打开；如需更改，请将其他应用设为默认", "Opened by Piko. Set another app as default to change", "Pikoで開きます。変更するには他のアプリを既定に設定してください", "현재 Piko에서 엽니다. 변경하려면 다른 앱을 기본 앱으로 설정하세요", "目前由 Piko 開啟；如需變更，請將其他應用程式設為預設")
            isDefault -> tr("由 Piko 打开", "Opened by Piko", "Pikoで開く", "Piko에서 열기", "由 Piko 開啟")
            isRegistered -> tr("已登记，未设为默认$confirmHint", "Registered; not the default$confirmHint", "登録済み、既定は未設定$confirmHint", "등록됨, 기본 앱 아님$confirmHint", "已登錄，未設為預設$confirmHint")
            else -> tr("由其他应用打开$confirmHint", "Opened by another app$confirmHint", "ほかのアプリで開く$confirmHint", "다른 앱에서 열기$confirmHint", "由其他應用程式開啟$confirmHint")
        },
        trailing = {
            if (isRegistered && association.canUnregister) {
                SettingsRowButton(
                    tr("取消关联", "Remove association", "関連付けを解除", "연결 해제", "取消關聯"),
                    onClick = { act(false) },
                    enabled = enabled,
                    primary = false,
                )
            }
            if (!isDefault) {
                SettingsRowButton(
                    tr("设为默认", "Set as default", "既定に設定", "기본으로 설정", "設為預設"),
                    onClick = { act(true) },
                    enabled = enabled,
                )
            }
        },
    )
}
