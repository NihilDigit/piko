package dev.piko.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Minimize
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.tr
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.MotionLevel
import dev.piko.ui.theme.SeedTheme
import dev.piko.ui.theme.ThemeMode
import dev.piko.ui.theme.effectiveSeed
import dev.piko.ui.theme.isDark
import dev.piko.ui.theme.isEInk
import kotlinx.coroutines.launch

@Composable
internal fun hasAppearanceSectionMatches(searchQuery: String): Boolean {
    if (searchQuery.isBlank()) return true
    val sessionManager = LocalPikoServices.current.preferences
    val platform = LocalPikoPlatform.current
    val appearance = LocalAppearance.current
    val appLanguage by sessionManager.appLanguageFlow.collectAsStateWithLifecycle(initialValue = AppLanguage.SYSTEM)
    val systemLanguageLabel = systemLanguageOptionLabel()
    fun match(vararg terms: String): Boolean = matchesSettingsQuery(searchQuery, *terms)

    val matchLang = match("语言", "Language", "言語", "언어", "語言", systemLanguageLabel, appLanguage.nativeLabel)
    val matchEink = match("水墨屏模式", "水墨屏", "水墨螢幕", "E-Ink mode", "e-ink", "eink", "电子墨水", "墨水屏", "monochrome", "黑白", "E-Inkモード", "E-Ink 모드")
    val matchThemeMode = match("深色模式", "Theme mode", "dark", "light", "system", "テーマモード", "테마 모드")
    val matchPureBlack = !appearance.isEInk && appearance.mode != ThemeMode.LIGHT &&
        match("纯黑深色模式", "Pure black", "OLED", "amoled", "true black", "ピュアブラック", "순수 블랙", "純黑")
    val matchThemeColor = match("主题色", "Theme color", "dynamic", "accent", "color", "テーマカラー", "테마 색상", "主題色", appearance.effectiveSeed?.label.orEmpty())
    val matchStartup = platform.launchAtStartup != null &&
        match("开机自动启动", "Launch at startup", "autostart", "Windows", "ログイン時に起動", "시작 시 실행", "開機自動啟動")
    val matchTray = platform.closeToTray != null &&
        match("关闭时最小化到托盘", "Minimize to tray on close", "tray", "トレイ", "트레이", "系統匣")
    val matchCompactBar = platform.compactTitleBar != null &&
        match("紧凑标题栏", "Compact title bar", "titlebar", "タイトルバー", "제목 표시줄", "精簡標題列")
    val matchMotion = match("动画程度", "减少动画", "减弱", "标准", "灵动", "Motion", "Reduce motion", "Reduced", "Standard", "Expressive", "animation", "アニメーション", "애니메이션", "減少動畫", "動畫程度")
    val matchPredictiveBack = platform.supportsPredictiveBack &&
        match("预测性返回手势", "预测性返回", "Predictive back", "侧滑返回", "エッジスワイプ", "예측형 뒤로", "預測性返回手勢")
    return matchLang || matchEink || matchThemeMode || matchPureBlack || matchThemeColor ||
        matchStartup || matchTray || matchCompactBar || matchMotion || matchPredictiveBack
}

internal fun hasCustomizeSectionMatches(searchQuery: String): Boolean {
    if (searchQuery.isBlank()) return true
    val matchDriveAppearance = matchesSettingsQuery(
        searchQuery,
        "自定义", "Customize", "カスタマイズ", "사용자 지정", "自訂", "文件外观", "File appearance",
        "card", "grid", "list", "gallery", "列表", "网格", "瀑布流", "角标", "文件夹样式", "ファイル外観", "파일 모양", "檔案外觀",
    )
    val matchPlayerCustom = matchesSettingsQuery(
        searchQuery,
        "自定义", "Customize", "カスタマイズ", "사용자 지정", "自訂", "播放器外观", "播放器自定义",
        "Player appearance", "Player customization", "layout", "gesture", "手势", "听音频", "プレーヤー", "플레이어", "播放器外觀", "播放器自訂",
    )
    return matchDriveAppearance || matchPlayerCustom
}

private fun systemLanguageOptionLabel(): String =
    tr("跟随系统", "System", "システムに従う", "시스템 설정 따르기", "跟隨系統")

@Composable
internal fun AppearanceSettingsSection(
    searchQuery: String,
    firstSection: SettingsSection?,
    onSectionPositioned: (SettingsSection, Int) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val sessionManager = LocalPikoServices.current.preferences
    val platform = LocalPikoPlatform.current
    val appearance = LocalAppearance.current
    val motionScale = platform.motionScale
    val scope = rememberCoroutineScope()

    val appLanguage by sessionManager.appLanguageFlow.collectAsStateWithLifecycle(initialValue = AppLanguage.SYSTEM)
    val predictiveBack by sessionManager.predictiveBackFlow.collectAsStateWithLifecycle(
        initialValue = sessionManager.currentSnapshot.predictiveBack,
    )
    var showLanguage by remember { mutableStateOf(false) }
    val systemLanguageLabel = systemLanguageOptionLabel()

    if (showLanguage) {
        SettingsChoiceDialog(
            icon = Icons.Outlined.Language,
            title = tr("语言", "Language", "言語", "언어", "語言"),
            onDismiss = { showLanguage = false },
        ) {
            AppLanguage.entries.forEach { language ->
                SettingsChoiceOption(
                    title = if (language == AppLanguage.SYSTEM) systemLanguageLabel else language.nativeLabel,
                    selected = appLanguage == language,
                    onClick = {
                        scope.launch { sessionManager.setAppLanguage(language) }
                        showLanguage = false
                    },
                )
            }
        }
    }

    fun match(vararg terms: String): Boolean = matchesSettingsQuery(searchQuery, *terms)

    val matchLang = match("语言", "Language", "言語", "언어", "語言", systemLanguageLabel, appLanguage.nativeLabel)
    val matchEink = match("水墨屏模式", "水墨屏", "水墨螢幕", "E-Ink mode", "e-ink", "eink", "电子墨水", "墨水屏", "monochrome", "黑白", "E-Inkモード", "E-Ink 모드")
    val matchThemeMode = match("深色模式", "Theme mode", "dark", "light", "system", "テーマモード", "테마 모드")
    val matchPureBlack = !appearance.isEInk && appearance.mode != ThemeMode.LIGHT &&
        match("纯黑深色模式", "Pure black", "OLED", "amoled", "true black", "ピュアブラック", "순수 블랙", "純黑")
    val matchThemeColor = match("主题色", "Theme color", "dynamic", "accent", "color", "テーマカラー", "테마 색상", "主題色", appearance.effectiveSeed?.label.orEmpty())
    val matchStartup = platform.launchAtStartup != null &&
        match("开机自动启动", "Launch at startup", "autostart", "Windows", "ログイン時に起動", "시작 시 실행", "開機自動啟動")
    val matchTray = platform.closeToTray != null &&
        match("关闭时最小化到托盘", "Minimize to tray on close", "tray", "トレイ", "트레이", "系統匣")
    val matchCompactBar = platform.compactTitleBar != null &&
        match("紧凑标题栏", "Compact title bar", "titlebar", "タイトルバー", "제목 표시줄", "精簡標題列")
    val matchMotion = match("动画程度", "减少动画", "减弱", "标准", "灵动", "Motion", "Reduce motion", "Reduced", "Standard", "Expressive", "animation", "アニメーション", "애니메이션", "減少動畫", "動畫程度")
    val matchPredictiveBack = platform.supportsPredictiveBack &&
        match("预测性返回手势", "预测性返回", "Predictive back", "侧滑返回", "エッジスワイプ", "예측형 뒤로", "預測性返回手勢")

    if (matchLang || matchEink || matchThemeMode || matchPureBlack || matchThemeColor ||
        matchStartup || matchTray || matchCompactBar || matchMotion || matchPredictiveBack
    ) {
        SettingsSectionBlock(
            section = SettingsSection.Appearance,
            first = firstSection == SettingsSection.Appearance,
            onPositioned = { onSectionPositioned(SettingsSection.Appearance, it) },
        ) {
            SettingsGroup(null) {
                if (matchLang) {
                    SettingsNavigationRow(
                        icon = Icons.Outlined.Language,
                        title = tr("语言", "Language", "言語", "언어", "語言"),
                        supporting = if (appLanguage == AppLanguage.SYSTEM) systemLanguageLabel else appLanguage.nativeLabel,
                        onClick = { showLanguage = true },
                    )
                }
                if (matchEink) {
                    SettingsSwitchRow(
                        icon = Icons.Outlined.MenuBook,
                        title = tr("水墨屏模式", "E-Ink mode", "E-Inkモード", "E-Ink 모드", "水墨螢幕模式"),
                        supporting = tr(
                            "高对比度纯黑白界面与清晰描边，自动关闭动画，仅对当前设备生效",
                            "High-contrast monochrome with crisp borders and no animations, for this device only",
                            "高コントラストの白黒表示と輪郭線、アニメーションを自動オフ（この端末のみ）",
                            "고대비 흑백 화면과 선명한 테두리, 애니메이션 자동 끄기 (이 기기 전용)",
                            "高對比純黑白介面與清晰描邊，自動關閉動畫，僅對目前裝置生效",
                        ),
                        checked = appearance.isEInk,
                        onCheckedChange = { scope.launch { sessionManager.setEinkMode(it) } },
                    )
                }
                if (matchThemeMode) {
                    ThemeModeRow(
                        mode = appearance.mode,
                        enabled = !appearance.isEInk,
                        supporting = if (appearance.isEInk) {
                            tr(
                                "水墨屏模式下固定为高对比度白底黑字",
                                "Fixed to high-contrast monochrome in E-Ink mode",
                                "E-Inkモードでは高コントラストの白黒表示に固定されます",
                                "E-Ink 모드에서는 고대비 흑백 화면으로 고정됩니다",
                                "水墨螢幕模式下固定為高對比白底黑字",
                            )
                        } else {
                            null
                        },
                        onModeChange = { scope.launch { sessionManager.setThemeMode(it.name) } },
                    )
                }
                if (matchPureBlack) {
                    DependentRow(visible = !appearance.isEInk && appearance.mode != ThemeMode.LIGHT) {
                        SettingsSwitchRow(
                            icon = Icons.Outlined.Contrast,
                            title = tr("纯黑深色模式", "Pure black (OLED)", "ピュアブラック（OLED）", "순수 블랙 (OLED)", "純黑深色模式"),
                            supporting = tr(
                                "深色模式下使用纯黑背景以节省 OLED 屏幕功耗",
                                "Use true black surfaces in dark theme for OLED displays",
                                "ダークテーマで完全な黒背景を使用します（OLED向け）",
                                "다크 테마에서 OLED 화면용 순수 블랙 배경 사용",
                                "深色模式下使用純黑背景以節省 OLED 螢幕功耗",
                            ),
                            checked = appearance.pureBlack,
                            onCheckedChange = { scope.launch { sessionManager.setPureBlack(it) } },
                        )
                    }
                }
                if (matchThemeColor) {
                    ThemeColorRow(
                        appearance = appearance,
                        onSeedChange = { scope.launch { sessionManager.setThemeSeed(it?.name) } },
                    )
                }
                if (matchStartup) {
                    platform.launchAtStartup?.let { startup ->
                        var enabled by remember { mutableStateOf(false) }
                        var busy by remember { mutableStateOf(true) }
                        val focused = LocalWindowInfo.current.isWindowFocused
                        LaunchedEffect(startup, focused) {
                            if (focused) {
                                enabled = startup.isEnabled()
                                busy = false
                            }
                        }
                        SettingsSwitchRow(
                            icon = Icons.Outlined.PowerSettingsNew,
                            title = tr("开机自动启动", "Launch at startup", "ログイン時に起動", "시작 시 실행", "開機自動啟動"),
                            supporting = tr("登录 Windows 后打开 Piko", "Open Piko after signing in to Windows", "Windowsへのログイン時にPikoを開く", "Windows 로그인 후 Piko 실행", "登入 Windows 後開啟 Piko"),
                            checked = enabled,
                            onCheckedChange = { value ->
                                if (!busy) {
                                    scope.launch {
                                        busy = true
                                        if (!startup.setEnabled(value)) {
                                            snackbarHostState.showSnackbar(
                                                tr("无法更改开机启动设置", "Unable to change startup setting", "起動設定を変更できません", "시작 설정을 변경할 수 없습니다", "無法變更開機啟動設定"),
                                            )
                                        }
                                        enabled = startup.isEnabled()
                                        busy = false
                                    }
                                }
                            },
                        )
                    }
                }
                if (matchTray) {
                    platform.closeToTray?.let { toggle ->
                        val enabled by toggle.enabled.collectAsState()
                        SettingsSwitchRow(
                            icon = Icons.Outlined.Minimize,
                            title = tr("关闭时最小化到托盘", "Minimize to tray on close", "閉じるときにトレイへ最小化", "닫을 때 트레이로 최소화", "關閉時最小化至系統匣"),
                            supporting = tr(
                                "点击关闭按钮时最小化至系统托盘，不退出应用",
                                "Minimize to the system tray instead of quitting when clicking Close",
                                "閉じるボタンをクリックした際、終了せずにシステムトレイへ格納します",
                                "닫기 버튼을 누를 때 종료하지 않고 시스템 트레이로 최소화합니다",
                                "點擊關閉按鈕時最小化至系統匣，不結束應用程式",
                            ),
                            checked = enabled,
                            onCheckedChange = toggle::set,
                        )
                    }
                }
                if (matchCompactBar) {
                    platform.compactTitleBar?.let { compactTitleBar ->
                        val compact by compactTitleBar.enabled.collectAsState()
                        SettingsSwitchRow(
                            icon = Icons.Outlined.WebAsset,
                            title = tr("紧凑标题栏", "Compact title bar", "コンパクトなタイトルバー", "간결한 제목 표시줄", "精簡標題列"),
                            supporting = tr(
                                "窗口按钮并入界面右上角",
                                "Integrate window controls into the top right corner",
                                "ウィンドウボタンを画面右上に統合",
                                "창 버튼을 화면 오른쪽 위에 통합",
                                "視窗按鈕整合至介面右上角",
                            ),
                            checked = compact,
                            onCheckedChange = compactTitleBar::set,
                        )
                    }
                }
                if (matchMotion) {
                    val selectedMotion = if (appearance.isEInk) {
                        MotionLevel.Reduced
                    } else {
                        MotionLevel.fromPreference(appearance.motionPreference, platform.motionStyle)
                    }
                    MotionLevelRow(
                        selected = selectedMotion,
                        enabled = !appearance.isEInk,
                        supporting = when {
                            appearance.isEInk -> tr("水墨屏模式已自动关闭过渡动画", "Transitions are disabled automatically in E-Ink mode", "E-Inkモードではアニメーションが自動的に無効になります", "E-Ink 모드에서는 전환 애니메이션이 자동으로 꺼집니다", "水墨螢幕模式已自動關閉轉場動畫")
                            motionScale.systemScale == 0f -> tr("系统已关闭动画", "Animations disabled by system", "システムでアニメーションが無効です", "시스템에서 애니메이션을 껐습니다", "系統已關閉動畫")
                            else -> selectedMotion.subtitle
                        },
                        onSelect = { level -> scope.launch { sessionManager.setMotionPreference(level.name) } },
                    )
                }
                if (matchPredictiveBack) {
                    SettingsSwitchRow(
                        icon = Icons.Outlined.Gesture,
                        title = tr("预测性返回手势", "Predictive back gesture", "予測型「戻る」ジェスチャー", "예측형 뒤로 제스처", "預測性返回手勢"),
                        supporting = tr(
                            "侧滑返回时实时预览上一页过渡动画",
                            "Preview the previous screen while swiping back",
                            "画面端からのスワイプ時に前の画面の遷移をリアルタイムでプレビュー",
                            "측면 스와이프로 뒤로 갈 때 이전 화면 전환을 실시간 미리 보기",
                            "側滑返回時即時預覽上一頁轉場動畫",
                        ),
                        checked = predictiveBack,
                        onCheckedChange = { scope.launch { sessionManager.setPredictiveBack(it) } },
                    )
                }
            }
        }
    }
}

@Composable
internal fun CustomizeSettingsSection(
    searchQuery: String,
    firstSection: SettingsSection?,
    onSectionPositioned: (SettingsSection, Int) -> Unit,
    onOpenDriveAppearanceCustomization: () -> Unit,
    onOpenPlayerCustomization: () -> Unit,
) {
    val matchDriveAppearance = matchesSettingsQuery(
        searchQuery,
        "自定义", "Customize", "カスタマイズ", "사용자 지정", "自訂", "文件外观", "File appearance",
        "card", "grid", "list", "gallery", "列表", "网格", "瀑布流", "角标", "文件夹样式", "ファイル外観", "파일 모양", "檔案外觀",
    )
    val matchPlayerCustom = matchesSettingsQuery(
        searchQuery,
        "自定义", "Customize", "カスタマイズ", "사용자 지정", "自訂", "播放器外观", "播放器自定义",
        "Player appearance", "Player customization", "layout", "gesture", "手势", "听音频", "プレーヤー", "플레이어", "播放器外觀", "播放器自訂",
    )
    if (matchDriveAppearance || matchPlayerCustom) {
        SettingsSectionBlock(
            section = SettingsSection.Customize,
            first = firstSection == SettingsSection.Customize,
            onPositioned = { onSectionPositioned(SettingsSection.Customize, it) },
        ) {
            SettingsGroup(null) {
                if (matchDriveAppearance) {
                    SettingsNavigationRow(
                        icon = Icons.Outlined.GridView,
                        title = tr("文件外观", "File appearance", "ファイル外観", "파일 모양", "檔案外觀"),
                        supporting = tr(
                            "列表、网格与瀑布流的文件夹样式、角标与详情字段",
                            "Folder style, corner badges, and detail fields across views",
                            "リスト、グリッド、ギャラリーのフォルダー外観とバッジ",
                            "목록, 격자, 갤러리의 폴더 스타일, 배지 및 상세 필드",
                            "清單、網格與瀑布流的資料夾樣式、角標與詳情欄位",
                        ),
                        onClick = onOpenDriveAppearanceCustomization,
                    )
                }
                if (matchPlayerCustom) {
                    SettingsNavigationRow(
                        icon = Icons.Outlined.Tune,
                        title = tr("播放器外观", "Player appearance", "プレーヤー外観", "플레이어 모양", "播放器外觀"),
                        supporting = tr(
                            "横竖屏视频、信息流、听音频与手势",
                            "Video, clips, audio listen mode, and gestures",
                            "動画、フィード、音声再生とジェスチャー",
                            "동영상, 피드, 오디오 듣기 및 제스처",
                            "橫直螢幕影片、資訊流、聽音訊與手勢",
                        ),
                        onClick = onOpenPlayerCustomization,
                    )
                }
            }
        }
    }
}

/** 深色模式三选一。放在行尾（照 Windows 设置的下拉位置）；手机竖握时行尾放不下，挪到标题下方占满一行。 */
@Composable
private fun ThemeModeRow(
    mode: ThemeMode,
    onModeChange: (ThemeMode) -> Unit,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    val style = settingsStyle()
    val choice: @Composable (Boolean) -> Unit = { fill ->
        SettingsSegmentedChoice(
            options = ThemeMode.entries,
            selected = mode,
            label = { it.label },
            onSelect = onModeChange,
            fill = fill,
            enabled = enabled,
        )
    }
    SettingsRow(
        title = tr("深色模式", "Theme mode", "テーマモード", "테마 모드", "深色模式"),
        icon = Icons.Outlined.DarkMode,
        supporting = supporting,
        enabled = enabled,
        trailing = if (style.trailingChoices) ({ choice(false) }) else null,
        below = if (style.trailingChoices) null else ({ choice(true) }),
    )
}

@Composable
private fun MotionLevelRow(
    selected: MotionLevel,
    onSelect: (MotionLevel) -> Unit,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    val style = settingsStyle()
    val choice: @Composable (Boolean) -> Unit = { fill ->
        SettingsSegmentedChoice(
            options = MotionLevel.entries,
            selected = selected,
            label = { it.label },
            onSelect = onSelect,
            fill = fill,
            enabled = enabled,
        )
    }
    SettingsRow(
        title = tr("动画程度", "Motion", "アニメーション", "애니메이션 강도", "動畫程度"),
        icon = Icons.Outlined.Animation,
        supporting = supporting,
        enabled = enabled,
        trailing = if (style.trailingChoices) ({ choice(false) }) else null,
        below = if (style.trailingChoices) null else ({ choice(true) }),
    )
}

/**
 * 主题色：系统取色加内置彩色主题，一排圆形色块，选中的打勾，名字写在标题下方。
 * 水墨屏模式已独立为外观顶部的设备本地开关，此处不再混排黑白圆点，开启水墨屏时整行置灰并说明原因。
 */
@Composable
private fun ThemeColorRow(appearance: Appearance, onSeedChange: (SeedTheme?) -> Unit) {
    val enabled = !appearance.isEInk
    val dark = appearance.isDark()
    val platform = LocalPikoPlatform.current
    val style = settingsStyle()
    // 色块画在触控区正中，触控区比色块宽出的一半是看不见的边。整排往前挪这一截，第一个色块的左缘才与标题文字齐
    val swatchInset = (style.swatchTarget - style.swatchSize) / 2
    SettingsRow(
        title = tr("主题色", "Theme color", "テーマカラー", "테마 색상", "主題色"),
        icon = Icons.Outlined.Palette,
        supporting = if (appearance.isEInk) {
            tr(
                "水墨屏模式已接管界面配色",
                "Overridden by E-Ink mode",
                "E-Inkモードが配色を上書きしています",
                "E-Ink 모드가 색상을 대체하고 있습니다",
                "水墨螢幕模式已接管介面配色",
            )
        } else {
            appearance.effectiveSeed?.label ?: tr("系统取色", "System color", "システムの色", "시스템 색상", "系統取色")
        },
        enabled = enabled,
        below = {
            val swatchScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .offset(x = -swatchInset)
                    .alpha(if (enabled) 1f else 0.38f)
                    .selectableGroup()
                    .verticalWheelScrollsRow(swatchScroll)
                    .horizontalScroll(swatchScroll),
            ) {
                if (platform.supportsDynamicColor) {
                    val scheme = platform.dynamicColorScheme(dark)
                    ColorSwatch(
                        scheme = scheme,
                        label = tr("系统取色", "System color", "システムの色", "시스템 색상", "系統取色"),
                        selected = appearance.seed == null,
                        enabled = enabled,
                        idleIcon = Icons.Outlined.Wallpaper,
                        onClick = { onSeedChange(null) },
                    )
                }
                SeedTheme.colorEntries.forEach { theme ->
                    val scheme = if (dark) theme.dark else theme.light
                    ColorSwatch(
                        scheme = scheme,
                        label = theme.label,
                        selected = appearance.effectiveSeed == theme,
                        enabled = enabled,
                        onClick = { onSeedChange(theme) },
                    )
                }
            }
        },
    )
}

/**
 * Material 3 Expressive 三段色板预览球：上半圆展示 `primary`，左下四分之一展示 `primaryContainer`，
 * 右下四分之一展示 `tertiary`，选中时配外环与对勾徽章。
 */
@Composable
private fun ColorSwatch(
    scheme: ColorScheme,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    idleIcon: ImageVector? = null,
) {
    val style = settingsStyle()
    val ringColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(style.swatchTarget)
            .clip(CircleShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(style.swatchSize)
                .then(
                    if (selected) {
                        Modifier
                            .border(2.dp, ringColor, CircleShape)
                            .padding(3.dp)
                    } else {
                        Modifier
                    },
                )
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val diameter = size.minDimension
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)
                drawArc(
                    color = scheme.primary,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = topLeft,
                    size = arcSize,
                )
                drawArc(
                    color = scheme.primaryContainer,
                    startAngle = 90f,
                    sweepAngle = 90f,
                    useCenter = true,
                    topLeft = topLeft,
                    size = arcSize,
                )
                drawArc(
                    color = scheme.tertiary,
                    startAngle = 0f,
                    sweepAngle = 90f,
                    useCenter = true,
                    topLeft = topLeft,
                    size = arcSize,
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            } else if (idleIcon != null) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(scheme.surface.copy(alpha = 0.82f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = idleIcon,
                        contentDescription = null,
                        tint = scheme.onSurface,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}
