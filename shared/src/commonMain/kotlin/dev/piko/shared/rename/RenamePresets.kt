package dev.piko.shared.rename

import dev.piko.shared.i18n.tr

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

val RenamePresets: List<RenamePreset> get() = listOf(
    RenamePreset(
        label = tr("添加前缀", "Add prefix", "接頭辞を追加", "접두사 추가"),
        find = listOf(FindBlock.Start),
        replace = emptyList(),
        focusReplace = true,
        example = BlockGuideExample(listOf(FindBlock.Start), listOf(ReplaceBlock.Text(tr("合集 ", "Collection ", "コレクション ", "모음 "))), tr("名字 - 01", "Name - 01", "名前 - 01", "이름 - 01"), tr("合集 名字 - 01", "Collection Name - 01", "コレクション 名前 - 01", "모음 이름 - 01")),
    ),
    RenamePreset(
        label = tr("添加后缀", "Add suffix", "接尾辞を追加", "접미사 추가"),
        // 范围默认只含扩展名之前的部分，结尾即扩展名之前
        find = listOf(FindBlock.End),
        replace = emptyList(),
        focusReplace = true,
        example = BlockGuideExample(listOf(FindBlock.End), listOf(ReplaceBlock.Text(tr(" 完", " Complete", " 完", " 완결"))), tr("名字 - 01", "Name - 01", "名前 - 01", "이름 - 01"), tr("名字 - 01 完", "Name - 01 Complete", "名前 - 01 完", "이름 - 01 완결")),
    ),
    RenamePreset(
        label = tr("改为序号", "Replace with numbers", "連番に変更", "번호로 변경"),
        find = listOf(FindBlock.Start, FindBlock.AnyText(), FindBlock.End),
        replace = listOf(ReplaceBlock.Counter(start = 1, padding = 2)),
        focusReplace = true,
        example = BlockGuideExample(
            listOf(FindBlock.Start, FindBlock.AnyText(), FindBlock.End),
            listOf(ReplaceBlock.Text(tr("第", "Episode ", "第", "")), ReplaceBlock.Counter(start = 1, padding = 2), ReplaceBlock.Text(tr("集", "", "話", "화"))),
            tr("[字幕组] 名字 - 01 [1080p]", "[Group] Name - 01 [1080p]", "[字幕グループ] 名前 - 01 [1080p]", "[자막 그룹] 이름 - 01 [1080p]"),
            tr("第01集", "Episode 01", "第01話", "01화"),
        ),
    ),
)
