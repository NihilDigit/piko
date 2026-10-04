package dev.piko.shared.rename

/**
 * 批量重命名使用说明的一条：一句话加一个改前改后的例子，写给不会写正则的人。例子用块本身写成，
 * [BlockGuideTest] 逐条跑过：块或改名规则改了、例子跟着失效时会被发现。放在 shared 而不是界面里，就是为了能测。
 *
 * 顺序照 PowerRename 文档与 RegexOne：从读者本来就会的查找替换文字起步，再到描述一类内容、取出重排，
 * 正则放在最后一句。不出现「捕获」「元字符」这类术语，只用界面上的块名与 ①②。先前按功能讲（块的种类、取出、换序），
 * 读者反馈看不懂。
 */
class BlockGuideEntry(val title: String, val body: String, val example: BlockGuideExample? = null)

/** 用 [find] 与 [replace] 两条块，把 [input] 改成 [result]。 */
class BlockGuideExample(val find: List<FindBlock>, val replace: List<ReplaceBlock>, val input: String, val result: String)

val BlockGuide: List<BlockGuideEntry> = listOf(
    BlockGuideEntry(
        title = "查找并替换",
        body = "在查找条输入要查找的文字，在替换条输入新文字。替换条留空将删除匹配内容。",
        example = BlockGuideExample(listOf(FindBlock.Text("[字幕组] ")), emptyList(), "[字幕组] 名字 - 01", "名字 - 01"),
    ),
    BlockGuideEntry(
        title = "匹配一类文字",
        body = "各文件的文字不同时，可使用「数字」「括号及内容」等块。下例可同时匹配 [1080p] 和 [720p]。",
        example = BlockGuideExample(
            listOf(FindBlock.Text(" "), FindBlock.Bracketed(BracketKind.SQUARE)),
            emptyList(),
            "名字 - 01 [1080p]",
            "名字 - 01",
        ),
    ),
    BlockGuideEntry(
        title = "调整顺序",
        body = "在块上打开「记为片段」，匹配内容将依次编号为 ①②。在替换条中按所需顺序放置「片段①」「片段②」。",
        example = BlockGuideExample(
            listOf(FindBlock.AnyText(until = ' ', capture = true), FindBlock.Text(" - "), FindBlock.Digits(capture = true)),
            listOf(ReplaceBlock.Text("第"), ReplaceBlock.Piece(2), ReplaceBlock.Text("集 "), ReplaceBlock.Piece(1)),
            "名字 - 01",
            "第01集 名字",
        ),
    ),
    BlockGuideEntry(
        title = "添加序号",
        body = "在替换条中添加「序号」，将按预览顺序编号，可设置起始值和位数。如熟悉正则表达式，可切换到「正则表达式」直接编写。",
        example = BlockGuideExample(
            listOf(FindBlock.Start, FindBlock.AnyText(), FindBlock.End),
            listOf(ReplaceBlock.Text("旅行 "), ReplaceBlock.Counter(start = 1, padding = 2)),
            "IMG_4421",
            "旅行 01",
        ),
    ),
)
