package dev.piko.shared.rename

import dev.piko.shared.i18n.tr

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

val BlockGuide: List<BlockGuideEntry> get() = listOf(
    BlockGuideEntry(
        title = tr("查找并替换", "Find and replace", "検索と置換", "찾기 및 바꾸기", "尋找與取代"),
        body = tr("在查找条输入要查找的文字，在替换条输入新文字。替换条留空将删除匹配内容。", "Enter the text to find in Find and the new text in Replace. Leave Replace empty to delete matches.", "検索欄に探す文字、置換欄に新しい文字を入力します。置換欄を空にすると、一致した文字を削除します。", "찾기 입력란에 찾을 텍스트를, 바꾸기 입력란에 새 텍스트를 입력하세요. 바꾸기를 비워 두면 일치한 내용을 삭제합니다.", "在尋找欄輸入要尋找的文字，在取代欄輸入新文字。取代欄留空將刪除符合的內容。"),
        example = BlockGuideExample(listOf(FindBlock.Text(tr("[字幕组] ", "[Group] ", "[字幕グループ] ", "[자막 그룹] ", "[字幕組] "))), emptyList(), tr("[字幕组] 名字 - 01", "[Group] Name - 01", "[字幕グループ] 名前 - 01", "[자막 그룹] 이름 - 01", "[字幕組] 名字 - 01"), tr("名字 - 01", "Name - 01", "名前 - 01", "이름 - 01", "名字 - 01")),
    ),
    BlockGuideEntry(
        title = tr("匹配一类文字", "Match a text pattern", "文字のパターンに一致", "텍스트 패턴 찾기", "符合一類文字"),
        body = tr("各文件的文字不同时，可使用「数字」「括号及内容」等块。下例可同时匹配 [1080p] 和 [720p]。", "Use blocks such as Digits or Brackets and contents to match varying text. The example matches both [1080p] and [720p].", "ファイルごとに文字が異なる場合は、「数字」「括弧とその内容」などのブロックを使います。下の例は [1080p] と [720p] の両方に一致します。", "파일마다 텍스트가 다르면 '숫자', '괄호 및 내용' 등의 블록을 사용하세요. 아래 예시는 [1080p]와 [720p]에 모두 일치합니다.", "各檔案的文字不同時，可使用「數字」「括號及內容」等區塊。下例可同時符合 [1080p] 和 [720p]。"),
        example = BlockGuideExample(
            listOf(FindBlock.Text(" "), FindBlock.Bracketed(BracketKind.SQUARE)),
            emptyList(),
            tr("名字 - 01 [1080p]", "Name - 01 [1080p]", "名前 - 01 [1080p]", "이름 - 01 [1080p]", "名字 - 01 [1080p]"),
            tr("名字 - 01", "Name - 01", "名前 - 01", "이름 - 01", "名字 - 01"),
        ),
    ),
    BlockGuideEntry(
        title = tr("调整顺序", "Reorder", "順序を変更", "순서 변경", "調整順序"),
        body = tr("在块上打开「记为片段」，匹配内容将依次编号为 ①②。在替换条中按所需顺序放置「片段①」「片段②」。", "Enable Capture on a block to number matches ①②. Arrange Capture ① and Capture ② in the desired order in Replace.", "ブロックの「キャプチャ」を有効にすると、一致した内容に①②の番号が付きます。置換欄に「キャプチャ①」「キャプチャ②」を好きな順に並べます。", "블록에서 '캡처'를 켜면 일치한 내용에 ①② 번호가 붙습니다. 바꾸기 입력란에 '캡처①', '캡처②'를 원하는 순서로 놓으세요.", "在區塊上開啟「記為片段」，符合的內容將依序編號為 ①②。在取代欄中依所需順序放置「片段①」「片段②」。"),
        example = BlockGuideExample(
            listOf(FindBlock.AnyText(until = ' ', capture = true), FindBlock.Text(" - "), FindBlock.Digits(capture = true)),
            listOf(ReplaceBlock.Text(tr("第", "Episode ", "第", "", "第")), ReplaceBlock.Piece(2), ReplaceBlock.Text(tr("集 ", " ", "話 ", "화 ", "集 ")), ReplaceBlock.Piece(1)),
            tr("名字 - 01", "Name - 01", "名前 - 01", "이름 - 01", "名字 - 01"),
            tr("第01集 名字", "Episode 01 Name", "第01話 名前", "01화 이름", "第01集 名字"),
        ),
    ),
    BlockGuideEntry(
        title = tr("添加序号", "Add numbering", "連番を追加", "번호 추가", "新增序號"),
        body = tr("在替换条中添加「序号」，将按预览顺序编号，可设置起始值和位数。如熟悉正则表达式，可切换到「正则表达式」直接编写。", "Add a Counter in Replace to number files in preview order. Set the start and digit count. You can also switch to Regular expression to write a pattern directly.", "置換欄に「連番」を追加すると、プレビュー順に番号を付けられます。開始値と桁数を設定できます。正規表現に慣れている場合は、「正規表現」に切り替えて直接入力できます。", "바꾸기 입력란에 '번호'를 추가하면 미리 보기 순서대로 번호를 매깁니다. 시작값과 자릿수를 설정할 수 있습니다. 정규식에 익숙하다면 '정규식'으로 전환하여 직접 작성할 수 있습니다.", "在取代欄中新增「序號」，將依預覽順序編號，可設定起始值和位數。若熟悉正規表示式，可切換至「正規表示式」直接編寫。"),
        example = BlockGuideExample(
            listOf(FindBlock.Start, FindBlock.AnyText(), FindBlock.End),
            listOf(ReplaceBlock.Text(tr("旅行 ", "Trip ", "旅行 ", "여행 ", "旅行 ")), ReplaceBlock.Counter(start = 1, padding = 2)),
            "IMG_4421",
            tr("旅行 01", "Trip 01", "旅行 01", "여행 01", "旅行 01"),
        ),
    ),
)
