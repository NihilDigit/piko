package dev.piko.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ToggleButtonDefaults
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.components.connectedToggleShapes

/**
 * 设置页一套的尺寸。桌面照 Windows 设置页的卡片（Fluent settings card）：每项一张独立的小圆角卡片、卡片间 4dp、
 * 20dp 图标配 14sp 标题；移动端照 M3 Expressive 的分段列表：组内 2dp 间隙、外侧 16dp 圆角、内侧 4dp、
 * 24dp 图标配 16sp 标题、行高至少 56dp。行按交互模型取，平板与手机是同一套；只有分段按钮的位置看宽度。
 */
@Immutable
internal class SettingsStyle(
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val minHeight: Dp,
    val iconSize: Dp,
    val iconGap: Dp,
    val outerCorner: Dp,
    val innerCorner: Dp,
    val itemGap: Dp,
    val titleStyle: TextStyle,
    val supportingStyle: TextStyle,
    /** 二三选一的分段按钮放在行尾（桌面与较宽的移动端），还是标题下面占满一行（手机竖握）。 */
    val trailingChoices: Boolean,
    val swatchTarget: Dp,
    val swatchSize: Dp,
) {
    val itemShape: Shape = RoundedCornerShape(innerCorner)
    val groupShape: Shape = RoundedCornerShape(outerCorner)

    /** 下方附加控件的起点：与标题文字左缘齐。 */
    val textInset: Dp get() = iconSize + iconGap
}

@Composable
internal fun settingsStyle(): SettingsStyle {
    val desktop = isDesktopLayout()
    // 密度按宽度：平板上分段按钮铺满一整行有 800dp 宽，与手机同放在行尾
    val compact = currentWidthClass() == WidthClass.Compact
    val typography = MaterialTheme.typography
    return remember(desktop, compact, typography) {
        if (desktop) {
            SettingsStyle(
                horizontalPadding = 16.dp,
                verticalPadding = 12.dp,
                minHeight = 64.dp,
                iconSize = 20.dp,
                iconGap = 16.dp,
                outerCorner = 8.dp,
                innerCorner = 8.dp,
                itemGap = 4.dp,
                titleStyle = typography.bodyMedium,
                supportingStyle = typography.bodySmall,
                trailingChoices = true,
                swatchTarget = 40.dp,
                swatchSize = 32.dp,
            )
        } else {
            SettingsStyle(
                horizontalPadding = 16.dp,
                verticalPadding = 12.dp,
                minHeight = 56.dp,
                iconSize = 24.dp,
                iconGap = 16.dp,
                outerCorner = 16.dp,
                innerCorner = 4.dp,
                itemGap = 2.dp,
                titleStyle = typography.bodyLarge,
                supportingStyle = typography.bodyMedium,
                trailingChoices = !compact,
                swatchTarget = 48.dp,
                swatchSize = 36.dp,
            )
        }
    }
}

/**
 * 一类设置的标题，即目录里的一项。两端、各宽度都有：原来窄窗口省掉类标题、改由组标题兼任，
 * 同一组设置在不同宽度下层级各不相同（ux-review L9）。
 */
@Composable
internal fun SettingsSectionTitle(text: String, first: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier.padding(
            start = settingsStyle().horizontalPadding,
            top = if (first) 8.dp else 32.dp,
            bottom = 4.dp,
        ),
    )
}

/**
 * 一组设置：可选的组标题，下面是连成一块的几行。只有一组的类不给组标题，类标题已说明了它。
 *
 * 圆角由组统一裁出，各行只画内侧的小圆角：首尾行的外侧自然是大圆角，只有一行的组四角都是，
 * 不必逐行传位置（原来逐行传 index、count，单项组拿到的是库的默认小圆角，ux-review M14），
 * 随开关收起的行也不会把相邻行的形状留错。
 */
@Composable
internal fun SettingsGroup(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val style = settingsStyle()
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = style.horizontalPadding, top = 16.dp, bottom = 8.dp),
            )
        } else {
            Spacer(Modifier.height(12.dp))
        }
        Column(
            modifier = Modifier.fillMaxWidth().clip(style.groupShape),
            verticalArrangement = Arrangement.spacedBy(style.itemGap),
            content = content,
        )
    }
}

/** 组里不是标准行的一块（账号卡片、关于卡片），与各行同底色、同形状。 */
@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val style = settingsStyle()
    Surface(
        shape = style.itemShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

/**
 * 设置的一行。所有设置项都用它（或下面几个包装），对齐规则只写在这一处：
 * - 前导图标对标题与说明这一块垂直居中，与行尾控件同一条中线（照 Windows 设置卡片与 M3 列表项）；
 *   曾对齐标题首行，有说明的行图标贴在左上角，与行尾控件一高一低；
 * - 行尾控件对标题与说明这一块居中，不对整行：下面挂着分段按钮或色块时，开关不悬到两者之间；
 * - 下方附加控件（[below]）左缘与标题文字左缘齐，右缘与行尾控件齐。
 *
 * 不用 SegmentedListItem：桌面端的 material3 停在 1.12.0-alpha03（原因见 libs.versions.toml），那一版只有
 * 带 onClick 与带 checked 的两种，下面挂控件的行得另拼，两种拼法的图标位置对不齐，就是原来深色模式那一行的错位。
 *
 * [enabled] 为 false 时标题、图标与行尾控件按 M3 的禁用态淡去，说明照常显示：它多半写着为什么不可用。
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: String? = null,
    supportingColor: Color = Color.Unspecified,
    titleColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    selected: Boolean = false,
    /** 整行可点时的点击，与 [toggle] 二选一。 */
    onClick: (() -> Unit)? = null,
    /** 整行是一个开关时的状态与回调，行尾的 Switch 只作指示，读屏把整行报成开关。 */
    toggle: Pair<Boolean, (Boolean) -> Unit>? = null,
    /** 头像这类比图标大的前导元素，对标题与说明整块居中。与 [icon] 二选一。 */
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val style = settingsStyle()
    val colors = MaterialTheme.colorScheme
    val interaction = when {
        toggle != null -> Modifier.toggleable(value = toggle.first, enabled = enabled, role = Role.Switch, onValueChange = toggle.second)
        onClick != null -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    val contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface
    val dimmed = if (enabled) 1f else DisabledContentAlpha
    Surface(
        shape = style.itemShape,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .then(interaction)
                .padding(horizontal = style.horizontalPadding, vertical = style.verticalPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = style.minHeight - style.verticalPadding * 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = (if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant).copy(alpha = dimmed),
                            modifier = Modifier.size(style.iconSize),
                        )
                        Spacer(Modifier.width(style.iconGap))
                    } else if (leading != null) {
                        leading()
                        Spacer(Modifier.width(style.iconGap))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = style.titleStyle,
                            color = titleColor.takeOrElse(contentColor).copy(alpha = dimmed),
                        )
                        if (supporting != null) {
                            Text(
                                text = supporting,
                                style = style.supportingStyle,
                                color = supportingColor.takeOrElse(colors.onSurfaceVariant),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (trailing != null) {
                    Spacer(Modifier.width(16.dp))
                    CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) { trailing() }
                    }
                }
            }
            if (below != null) {
                Box(Modifier.fillMaxWidth().padding(start = if (icon != null) style.textInset else 0.dp, top = 12.dp)) {
                    below()
                }
            }
        }
    }
}

private fun Color.takeOrElse(fallback: Color): Color = if (this == Color.Unspecified) fallback else this

// M3 禁用态的内容不透明度
private const val DisabledContentAlpha = 0.38f

/** 开关行：整行可点，行尾的 Switch 只作指示。[extraTrailing] 是排在开关前面的附属按钮（立即同步）。 */
@Composable
internal fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    supporting: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    extraTrailing: (@Composable () -> Unit)? = null,
) {
    SettingsRow(
        title = title,
        icon = icon,
        supporting = supporting,
        enabled = enabled,
        toggle = checked to onCheckedChange,
        trailing = {
            extraTrailing?.invoke()
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
    )
}

/**
 * 可点的一行。行尾图标说明点了去哪：去下一页是箭头，离开应用是外链图标，弹对话框或当场执行的为 null。
 * 箭头在列表里只说「去下一页」这一件事，给每个可点的行都画上，它就什么也不说明了。
 */
@Composable
internal fun SettingsNavigationRow(
    icon: ImageVector,
    title: String,
    supporting: String?,
    onClick: () -> Unit,
    trailingIcon: ImageVector? = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
    /** 这一行对应的页正显示着。 */
    selected: Boolean = false,
    /** 亮起时换成的实心图标，与侧边栏一样。 */
    selectedIcon: ImageVector = icon,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        icon = if (selected) selectedIcon else icon,
        supporting = supporting,
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        trailing = trailingIcon?.let { trailing -> { Icon(trailing, contentDescription = null) } },
    )
}

/**
 * 行尾的按钮：主要的用有底色的 tonal 按钮，次要的用描边按钮。不用文字按钮：它没有容器，禁用时只是一行灰字，
 * 看不出是按钮还是说明。禁用时的原因写在行的说明里。
 */
@Composable
internal fun SettingsRowButton(label: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = true) {
    if (primary) {
        FilledTonalButton(onClick = onClick, enabled = enabled) { Text(label, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(label, maxLines = 1) }
    }
}

/**
 * 二三选一的分段按钮（M3 Expressive 的连体按钮组）。[fill] 时各段均分整行，用于标题下方；
 * 否则各段按文字宽度，用于行尾。
 */
@Composable
internal fun <T> SettingsSegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    fill: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        // 未选的段默认是 surfaceContainer，与设置行同色，放在行里只剩一行字，看不出是按钮
        val colors = ToggleButtonDefaults.toggleButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                shapes = connectedToggleShapes(index, options.size),
                colors = colors,
                modifier = if (fill) Modifier.weight(1f) else Modifier,
            ) {
                Text(label(option), maxLines = 1)
            }
        }
    }
}

/**
 * 依赖上面某个开关的行：开关关着时收起，而不是灰着留在那里。一行灰掉的设置读起来像「坏了」或「没权限」，
 * 收起则明说「这时它不起作用」；收起与展开有动画，看得出是上面那个开关带出来的。
 * 只用于同一组里紧跟着的行；依赖别处的开关时留着并禁用、在说明里写原因，否则人找不到它为什么不见了。
 */
@Composable
internal fun ColumnScope.DependentRow(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
        exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
    ) { content() }
}

/**
 * 选择类设置的对话框：一列单选项，点选即生效，只有一个「关闭」。原来四个对话框各用一种提交方式
 * （只有「取消」、只有「完成」、「重新测速」加「完成」、「取消」加「保存」，ux-review M2），
 * 点一下单选项会不会立刻生效无从预期；「取消」更暗示能撤销，其实撤销不了。
 *
 * [header] 放在列表上方的一行，如测速按钮；[footer] 是列表下面的补充说明。
 */
@Composable
internal fun SettingsChoiceDialog(
    icon: ImageVector,
    title: String,
    onDismiss: () -> Unit,
    description: String? = null,
    footer: String? = null,
    header: (@Composable () -> Unit)? = null,
    options: @Composable ColumnScope.() -> Unit,
) {
    PikoDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (description != null) DialogDescription(description)
                header?.invoke()
                Column(modifier = Modifier.selectableGroup(), content = options)
                if (footer != null) DialogFootnote(footer)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 单选对话框的一项：整行可点，单选钮不单独接点击（onClick 为 null），免得点钮和点行各触发一次。
 * [trailingIcon] 说明再点一次会怎样，例如已选的自定义位置再点是换文件夹。
 */
@Composable
internal fun SettingsChoiceOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    supporting: String? = null,
    trailingIcon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (supporting != null) 56.dp else 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                // 路径可能很长，最多两行，末尾省略保住开头的盘符与上级目录
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
            )
        }
    }
}

/**
 * 填写类设置的对话框：说明、输入框、补充说明，底部「取消」与「保存」，填完点保存才生效。
 * 代理、速度上限与 MetaTube 共用这一副结构，输入框一律用 PikoTextField（ux-review M3）。
 */
@Composable
internal fun SettingsInputDialog(
    icon: ImageVector,
    title: String,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    onDismiss: () -> Unit,
    description: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PikoDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (description != null) DialogDescription(description)
                content()
                if (footer != null) DialogFootnote(footer)
            }
        },
        confirmButton = { PikoDialogConfirm("保存", onClick = onSave, enabled = saveEnabled) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DialogDescription(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DialogFootnote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
