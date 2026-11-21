package dev.piko.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.OutlinedToggleButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.CardBadgeKind
import dev.piko.shared.data.CornerPosition
import dev.piko.shared.data.DetailField
import dev.piko.shared.data.DriveAppearanceMode
import dev.piko.shared.data.DriveAppearancePrefs
import dev.piko.shared.data.FolderStyle
import dev.piko.shared.data.ListViewStylePrefs
import dev.piko.shared.data.toggleDetailField
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.*
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk

/** 紧随预览下方的上下文槽位检查器：顶部连体单选切换器 + 当前选中区域的精简控件。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SlotInspectorCard(
    mode: DriveAppearanceMode,
    prefs: DriveAppearancePrefs,
    selectedSlot: DrivePreviewSlot,
    onSelectSlot: (DrivePreviewSlot) -> Unit,
    onUpdate: (DriveAppearancePrefs) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val slots = remember(mode) { availableSlotsFor(mode) }
    val slotRows = remember(slots) {
        val corners = slots.filterIsInstance<DrivePreviewSlot.Corner>()
        val nonCorners = slots.filterNot { it is DrivePreviewSlot.Corner }
        if (corners.isNotEmpty() && nonCorners.isNotEmpty()) {
            listOf(corners, nonCorners)
        } else {
            listOf(slots)
        }
    }

    Surface(
        color = if (isEInk) Color.White else colors.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        border = if (isEInk) BorderStroke(1.dp, colors.outline) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectedSingleSelectRows(
                rows = slotRows,
                selected = selectedSlot,
                label = { slot -> slotLabel(slot, mode) },
                onSelect = onSelectSlot,
            )

            HorizontalDivider(color = if (isEInk) colors.outline else colors.outlineVariant.copy(alpha = 0.45f))

            when (selectedSlot) {
                is DrivePreviewSlot.Corner -> {
                    val corners = when (mode) {
                        DriveAppearanceMode.GRID -> prefs.grid.corners
                        DriveAppearanceMode.GALLERY -> prefs.gallery.corners
                        DriveAppearanceMode.LIST -> return@Column
                    }
                    CornerBadgeSelector(
                        selected = corners[selectedSlot.position],
                        onSelect = { badge ->
                            val next = corners.assign(selectedSlot.position, badge)
                            when (mode) {
                                DriveAppearanceMode.GRID -> onUpdate(prefs.copy(grid = prefs.grid.copy(corners = next)))
                                DriveAppearanceMode.GALLERY -> onUpdate(prefs.copy(gallery = prefs.gallery.copy(corners = next)))
                                DriveAppearanceMode.LIST -> Unit
                            }
                        },
                    )
                }
                DrivePreviewSlot.Folder -> {
                    val currentStyle = when (mode) {
                        DriveAppearanceMode.LIST -> prefs.list.folderStyle
                        DriveAppearanceMode.GRID -> prefs.grid.folderStyle
                        DriveAppearanceMode.GALLERY -> return@Column
                    }
                    FolderStyleInspector(
                        selected = currentStyle,
                        onSelect = { style ->
                            when (mode) {
                                DriveAppearanceMode.LIST -> onUpdate(prefs.copy(list = prefs.list.copy(folderStyle = style)))
                                DriveAppearanceMode.GRID -> onUpdate(prefs.copy(grid = prefs.grid.copy(folderStyle = style)))
                                DriveAppearanceMode.GALLERY -> Unit
                            }
                        },
                    )
                }
                DrivePreviewSlot.TitleRow -> {
                    if (mode == DriveAppearanceMode.LIST) {
                        ListTitleRowInspector(
                            prefs = prefs.list,
                            onChange = { onUpdate(prefs.copy(list = it)) },
                        )
                    }
                }
                DrivePreviewSlot.Details -> when (mode) {
                    DriveAppearanceMode.LIST -> DetailFieldsChipGroup(
                        selected = prefs.list.detailFields,
                        onToggle = { field ->
                            onUpdate(
                                prefs.copy(
                                    list = prefs.list.copy(
                                        detailFields = toggleDetailField(prefs.list.detailFields, field),
                                    ),
                                ),
                            )
                        },
                    )
                    DriveAppearanceMode.GRID -> CardDetailsInspector(
                        showTitle = prefs.grid.showTitle,
                        showDetailLine = prefs.grid.showDetailLine,
                        detailFields = prefs.grid.detailFields,
                        onShowTitle = { onUpdate(prefs.copy(grid = prefs.grid.copy(showTitle = it))) },
                        onShowDetailLine = { onUpdate(prefs.copy(grid = prefs.grid.copy(showDetailLine = it))) },
                        onToggleField = { field ->
                            onUpdate(
                                prefs.copy(
                                    grid = prefs.grid.copy(
                                        detailFields = toggleDetailField(prefs.grid.detailFields, field),
                                    ),
                                ),
                            )
                        },
                    )
                    DriveAppearanceMode.GALLERY -> CardDetailsInspector(
                        showTitle = prefs.gallery.showTitle,
                        showDetailLine = prefs.gallery.showDetailLine,
                        detailFields = prefs.gallery.detailFields,
                        onShowTitle = { onUpdate(prefs.copy(gallery = prefs.gallery.copy(showTitle = it))) },
                        onShowDetailLine = { onUpdate(prefs.copy(gallery = prefs.gallery.copy(showDetailLine = it))) },
                        onToggleField = { field ->
                            onUpdate(
                                prefs.copy(
                                    gallery = prefs.gallery.copy(
                                        detailFields = toggleDetailField(prefs.gallery.detailFields, field),
                                    ),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * 互斥单选的 M3 Expressive 连体 `OutlinedToggleButton` 分行组：
 * 每行内部严格使用 [connectedToggleShapes] 与 `ConnectedSpaceBetween`，不带多选对勾，兼顾桌面紧凑与窄屏触控热区。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ConnectedSingleSelectRows(
    rows: List<List<T>>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val minHeight = if (isDesktopLayout()) 36.dp else 40.dp
    val toggleColors = OutlinedToggleButtonDefaults.colors(
        containerColor = if (isEInk) Color.White else Color.Transparent,
        contentColor = if (isEInk) Color.Black else colors.onSurfaceVariant,
        checkedContainerColor = if (isEInk) Color.Black else colors.secondaryContainer,
        checkedContentColor = if (isEInk) Color.White else colors.onSecondaryContainer,
    )
    Column(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                rowItems.forEachIndexed { index, item ->
                    val checked = item == selected
                    OutlinedToggleButton(
                        checked = checked,
                        onCheckedChange = { if (!checked) onSelect(item) },
                        shapes = connectedToggleShapes(index, rowItems.size),
                        colors = toggleColors,
                        border = if (isEInk) {
                            BorderStroke(1.dp, colors.outline)
                        } else {
                            OutlinedToggleButtonDefaults.border(enabled = true, checked = checked)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.weight(1f).heightIn(min = minHeight),
                    ) {
                        Text(
                            text = label(item),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CornerBadgeSelector(
    selected: CardBadgeKind,
    onSelect: (CardBadgeKind) -> Unit,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val perRow = if (widthPx == 0 || with(density) { widthPx.toDp() } >= 480.dp) 4 else 3
    val rows = remember(perRow) { CardBadgeKind.entries.chunked(perRow) }
    ConnectedSingleSelectRows(
        rows = rows,
        selected = selected,
        label = { badgeKindLabel(it) },
        onSelect = onSelect,
        modifier = Modifier.onSizeChanged { widthPx = it.width },
    )
}

@Composable
private fun FolderStyleInspector(
    selected: FolderStyle,
    onSelect: (FolderStyle) -> Unit,
) {
    val skeuomorphicLabel = stringResource(Res.string.drive_custom_folder_skeuomorphic)
    val minimalLabel = stringResource(Res.string.drive_custom_folder_minimal)
    SettingsSegmentedChoice(
        options = FolderStyle.entries,
        selected = selected,
        label = { folderStyle ->
            when (folderStyle) {
                FolderStyle.SKEUOMORPHIC -> skeuomorphicLabel
                FolderStyle.MINIMAL_ICON -> minimalLabel
            }
        },
        onSelect = onSelect,
        fill = true,
    )
}

@Composable
private fun ListTitleRowInspector(
    prefs: ListViewStylePrefs,
    onChange: (ListViewStylePrefs) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SettingsSwitchRow(
            icon = Icons.Outlined.Subtitles,
            title = stringResource(Res.string.drive_custom_show_secondary_name),
            supporting = null,
            checked = prefs.showSecondaryName,
            onCheckedChange = { onChange(prefs.copy(showSecondaryName = it)) },
        )
        SettingsSwitchRow(
            icon = Icons.Outlined.Label,
            title = stringResource(Res.string.drive_custom_show_parsed_tags),
            supporting = null,
            checked = prefs.showParsedTags,
            onCheckedChange = { onChange(prefs.copy(showParsedTags = it)) },
        )
        SettingsSwitchRow(
            icon = Icons.Outlined.ViewAgenda,
            title = stringResource(Res.string.drive_custom_compact_density),
            supporting = null,
            checked = prefs.compactDensity,
            onCheckedChange = { onChange(prefs.copy(compactDensity = it)) },
        )
    }
}

@Composable
private fun CardDetailsInspector(
    showTitle: Boolean,
    showDetailLine: Boolean,
    detailFields: List<DetailField>,
    onShowTitle: (Boolean) -> Unit,
    onShowDetailLine: (Boolean) -> Unit,
    onToggleField: (DetailField) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsSwitchRow(
            icon = Icons.Outlined.Title,
            title = stringResource(Res.string.drive_custom_show_title),
            supporting = null,
            checked = showTitle,
            onCheckedChange = onShowTitle,
        )
        SettingsSwitchRow(
            icon = Icons.Outlined.Subtitles,
            title = stringResource(Res.string.drive_custom_show_detail_line),
            supporting = null,
            checked = showDetailLine,
            onCheckedChange = onShowDetailLine,
        )
        DependentRow(visible = showDetailLine) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.drive_custom_detail_fields_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DetailFieldsChipGroup(
                    selected = detailFields,
                    onToggle = onToggleField,
                )
            }
        }
    }
}

/** 详情行字段支持多选叠加，遵循 M3 筛选芯片组（`FilterChip` + 勾选图标）规范。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailFieldsChipGroup(
    selected: List<DetailField>,
    onToggle: (DetailField) -> Unit,
) {
    val isEInk = LocalAppearance.current.isEInk
    val colors = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DetailField.entries.forEach { field ->
            val isSelected = field in selected
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(field) },
                label = { Text(detailFieldLabel(field), maxLines = 1) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
                colors = if (isEInk) {
                    FilterChipDefaults.filterChipColors(
                        containerColor = Color.White,
                        labelColor = Color.Black,
                        selectedContainerColor = Color.Black,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White,
                    )
                } else {
                    FilterChipDefaults.filterChipColors()
                },
                border = if (isEInk) {
                    BorderStroke(1.dp, colors.outline)
                } else {
                    FilterChipDefaults.filterChipBorder(enabled = true, selected = isSelected)
                },
            )
        }
    }
}

@Composable
internal fun slotLabel(slot: DrivePreviewSlot, mode: DriveAppearanceMode): String = when (slot) {
    is DrivePreviewSlot.Corner -> cornerPositionLabel(slot.position)
    DrivePreviewSlot.Folder -> stringResource(Res.string.drive_custom_section_folder)
    DrivePreviewSlot.TitleRow -> stringResource(Res.string.drive_custom_slot_title_row)
    DrivePreviewSlot.Details -> if (mode == DriveAppearanceMode.LIST) {
        stringResource(Res.string.drive_custom_detail_fields_label)
    } else {
        stringResource(Res.string.drive_custom_section_text)
    }
}

@Composable
internal fun cornerPositionLabel(position: CornerPosition): String = when (position) {
    CornerPosition.TOP_START -> stringResource(Res.string.drive_custom_corner_top_start)
    CornerPosition.TOP_END -> stringResource(Res.string.drive_custom_corner_top_end)
    CornerPosition.BOTTOM_START -> stringResource(Res.string.drive_custom_corner_bottom_start)
    CornerPosition.BOTTOM_END -> stringResource(Res.string.drive_custom_corner_bottom_end)
}

@Composable
internal fun badgeKindLabel(kind: CardBadgeKind): String = when (kind) {
    CardBadgeKind.NONE -> stringResource(Res.string.drive_custom_badge_none)
    CardBadgeKind.DURATION -> stringResource(Res.string.drive_custom_badge_duration)
    CardBadgeKind.RESOLUTION -> stringResource(Res.string.drive_custom_badge_resolution)
    CardBadgeKind.PARSED_TAGS -> stringResource(Res.string.drive_custom_badge_parsed_tags)
    CardBadgeKind.FILE_SIZE -> stringResource(Res.string.drive_custom_badge_file_size)
    CardBadgeKind.STATUS_BADGES -> stringResource(Res.string.drive_custom_badge_status)
    CardBadgeKind.EXTENSION -> stringResource(Res.string.drive_custom_badge_extension)
}

@Composable
internal fun detailFieldLabel(field: DetailField): String = when (field) {
    DetailField.SIZE -> stringResource(Res.string.drive_custom_field_size)
    DetailField.DURATION -> stringResource(Res.string.drive_custom_field_duration)
    DetailField.ADDED_TIME -> stringResource(Res.string.drive_custom_field_added_time)
    DetailField.PARSED_TAGS -> stringResource(Res.string.drive_custom_field_parsed_tags)
    DetailField.ITEM_COUNT -> stringResource(Res.string.drive_custom_field_item_count)
    DetailField.EXTENSION -> stringResource(Res.string.drive_custom_field_extension)
    DetailField.VAULT_STATUS -> stringResource(Res.string.drive_custom_field_vault_status)
}
