package dev.piko.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 面板、右键菜单与工具栏「更多」里的一项操作。怎么摆由 [layoutActions] 统一决定：[destructive] 的单独成组垫底、用错误色，
 * 其余按 [tier] 分档、按 [group] 分组。
 */
class SheetAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val group: ActionGroup = ActionGroup.Default,
    /** 几选一里的一项（视图）：true 是眼下这一项，菜单里打勾；null 不是这类项。 */
    val checked: Boolean? = null,
    /**
     * 按下或指针移到这一项上时调用，点下去之前先做准备（例如提前查画质）。可能调用多次，要能重入。
     * 面板里的操作要等面板收起才执行，按下到执行之间有三四百毫秒，菜单里还有悬停的时间。
     */
    val onPrepare: (() -> Unit)? = null,
    val tier: ActionTier = ActionTier.Standard,
    /** 图标行里写在图标下的字。面板的图标行一格只有四分之一宽，长标签放不下，例如「下载到本地」写「下载」。 */
    val shortLabel: String = label,
)

/** 一项操作摆在哪一档。只在项数多到要分档时起作用，见 [layoutActions]。 */
enum class ActionTier {
    /** 顶上一排图标，最常用的几样。 */
    Quick,

    /** 常驻的列表。 */
    Standard,

    /** 面板里收进末尾一行可展开的「更多」；右键菜单不收，按组并进列表。 */
    More,
}

/**
 * 同组的排在一起，组与组之间画分隔。只认同组与否，按先出现的先排，不按这里声明的先后。
 * 条目操作用打开、整理、管理三组；网格空白处与工具栏收起的项各用各的。
 */
enum class ActionGroup {
    Default,

    /** 拿到内容的方式：打开、下载、分享、解压、恢复。 */
    Open,

    /** 改它在网盘里的样子与位置：星标、改名、移动、复制、固定。 */
    Organize,

    /** 用得少的：查重、归档、预览遮蔽、来源链接。 */
    Manage,
    View,

    /** 海报墙与图库的卡片大小，紧跟在视图之后另成一组。 */
    ViewSize,
    Refresh,
    Create,
    Edit,
    Select,
    Sort,
    Filter,

    /** 「属性」，自成一组，排在危险项之前。 */
    Properties,
}

/** [layoutActions] 的结果。各档为空即不画，各组按先出现的先排。 */
class ActionLayout(
    val quick: List<SheetAction>,
    val sections: List<List<SheetAction>>,
    val more: List<List<SheetAction>>,
    val properties: List<SheetAction>,
    val danger: List<SheetAction>,
)

/**
 * 面板与右键菜单共用的排法。危险项垫底、「属性」在它之前，各自成组；其余按三条规则分档：
 * 非危险项（连同「属性」）不超过 [FlatLimit] 项时不分档，全部平铺，几项还要找图标行、再点「更多」只是添步骤；
 * 图标行至多 [QuickLimit] 个，多出的回到列表；「更多」里只有一项时就地放进列表，为一项多点一下不值得。
 *
 * [foldMore] 为 false 时没有「更多」，[ActionTier.More] 的项按各自的组并进列表。右键菜单这样排：它只在桌面上，
 * 窗口放得下一整列，收起来只是多点一下；面板在手机上一屏放不下，仍然收。
 */
fun layoutActions(actions: List<SheetAction>, foldMore: Boolean = true): ActionLayout {
    val (danger, safe) = actions.partition { it.destructive }
    val (properties, rest) = safe.partition { it.group == ActionGroup.Properties }
    if (safe.size <= FlatLimit) return ActionLayout(emptyList(), sectionsOf(rest), emptyList(), properties, danger)
    val quick = rest.filter { it.tier == ActionTier.Quick }.take(QuickLimit)
    val more = if (foldMore) rest.filter { it.tier == ActionTier.More }.takeIf { it.size > 1 }.orEmpty() else emptyList()
    val standard = rest.filter { it !in quick && it !in more }
    return ActionLayout(quick, sectionsOf(standard), sectionsOf(more), properties, danger)
}

private fun sectionsOf(actions: List<SheetAction>): List<List<SheetAction>> = actions.groupBy { it.group }.values.toList()

const val FlatLimit = 6
const val QuickLimit = 4

/** 在 [action] 上按下或指针移入时调用 [SheetAction.onPrepare]。不消费事件，点击照常。 */
internal fun Modifier.prepareOnPointer(action: SheetAction): Modifier {
    val prepare = action.onPrepare ?: return this
    return pointerInput(action) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press || event.type == PointerEventType.Enter) prepare()
            }
        }
    }
}
