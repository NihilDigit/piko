package dev.piko.shared.rename

/**
 * 积木模式的起手式：按要做的事（加前缀、编号）给出一组拼好的积木，选了即换上，之后照常在积木上改。
 *
 * 用户反馈要「前缀加原名」「顺序重命名」，这些积木早就拼得出，难在想到「查找开头、替换成文字」就是加前缀。
 * 起手式只是预填，不另设重命名模式：模式一多，预览、撤销与最近使用都要按模式分开记。
 *
 * [example] 由 [BlockGuideTest] 逐条跑过，替换条里的文字是例子填上的；界面上换上的是 [replace]，
 * 需要文字的那一段留给积木条末尾的输入框，[RenamePreset.focusReplace] 为真时把焦点交给它。
 */
class RenamePreset(
    val label: String,
    val find: List<FindBlock>,
    val replace: List<ReplaceBlock>,
    val focusReplace: Boolean,
    val example: BlockGuideExample,
)

val RenamePresets: List<RenamePreset> = listOf(
    RenamePreset(
        label = "加前缀",
        find = listOf(FindBlock.Start),
        replace = emptyList(),
        focusReplace = true,
        example = BlockGuideExample(listOf(FindBlock.Start), listOf(ReplaceBlock.Text("合集 ")), "名字 - 01", "合集 名字 - 01"),
    ),
    RenamePreset(
        label = "加后缀",
        // 范围默认只含扩展名之前的部分，结尾即扩展名之前
        find = listOf(FindBlock.End),
        replace = emptyList(),
        focusReplace = true,
        example = BlockGuideExample(listOf(FindBlock.End), listOf(ReplaceBlock.Text(" 完")), "名字 - 01", "名字 - 01 完"),
    ),
    RenamePreset(
        label = "改为编号",
        find = listOf(FindBlock.Start, FindBlock.AnyText(), FindBlock.End),
        replace = listOf(ReplaceBlock.Counter(start = 1, padding = 2)),
        focusReplace = false,
        example = BlockGuideExample(
            listOf(FindBlock.Start, FindBlock.AnyText(), FindBlock.End),
            listOf(ReplaceBlock.Text("第"), ReplaceBlock.Counter(start = 1, padding = 2), ReplaceBlock.Text("集")),
            "[字幕组] 名字 - 01 [1080p]",
            "第01集",
        ),
    ),
)
