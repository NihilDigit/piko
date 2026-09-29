package dev.piko.shared.rename

/**
 * 积木用法说明的一条，写给不会写正则的人，按要做的事组织。例子用积木本身写成，[BlockGuideTest] 逐条跑过：
 * 积木或改名规则改了、例子跟着失效时会被发现。放在 shared 而不是界面里，就是为了能测。
 */
class BlockGuideEntry(val title: String, val body: String, val example: BlockGuideExample? = null)

/** 用 [find] 与 [replace] 两条积木，把 [input] 改成 [result]。 */
class BlockGuideExample(val find: List<FindBlock>, val replace: List<ReplaceBlock>, val input: String, val result: String)

val BlockGuide: List<BlockGuideEntry> = listOf(
    BlockGuideEntry(
        title = "查找条与替换条",
        body = "查找条里的积木从左到右拼成要找的样子，替换条拼成换成的样子。找到的部分换掉，其余原样保留；替换条留空即删除。",
        example = BlockGuideExample(listOf(FindBlock.Text(" [1080p]")), emptyList(), "名字 - 01 [1080p]", "名字 - 01"),
    ),
    BlockGuideEntry(
        title = "积木的种类",
        body = "文字按原样查找；数字是一串阿拉伯数字，全角数字不算；字母是一串英文字母；任意字符尽量少地匹配，可设为到某个字符为止；" +
            "括号内容是一对括号连同其中的文字；若干之一匹配几段文字中的任一段；开头与结尾限定只在名称两端查找。",
        example = BlockGuideExample(
            listOf(FindBlock.Start, FindBlock.Bracketed(BracketKind.SQUARE), FindBlock.Text(" ")),
            emptyList(),
            "[字幕组] 名字 - 01",
            "名字 - 01",
        ),
    ),
    BlockGuideEntry(
        title = "取出与片段",
        body = "打开积木的「取出」，它找到的文字依次编为 ①②；替换条里放「片段①」，就把这段文字搬进新名称。颜色相同的是同一段。",
        example = BlockGuideExample(
            listOf(
                FindBlock.Start, FindBlock.Bracketed(BracketKind.SQUARE), FindBlock.Text(" "),
                FindBlock.AnyText(until = ' ', capture = true), FindBlock.Text(" - "), FindBlock.Digits(capture = true),
                FindBlock.Text(" "), FindBlock.Bracketed(BracketKind.SQUARE), FindBlock.End,
            ),
            listOf(ReplaceBlock.Piece(1), ReplaceBlock.Text(" 第"), ReplaceBlock.Piece(2), ReplaceBlock.Text("集")),
            "[字幕组] 名字 - 01 [1080p]",
            "名字 第01集",
        ),
    ),
    BlockGuideEntry(
        title = "序号与日期",
        body = "序号按预览里的先后编号，可设起始值、步长与位数；日期取文件的创建或修改时间。",
        example = BlockGuideExample(listOf(FindBlock.Digits()), listOf(ReplaceBlock.Counter(start = 1, padding = 2)), "名字 - 7", "名字 - 01"),
    ),
    BlockGuideEntry(
        title = "从预览直接生成",
        body = "最省事的做法：在右侧预览的原名上拖选一段（触屏上长按选词），选「删除」「替换为」或「改为编号」，积木自动生成。" +
            "各项的这一段不同时，比如集数，按位置匹配，所有项的同一位置一起改。",
    ),
    BlockGuideEntry(
        title = "修改与换序",
        body = "点积木可改参数、设为取出、删除或左右移动；鼠标按住积木可直接拖动换序，触屏上长按后拖动。" +
            "预览里原名的底色与积木同色，看得出每块积木找到了哪一段。",
    ),
    BlockGuideEntry(
        title = "更复杂的规则",
        body = "打开「正则表达式」开关可直接写正则，已拼好的积木会原样换成正则。",
    ),
)
