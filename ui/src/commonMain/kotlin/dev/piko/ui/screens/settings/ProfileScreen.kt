package dev.piko.ui.screens.settings

import dev.piko.shared.i18n.enCount

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import dev.piko.ui.components.TooltipIconButton
import kotlinx.coroutines.coroutineScope
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.IslandHeaderSpace
import dev.piko.ui.platform.rememberCaptionSlot
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.piko.data.auth.QuotaSnapshot
import dev.piko.shared.data.DriveLibrary
import dev.piko.ui.screens.drive.icon
import dev.piko.ui.screens.drive.selectedIcon
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.LocalRootSnackbar
import dev.piko.ui.adaptive.readableSidePadding
import dev.piko.ui.adaptive.readableWidth
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.navigation.Screen
import io.github.nihildigit.pikpak.CountQuota
import io.github.nihildigit.pikpak.TransferAllowances
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.launch
import dev.piko.shared.data.SavedAccount
import androidx.compose.ui.unit.Dp

/**
 * 「我的」页：账号卡片（含网盘空间与流量额度）、库与管理的入口、关于、退出登录。
 * 设置项另起一页。开屏检查查到的新版本写在关于卡片上，设置入口的副标题不再代为提示。
 *
 * [selectedPane] 是 expanded 窗口里右侧详情栏正在显示的页，对应的入口行高亮；其余宽度下为 null。
 */
@Composable
fun ProfileScreen(
    onLogout: () -> Unit,
    /** 打开「我的」的详情页：我的分享、设置。 */
    onOpenPane: (Screen) -> Unit,
    selectedPane: Screen?,
    /** 最近添加、星标、播放历史与回收站在网盘页里看，点了切到网盘页，见 DriveLibrary。 */
    onOpenLibrary: (DriveLibrary) -> Unit,
    modifier: Modifier = Modifier,
    /** 宽窗口里「我的」与详情页并排、盖住导航栏时才有：这时它是列表栏，退出两栏由它负责。 */
    onBackClick: (() -> Unit)? = null,
) {
    val account = rememberAccountSummary()
    val saved = account.saved
    val snackbarHostState = LocalRootSnackbar.current

    // 顶栏写的是账号名而不是「我的」：写「我的」只是把导航栏标签抄一遍，账号名才是这一页在讲的
    // 东西。展开时用 headline 字号立起全应用唯一的标题锚点，滚上去收成一行，让出的高度归下面的
    // 内容。副标题在收起态也在（flexible 顶栏的 subtitle 同时用作收起态的小副标题），只放一行短字，
    // 头像与会员期留在下面的账号卡里
    val topBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val accountLabel = saved?.email?.ifBlank { null } ?: saved?.account?.takeIf { it != saved.displayName }
    PikoScaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(topBarScrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 有外框时（恢复旧版存下的返回栈才会在宽窗口里打开这一页）与别的页同一副骨架，页名是账号名
        island = IslandPage(header = {
            IslandTitle(saved?.displayName ?: tr("PikPak 用户", "PikPak user", "PikPakユーザー", "PikPak 사용자", "PikPak 使用者"))
            IslandHeaderSpace()
        }),
        topBar = {
            // 宽窗口里下面的内容收在居中的一栏，标题与返回一起缩进同样的量，底色仍铺满
            BoxWithConstraints {
                val sideInset = readableSidePadding(maxWidth)
                // 桌面端手机宽度的窗口里这一行贴着右上角，窗口按钮接在动作的位置上
                val caption = rememberCaptionSlot()
                MediumFlexibleTopAppBar(
                    modifier = caption.modifier,
                    actions = { caption.buttons?.invoke() },
                    title = {
                        Text(
                            text = saved?.displayName ?: tr("PikPak 用户", "PikPak user", "PikPakユーザー", "PikPak 사용자", "PikPak 使用者"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    subtitle = accountLabel?.let { label ->
                        { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    },
                    navigationIcon = {
                        if (onBackClick != null) {
                            IconButton(onClick = onBackClick) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("返回", "Back", "戻る", "뒤로", "返回"))
                            }
                        }
                    },
                    windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = sideInset, right = sideInset)),
                    scrollBehavior = topBarScrollBehavior,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp)
                .readableWidth(),
        ) {
            AccountCard(account)

            AccountSwitcher()

            // 最近添加、星标与播放历史是看内容的入口，排在前面；分享、回收站与设置是管理，排在后面
            SettingsGroup(null) {
                SettingsNavigationRow(
                    icon = DriveLibrary.RECENT.icon,
                    selectedIcon = DriveLibrary.RECENT.selectedIcon,
                    title = DriveLibrary.RECENT.title,
                    supporting = tr("最近上传与离线、秒传的文件", "Recent uploads, cloud downloads and instant saves", "最近アップロード・クラウドダウンロード・インスタント保存したファイル", "최근 업로드, 클라우드 다운로드 및 즉시 저장 파일", "最近上傳與離線、秒傳的檔案"),
                    onClick = { onOpenLibrary(DriveLibrary.RECENT) },
                )
                SettingsNavigationRow(
                    icon = DriveLibrary.STARRED.icon,
                    selectedIcon = DriveLibrary.STARRED.selectedIcon,
                    title = DriveLibrary.STARRED.title,
                    supporting = tr("已加星标的文件与文件夹", "Starred files and folders", "スター付きのファイルとフォルダー", "별표를 추가한 파일 및 폴더", "已加星號的檔案與資料夾"),
                    onClick = { onOpenLibrary(DriveLibrary.STARRED) },
                )
                SettingsNavigationRow(
                    icon = DriveLibrary.HISTORY.icon,
                    selectedIcon = DriveLibrary.HISTORY.selectedIcon,
                    title = DriveLibrary.HISTORY.title,
                    supporting = tr("与 PikPak 官方客户端同步", "Synced with official PikPak apps", "PikPak公式アプリと同期", "PikPak 공식 앱과 동기화", "與 PikPak 官方用戶端同步"),
                    onClick = { onOpenLibrary(DriveLibrary.HISTORY) },
                )
            }

            SettingsGroup(null) {
                SettingsNavigationRow(
                    icon = Icons.Outlined.FolderShared,
                    selectedIcon = Icons.Filled.FolderShared,
                    title = tr("我的分享", "My shares", "自分の共有", "내 공유", "我的分享"),
                    supporting = tr("复制或取消已创建的分享链接", "Copy or revoke your share links", "作成した共有リンクをコピーまたは解除", "만든 공유 링크 복사 또는 취소", "複製或取消已建立的分享連結"),
                    onClick = { onOpenPane(Screen.MyShares) },
                    selected = selectedPane == Screen.MyShares,
                )
                SettingsNavigationRow(
                    icon = DriveLibrary.TRASH.icon,
                    selectedIcon = DriveLibrary.TRASH.selectedIcon,
                    title = DriveLibrary.TRASH.title,
                    supporting = tr("恢复或彻底删除已移入回收站的文件", "Restore or permanently delete trashed files", "ゴミ箱内のファイルを復元または完全に削除", "휴지통 파일 복원 또는 영구 삭제", "還原或永久刪除已移至資源回收筒的檔案"),
                    onClick = { onOpenLibrary(DriveLibrary.TRASH) },
                )
                SettingsNavigationRow(
                    icon = Icons.Outlined.Settings,
                    selectedIcon = Icons.Filled.Settings,
                    title = tr("设置", "Settings", "設定", "설정", "設定"),
                    supporting = tr("外观、网盘、传输与同步", "Appearance, drive, transfers and sync", "外観・ドライブ・転送・同期", "모양, 드라이브, 전송 및 동기화", "外觀、雲端硬碟、傳輸與同步"),
                    onClick = { onOpenPane(Screen.Settings) },
                    selected = selectedPane == Screen.Settings,
                )
            }

            AboutSection(snackbarHostState)

            Spacer(modifier = Modifier.height(24.dp))

            LogoutButton(onLoggedOut = onLogout)
        }
    }
}

/** 账号卡片要的数据：会话、网盘空间与流量额度。「我的」页与桌面侧边栏左下角的账号菜单共用。 */
internal class AccountSummary(
    /** 当前账号在列表里记着的资料；启动恢复前为 null。 */
    val saved: SavedAccount?,
    val quota: QuotaSnapshot?,
    val allowances: TransferAllowances?,
    val allowancesError: String?,
    /** 每日离线次数，只有免费账号有上限。 */
    val cloudDownload: CountQuota?,
    val refreshing: Boolean,
    val refresh: () -> Unit,
)

/** 取 [AccountSummary]，并在进入组合时与 [AccountSummary.refresh] 时刷新空间、流量额度与昵称头像。 */
@Composable
internal fun rememberAccountSummary(): AccountSummary {
    val services = LocalPikoServices.current
    val driveRepo = services.driveRepository
    val accounts by services.clientManager.accounts.collectAsStateWithLifecycle()
    val client by services.clientManager.currentClient.collectAsStateWithLifecycle()
    val saved = client?.account?.let(accounts::find)
    // 网络回来之前先用列表里记着的数字渲染，否则卡片整块缺席、刷新完再跳出来
    val liveQuota by driveRepo.quotaFlow.collectAsStateWithLifecycle()
    val cachedQuota = saved?.takeIf { it.limitBytes > 0 }?.let { QuotaSnapshot(it.usageBytes, it.limitBytes) }
    val quota = liveQuota?.let { QuotaSnapshot(it.quota.usageBytes, it.quota.limitBytes) } ?: cachedQuota
    // 不落盘，只在本页存活期间保留；失败时留着上一次的值，只在旁边补一行错误文字
    val transferQuota by driveRepo.transferQuotaFlow.collectAsStateWithLifecycle()
    var transferQuotaError by remember { mutableStateOf<String?>(null) }
    var refreshCount by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshCount) {
        refreshing = true
        coroutineScope {
            launch {
                driveRepo.getQuota()
                // 昵称与头像不随登录态返回，每次进入取一次。
                // 失败不提示：头像本就有首字母兜底，为它弹一条错误反而扰人。
                services.accountRepository.refreshProfile()
            }
            launch {
                driveRepo.getTransferQuota()
                    .onSuccess { transferQuotaError = null }
                    .onFailure { transferQuotaError = tr("流量额度加载失败", "Failed to load traffic quota", "転送量の利用枠を読み込めませんでした", "트래픽 할당량을 불러오지 못했습니다", "流量額度載入失敗") }
            }
        }
        refreshing = false
    }
    return AccountSummary(
        saved = saved,
        quota = quota,
        allowances = transferQuota?.account,
        allowancesError = transferQuotaError,
        cloudDownload = liveQuota?.quotas?.cloudDownload,
        refreshing = refreshing,
        refresh = { refreshCount++ },
    )
}

/** 退出当前账号前的确认。[next] 是退出后要切过去的已保存账号，没有时回登录页。 */
@Composable
internal fun LogoutDialog(next: SavedAccount?, onDismiss: () -> Unit, onLoggedOut: () -> Unit) {
    val clientManager = LocalPikoServices.current.clientManager
    val scope = rememberCoroutineScope()
    PikoDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Logout,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(if (next == null) tr("退出登录", "Sign out", "ログアウト", "로그아웃", "登出") else tr("退出此账号", "Sign out of this account", "このアカウントからログアウト", "이 계정에서 로그아웃", "登出此帳號")) },
        text = {
            val after = next?.let { tr("，随后切换至「${it.displayName}」", ", then switch to “${it.displayName}”", "、その後「${it.displayName}」に切り替え", ", 이후 “${it.displayName}”(으)로 전환", "，隨後切換至「${it.displayName}」") }.orEmpty()
            Text(tr("将清除本机保存的该账号登录凭据$after。", "Remove this account’s saved credentials from this device$after.", "この端末に保存したアカウントのログイン情報を削除します$after。", "이 계정의 기기 내 로그인 정보를 삭제합니다$after.", "將清除本機儲存的此帳號登入憑證$after。"))
        },
        confirmButton = {
            PikoDialogConfirm(
                label = tr("退出", "Exit", "終了", "나가기", "登出"),
                destructive = true,
                onClick = {
                    // 退出在进程级作用域里跑，对话框的作用域被取消也照样清完凭据。
                    // 对话框等它做完再关：先关的话这个作用域随即取消，回调就到不了
                    val loggingOut = clientManager.logout()
                    scope.launch {
                        loggingOut.join()
                        onDismiss()
                        onLoggedOut()
                    }
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}

/**
 * 头像、会员期、网盘空间与月度流量额度合为一块。名字与邮箱在顶栏上，这里不再重复。
 *
 * 是 Surface 不是 Card：M3 的 card 是可以点进去的单一主题入口，这一块不可点，只是个容器。
 */
@Composable
internal fun AccountCard(account: AccountSummary, showRefresh: Boolean = false) {
    val username = account.saved?.displayName
    val avatarUrl = account.saved?.avatarUrl
    val quota = account.quota
    val allowances = account.allowances
    val allowancesError = account.allowancesError
    // 圆角与设置的分组相同：桌面设置页里它与下面的账号列表是同一套卡片
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = settingsStyle().groupShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 头像、会员期与空间并成一行：名字挪到顶栏之后，头像旁边只剩一枚会员期，单独占一行太空。
            // 会员期缩成小胶囊挂在「网盘空间」那一行的尾部，它和空间同属「这个账号有多少」
            // 免费账号的保存与离线规则不同，写明账号类型，行为上的差别才有来由
            val tier = when {
                allowances == null -> null
                allowances.isPremium -> allowances.expireTime.let(::formatExpireDate)?.let { tr("会员至 $it", "Premium until $it", "プレミアム有効期限 $it", "프리미엄 만료일 $it", "會員至 $it") }
                else -> tr("免费账号", "Free account", "無料アカウント", "무료 계정", "免費帳號")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(username = username, avatarUrl = avatarUrl)
                Spacer(modifier = Modifier.width(16.dp))
                if (quota != null) {
                    StorageSection(quota, tier, Modifier.weight(1f))
                } else if (tier != null) {
                    TierChip(tier)
                }
                if (showRefresh) {
                    if (quota == null) Spacer(modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    TooltipIconButton(Icons.Outlined.Refresh, tr("刷新", "Refresh", "更新", "새로고침", "重新整理"), account.refresh, enabled = !account.refreshing)
                }
            }

            if (allowances != null || allowancesError != null) {
                // 折叠行自带上下内边距作点击区，这里的间距比视觉上的段距小
                Spacer(modifier = Modifier.height(8.dp))
                TransferSection(allowances, account.cloudDownload, allowancesError)
            }
        }
    }
}

/** 头像，没有时是首字母。[username] 只用来给首字母占位。 */
@Composable
internal fun Avatar(username: String?, avatarUrl: String?, size: Dp = AvatarSize) {
    if (!avatarUrl.isNullOrBlank()) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = username?.take(1)?.uppercase(Locale.getDefault()) ?: "P",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** 与右侧空间块的三行（标签、用量、进度条）同高，头像不把这一行撑高。 */
private val AvatarSize = 48.dp

@Composable
private fun TierChip(tier: String, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Text(
            text = tier,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StorageSection(quota: QuotaSnapshot, tier: String?, modifier: Modifier = Modifier) {
    val fraction = usedFraction(quota.usageBytes, quota.limitBytes)
    val nearlyFull = fraction >= NEARLY_FULL_FRACTION
    val accent = if (nearlyFull) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(tr("网盘空间", "Cloud storage", "ドライブ容量", "클라우드 공간", "雲端硬碟空間"), Modifier.weight(1f))
            if (tier != null) TierChip(tier)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = quota.usageBytes.toReadableSize(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "/ ${quota.limitBytes.toReadableSize()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(modifier = Modifier.weight(1f))
            if (quota.limitBytes > 0) {
                Text(
                    text = tr("剩余 ${(quota.limitBytes - quota.usageBytes).coerceAtLeast(0).toReadableSize()}", "${(quota.limitBytes - quota.usageBytes).coerceAtLeast(0).toReadableSize()} remaining", "残り ${(quota.limitBytes - quota.usageBytes).coerceAtLeast(0).toReadableSize()}", "남은 공간 ${(quota.limitBytes - quota.usageBytes).coerceAtLeast(0).toReadableSize()}", "剩餘 ${(quota.limitBytes - quota.usageBytes).coerceAtLeast(0).toReadableSize()}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (nearlyFull) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { fraction },
            color = accent,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        )
    }
}

/**
 * 额度分两块，按重置的周期分：「今日额度」只有免费账号有（每日下载与每日离线次数，新加坡时间 0 点重置），
 * 「本月流量」两种账号都有（每月 1 日重置）。放在一块里，标题与重置说明总有一半说不对。
 */
@Composable
private fun TransferSection(allowances: TransferAllowances?, cloudDownload: CountQuota?, error: String?) {
    val daily = allowances?.let { dailyUsages(it, cloudDownload) }.orEmpty()
    Column {
        if (daily.isNotEmpty()) UsageSection(tr("今日额度", "Daily quota", "本日の利用枠", "오늘 할당량", "今日額度"), daily, ::dailyQuotaResetLabel, error = null)
        UsageSection(tr("本月流量", "Monthly traffic", "今月の転送量", "이번 달 트래픽", "本月流量"), allowances?.let(::monthlyUsages).orEmpty(), ::transferQuotaResetLabel, error)
    }
}

/**
 * 默认折叠成一行，只报用得最满的一项：额度要掂量的是「哪一项快见底了」，其余几格平时用不着看。
 * 展开后才写多久重置，折叠行放不下两段摘要，而重置时间只在某项接近用完时才有意义。
 */
@Composable
private fun UsageSection(title: String, usages: List<Usage>, resetLabel: () -> String, error: String?) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val tightest = usages.maxByOrNull { usedFraction(it.usedBytes, it.limitBytes) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(
                    enabled = usages.isNotEmpty(),
                    onClickLabel = if (expanded) tr("收起", "Collapse", "折りたたむ", "접기", "收合") else tr("展开", "Expand", "展開", "펼치기", "展開"),
                    role = Role.Button,
                ) { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(title, Modifier.weight(1f))
            val summary = when {
                expanded -> resetLabel()
                tightest != null -> tightestUsageLabel(tightest)
                else -> null
            }
            if (summary != null) {
                val nearlyFull = !expanded && tightest != null &&
                    usedFraction(tightest.usedBytes, tightest.limitBytes) >= NEARLY_FULL_FRACTION
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (nearlyFull) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (usages.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (error != null) {
            Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        AnimatedVisibility(visible = expanded && usages.isNotEmpty()) {
            Column {
                Spacer(modifier = Modifier.height(4.dp))
                UsageGrid(usages)
            }
        }
    }
}

private fun monthlyUsages(allowances: TransferAllowances): List<Usage> = listOf(
    Usage(tr("离线", "Cloud download", "クラウドダウンロード", "클라우드 다운로드", "離線"), allowances.offline.usedBytes, allowances.offline.limitBytes),
    Usage(tr("下载", "Downloads", "ダウンロード", "다운로드", "下載"), allowances.download.usedBytes, allowances.download.limitBytes),
    Usage(tr("上传", "Uploads", "アップロード", "업로드", "上傳"), allowances.upload.usedBytes, allowances.upload.limitBytes),
)

/** 会员两项都没有：每日下载的上限为 0，离线次数不限。 */
private fun dailyUsages(allowances: TransferAllowances, cloudDownload: CountQuota?): List<Usage> = buildList {
    if (allowances.downloadDaily.limitBytes > 0) {
        add(Usage(tr("下载", "Downloads", "ダウンロード", "다운로드", "下載"), allowances.downloadDaily.usedBytes, allowances.downloadDaily.limitBytes))
    }
    // 秒传不算离线次数，这一格只数离线任务
    val remaining = cloudDownload?.remaining
    if (cloudDownload != null && remaining != null) {
        val limit = cloudDownload.limit.toLong()
        add(Usage(tr("离线", "Cloud download", "クラウドダウンロード", "클라우드 다운로드", "離線"), limit - remaining, limit, format = { tr("$it 次", "${enCount(it, "time", "times")}", "$it 回", "${it}회", "$it 次") }))
    }
}

/** 额度为 0 的项没有比例可言，写用量本身。 */
private fun tightestUsageLabel(usage: Usage): String =
    if (usage.limitBytes > 0) {
        tr("${usage.title}已用 ${(usedFraction(usage.usedBytes, usage.limitBytes) * 100).toInt()}%", "${usage.title}: ${(usedFraction(usage.usedBytes, usage.limitBytes) * 100).toInt()}% used", "${usage.title}：${(usedFraction(usage.usedBytes, usage.limitBytes) * 100).toInt()}% 使用済み", "${usage.title}: ${(usedFraction(usage.usedBytes, usage.limitBytes) * 100).toInt()}% 사용", "${usage.title}已用 ${(usedFraction(usage.usedBytes, usage.limitBytes) * 100).toInt()}%")
    } else {
        tr("${usage.title}已用 ${usage.format(usage.usedBytes)}", "${usage.title}: ${usage.format(usage.usedBytes)} used", "${usage.title}：${usage.format(usage.usedBytes)} 使用済み", "${usage.title}: ${usage.format(usage.usedBytes)} 사용", "${usage.title}已用 ${usage.format(usage.usedBytes)}")
    }

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * 小格按宽度换行，但各行格数取均匀：四格放不下一行时排成 2 + 2，而不是 3 + 1。
 * 不用 FlowRow：它只按剩余空间换行，末行那一格会被 weight 拉满整行，与上一行宽度对不上。
 */
@Composable
private fun UsageGrid(usages: List<Usage>) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val spacing = 8.dp
        val maxColumns = ((maxWidth + spacing) / (USAGE_TILE_MIN_WIDTH + spacing)).toInt().coerceIn(1, usages.size)
        val rows = (usages.size + maxColumns - 1) / maxColumns
        val columns = (usages.size + rows - 1) / rows
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            usages.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    row.forEach { UsageTile(it, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * 月度流量额度（离线下载、下载、上传）的一格。流量额度是官方网页流量配额弹窗在客户端的对应物；
 * 第三方应用共享的 `connectedApps` 那 25% 不显示：piko 走的是账号自身额度，不占用那一份。
 */
private class Usage(
    val title: String,
    val usedBytes: Long,
    val limitBytes: Long,
    val format: (Long) -> String = { it.toReadableSize() },
)

private val USAGE_TILE_MIN_WIDTH = 88.dp

/** 空间用到这个比例起，进度条与剩余量改用 error 色。 */
private const val NEARLY_FULL_FRACTION = 0.95f

private fun usedFraction(usedBytes: Long, limitBytes: Long): Float =
    if (limitBytes > 0) (usedBytes.toFloat() / limitBytes).coerceIn(0f, 1f) else 0f

@Composable
private fun UsageTile(usage: Usage, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = usage.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = usage.format(usage.usedBytes),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "/ ${usage.format(usage.limitBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { usedFraction(usage.usedBytes, usage.limitBytes) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 距下一次月度重置还有几天（每月 1 日 0 点，新加坡时间；接口不返回）。写天数不写日期：看额度时
 * 要掂量的是「还能撑几天」，日期还得自己再减一次。按新加坡的日历日算，月末最后一天是「1 天后」。
 * minSdk 26 起自带 java.time，不必再引入 kotlinx-datetime。
 */
private fun transferQuotaResetLabel(): String {
    val today = OffsetDateTime.now(ZoneOffset.ofHours(8)).toLocalDate()
    val reset = today.plusMonths(1).withDayOfMonth(1)
    return tr("${ChronoUnit.DAYS.between(today, reset)} 天后重置", "Resets in ${enCount(ChronoUnit.DAYS.between(today, reset), "day", "days")}", "${ChronoUnit.DAYS.between(today, reset)} 日後にリセット", "${ChronoUnit.DAYS.between(today, reset)}일 후 초기화", "${ChronoUnit.DAYS.between(today, reset)} 天後重設")
}

/** 距下一次每日重置（新加坡时间 0 点）还有几小时，不足 1 小时写「1 小时内」。 */
private fun dailyQuotaResetLabel(): String {
    val now = OffsetDateTime.now(ZoneOffset.ofHours(8))
    val hours = ChronoUnit.HOURS.between(now, now.toLocalDate().plusDays(1).atStartOfDay().atOffset(now.offset))
    return if (hours < 1) tr("1 小时内重置", "Resets within 1 hour", "1時間以内にリセット", "1시간 이내 초기화", "1 小時內重設") else tr("$hours 小时后重置", "Resets in ${enCount(hours, "hour", "hours")}", "$hours 時間後にリセット", "${hours}시간 후 초기화", "$hours 小時後重設")
}

/** 非会员时 [TransferAllowances.expireTime] 为空字符串，解析失败也一并按「没有」处理。 */
private fun formatExpireDate(expireTime: String): String? {
    if (expireTime.isBlank()) return null
    return runCatching {
        OffsetDateTime.parse(expireTime).format(DateTimeFormatter.ofPattern(tr("yyyy 年 M 月 d 日", "MMM d, yyyy", "yyyy年M月d日", "yyyy년 M월 d일", "yyyy 年 M 月 d 日"), Locale.getDefault()))
    }.getOrNull()
}

