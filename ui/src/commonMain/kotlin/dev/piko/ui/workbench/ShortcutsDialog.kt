package dev.piko.ui.workbench

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.piko.ui.components.PikoDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.ui.platform.ShortcutModifier

/**
 * 快捷键一览（F1 或主修饰键 + /）。快捷键散在各处的提示里，这里汇到一起；
 * 改了哪处的快捷键，这张表跟着改。按键写法随平台：mac 上是 ⌘ 与 ⌥。
 */
@Composable
internal fun ShortcutsDialog(modifier: ShortcutModifier, onDismiss: () -> Unit) {
    val mac = modifier == ShortcutModifier.Command
    val primary = if (mac) "⌘" else "Ctrl+"
    val groups = listOf(
        tr("全局", "Global", "全体", "전역", "全域") to listOf(
            "${primary}K" to tr("命令面板：前往文件夹或执行命令", "Command palette: go to folder or run a command", "コマンドパレット：フォルダーへ移動またはコマンドを実行", "명령 팔레트: 폴더 이동 또는 명령 실행", "命令面板：前往資料夾或執行命令"),
            "${primary}1 / 2 / 3" to tr("切换至文件、任务或我的", "Switch to Files, Tasks or Me", "ファイル・タスク・マイページへ切り替え", "파일, 작업, 내 정보로 전환", "切換至檔案、任務或我的"),
            tr("F1 或 $primary/", "F1 or $primary/", "F1 または $primary/", "F1 또는 $primary/", "F1 或 $primary/") to tr("快捷键一览", "Keyboard shortcuts", "キーボードショートカット", "키보드 단축키", "快速鍵一覽"),
            "${primary}," to tr("设置", "Settings", "設定", "설정", "設定"),
            "${primary}B" to tr("收起或展开侧边栏", "Collapse or expand sidebar", "サイドバーを折りたたむ・展開", "사이드바 접기 또는 펼치기", "收合或展開側邊欄"),
        ),
        tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟") to listOf(
            tr("方向键", "Arrow keys", "矢印キー", "방향 키", "方向鍵") to tr("在条目间移动", "Move between items", "項目間を移動", "항목 간 이동", "在項目間移動"),
            (if (mac) "⌘↓" else "Enter") to tr("打开", "Open", "開く", "열기", "開啟"),
            (if (mac) "⌘↑" else "Alt+↑") to tr("上一级", "Parent folder", "上の階層", "상위 폴더", "上一層"),
            (if (mac) "⌘[ / ⌘]" else tr("Backspace 或 Alt+← / Alt+→", "Backspace or Alt+← / Alt+→", "Backspace または Alt+← / Alt+→", "Backspace 또는 Alt+← / Alt+→", "Backspace 或 Alt+← / Alt+→")) to tr("后退或前进（支持鼠标侧键）", "Back or forward (mouse side buttons supported)", "戻る・進む（マウスのサイドボタン対応）", "뒤로 또는 앞으로 (마우스 측면 버튼 지원)", "上一頁或下一頁（支援滑鼠側鍵）"),
            (if (mac) "⌘L" else tr("Ctrl+L、Alt+D 或 F4", "Ctrl+L, Alt+D or F4", "Ctrl+L、Alt+D または F4", "Ctrl+L, Alt+D 또는 F4", "Ctrl+L、Alt+D 或 F4")) to tr("在地址栏输入路径", "Enter path in address bar", "アドレスバーにパスを入力", "주소 표시줄에 경로 입력", "在網址列輸入路徑"),
            "Tab" to tr("在地址栏中补全当前路径段", "Complete current path segment in address bar", "アドレスバーで現在のパス区間を補完", "주소 표시줄에서 현재 경로 구간 자동 완성", "在位址列補齊目前路徑區段"),
            "Delete" to tr("在地址栏中移除所选历史记录", "Remove selected recent entry in address bar", "アドレスバーで選択した履歴を削除", "주소 표시줄에서 선택한 최근 항목 제거", "在位址列移除所選最近記錄"),
            (if (mac) "⌘⇧E" else "Ctrl+Shift+E") to tr("展开或收起目录图", "Expand or collapse folder map", "フォルダーマップを展開・折りたたむ", "폴더 맵 펼치기 또는 접기", "展開或收合目錄圖"),
            "${primary}F" to tr("搜索", "Search", "検索", "검색", "搜尋"),
            "F5" to tr("刷新", "Refresh", "更新", "새로고침", "重新整理"),
            tr("${primary}滚轮", "${primary}Wheel", "${primary}ホイール", "${primary}휠", "${primary}滾輪") to tr("缩放视图：切换列表与网格尺寸，或调整瀑布流缩略图大小", "Zoom views: step through list and grid sizes, or resize gallery tiles", "表示を拡大・縮小：リストとグリッドのサイズ切り替え、ギャラリーのタイルサイズ変更", "보기 확대 및 축소: 목록과 격자 크기 전환 또는 갤러리 타일 크기 조정", "縮放檢視：切換清單與網格尺寸，或調整瀑布流縮圖大小"),
            "${primary}A" to tr("全选", "Select all", "すべて選択", "모두 선택", "全選"),
            (if (mac) "⌘⌫" else "Delete") to tr("将所选条目移入回收站", "Move selected items to trash", "選択した項目をゴミ箱へ移動", "선택한 항목을 휴지통으로 이동", "將所選項目移至資源回收筒"),
            (if (mac) tr("回车 或 F2", "Enter or F2", "Enter または F2", "Enter 또는 F2", "Enter 或 F2") else "F2") to tr("重命名（多选时为批量重命名）", "Rename (batch rename when multiple items selected)", "名前を変更（複数選択時は一括変更）", "이름 변경 (여러 항목 선택 시 일괄 변경)", "重新命名（多選時為批次重新命名）"),
            "${primary}Z" to tr("撤销上一次移动、删除、重命名或归档改动", "Undo the last move, deletion, rename or archive", "前回の移動・削除・改名・アーカイブを元に戻す", "이전 이동, 삭제, 이름 변경 또는 보관 실행 취소", "復原上一次移動、刪除、重新命名或歸檔變更"),
            (if (mac) "⌘I" else tr("Alt+Enter 或 Ctrl+I", "Alt+Enter or Ctrl+I", "Alt+Enter または Ctrl+I", "Alt+Enter 또는 Ctrl+I", "Alt+Enter 或 Ctrl+I")) to tr("属性", "Properties", "プロパティ", "속성", "屬性"),
            tr("菜单键 或 Shift+F10", "Menu key or Shift+F10", "メニューキー または Shift+F10", "메뉴 키 또는 Shift+F10", "選單鍵 或 Shift+F10") to tr("操作菜单", "Actions menu", "操作メニュー", "작업 메뉴", "操作選單"),
        ),
        tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結") to listOf(
            "${primary}Enter" to tr("保存", "Save", "保存", "저장", "儲存"),
            "Esc" to tr("关闭面板或返回链接列表", "Close panel or return to link list", "パネルを閉じる、またはリンク一覧に戻る", "패널 닫기 또는 링크 목록으로 돌아가기", "關閉面板或返回連結清單"),
        ),
        tr("任务", "Tasks", "タスク", "작업", "任務") to listOf(
            tr("单击 / 双击", "Click / double-click", "クリック / ダブルクリック", "클릭 / 두 번 클릭", "按一下 / 按兩下") to tr("选择或执行默认操作", "Select or run primary action", "選択、または主な操作を実行", "선택 또는 기본 작업 실행", "選取或執行主要操作"),
            tr("${primary.removeSuffix("+")} 点选 / Shift 点选", "${primary.removeSuffix("+")}-click / Shift-click", "${primary.removeSuffix("+")} クリック / Shift クリック", "${primary.removeSuffix("+")} 클릭 / Shift 클릭", "${primary.removeSuffix("+")} 點選 / Shift 點選") to tr("加选、连选", "Add or range-select", "追加選択・範囲選択", "추가 및 범위 선택", "追加選取、範圍選取"),
            "${primary}A" to tr("全选当前可见任务", "Select all visible tasks", "表示中のタスクをすべて選択", "현재 표시된 작업 모두 선택", "全選目前可見任務"),
            (if (mac) tr("⌘⌫ 或 Delete", "⌘⌫ or Delete", "⌘⌫ または Delete", "⌘⌫ 또는 Delete", "⌘⌫ 或 Delete") else "Delete") to tr("删除所选", "Delete selection", "選択した項目を削除", "선택 항목 삭제", "刪除所選項目"),
            "Esc" to tr("取消选择", "Deselect", "選択を解除", "선택 해제", "取消選取"),
        ),
        tr("标签页", "Tabs", "タブ", "탭", "分頁") to listOf(
            "${primary}T" to tr("新建标签页", "New tab", "新しいタブ", "새 탭", "新增分頁"),
            "${primary}W" to tr("关闭标签页", "Close tab", "タブを閉じる", "탭 닫기", "關閉分頁"),
            "Ctrl+Tab / Ctrl+Shift+Tab" to tr("下一个、上一个标签页", "Next or previous tab", "次・前のタブ", "다음 또는 이전 탭", "下一個、上一個分頁"),
            "${primary}Shift+PageUp / PageDown" to tr("向前、向后移动标签页", "Move tab forward or backward", "タブを前後に移動", "탭을 앞 또는 뒤로 이동", "向前、向後移動分頁"),
            tr("鼠标中键点击文件夹", "Middle-click a folder", "フォルダーを中クリック", "폴더를 마우스 가운데 버튼으로 클릭", "以滑鼠中鍵點擊資料夾") to tr("在后台新标签页中打开", "Open in background tab", "バックグラウンドの新しいタブで開く", "백그라운드 새 탭에서 열기", "在背景新分頁中開啟"),
        ),
        tr("鼠标", "Mouse", "マウス", "마우스", "滑鼠") to listOf(
            tr("单击 / 双击", "Click / double-click", "クリック / ダブルクリック", "클릭 / 두 번 클릭", "按一下 / 按兩下") to tr("选中、打开", "Select or open", "選択・開く", "선택 또는 열기", "選取、開啟"),
            tr("右键", "Right-click", "右クリック", "오른쪽 클릭", "滑鼠右鍵") to tr("操作菜单", "Actions menu", "操作メニュー", "작업 메뉴", "操作選單"),
            tr("${primary.removeSuffix("+")} 点选 / Shift 点选", "${primary.removeSuffix("+")}-click / Shift-click", "${primary.removeSuffix("+")} クリック / Shift クリック", "${primary.removeSuffix("+")} 클릭 / Shift 클릭", "${primary.removeSuffix("+")} 點選 / Shift 點選") to tr("加选、连选", "Add or range-select", "追加選択・範囲選択", "추가 및 범위 선택", "追加選取、範圍選取"),
            tr("在空白处拖动", "Drag on empty space", "空白部分をドラッグ", "빈 공간에서 드래그", "在空白處拖曳") to tr("框选", "Box select", "範囲選択", "영역 선택", "框選"),
            tr("将项目拖放至文件夹", "Drag items onto a folder", "項目をフォルダーへドラッグ", "항목을 폴더로 드래그", "將項目拖放至資料夾") to tr("移动（按住 ${if (mac) "⌥" else "Ctrl"} 拖放为复制）", "Move (hold ${if (mac) "⌥" else "Ctrl"} to copy)", "移動（${if (mac) "⌥" else "Ctrl"} を押しながらでコピー）", "이동 (${if (mac) "⌥" else "Ctrl"} 키를 누른 채로 드래그하면 복사)", "移動（按住 ${if (mac) "⌥" else "Ctrl"} 拖放為複製）"),
        ),
        tr("播放器", "Player", "プレーヤー", "플레이어", "播放器") to listOf(
            tr("空格 / K", "Space / K", "スペース / K", "스페이스 / K", "空白鍵 / K") to tr("播放、暂停", "Play or pause", "再生・一時停止", "재생 또는 일시 중지", "播放、暫停"),
            tr("← / → 或 J / L", "← / → or J / L", "← / → または J / L", "← / → 또는 J / L", "← / → 或 J / L") to tr("按步长快退或快进", "Seek backward or forward by step", "設定間隔で巻き戻し・早送り", "설정한 간격만큼 뒤로 또는 앞으로 탐색", "依步長快退或快進"),
            tr("按住 ← / →", "Hold ← / →", "← / → を長押し", "← / → 길게 누르기", "按住 ← / →") to tr("快退、快进", "Rewind or fast forward", "巻き戻し・早送り", "되감기 또는 빨리 감기", "快退、快進"),
            "Shift+← / →" to tr("后退、快进 1 分钟", "Seek back or forward one minute", "1分戻る・進む", "1분 뒤로 또는 앞으로", "後退、快進 1 分鐘"),
            tr("0 至 9", "0 to 9", "0 ～ 9", "0~9", "0 至 9") to tr("跳转至对应百分比进度（0%–90%）", "Seek to 0%–90% of the video", "動画の0%～90%の位置へ移動", "동영상의 0%~90% 위치로 이동", "跳轉至對應百分比進度（0%–90%）"),
            "Home" to tr("回到开头", "Go to start", "先頭に戻る", "처음으로 이동", "回到開頭"),
            tr("↑ / ↓ 或滚轮", "↑ / ↓ or mouse wheel", "↑ / ↓ またはホイール", "↑ / ↓ 또는 마우스 휠", "↑ / ↓ 或滾輪") to tr("音量", "Volume", "音量", "볼륨", "音量"),
            "M" to tr("静音", "Mute", "ミュート", "음소거", "靜音"),
            "[ / ]" to tr("减速、加速", "Decrease or increase speed", "減速・加速", "감속 또는 가속", "減速、加速"),
            "Backspace" to tr("恢复原速", "Reset playback speed", "通常速度に戻す", "기본 속도로 복원", "還原原速"),
            "C" to tr("开关字幕", "Toggle subtitles", "字幕を切り替え", "자막 켜기 또는 끄기", "開啟或關閉字幕"),
            "PageUp / PageDown" to tr("上一集、下一集", "Previous or next episode", "前・次のエピソード", "이전 또는 다음 에피소드", "上一集、下一集"),
            "F" to tr("全屏", "Fullscreen", "全画面", "전체 화면", "全螢幕"),
            "R" to tr("画面顺时针旋转 90 度", "Rotate video 90° clockwise", "映像を右に90度回転", "화면을 시계 방향으로 90도 회전", "畫面順時針旋轉 90 度"),
        ),
    )
    PikoDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_got_it)) } },
        title = { Text(stringResource(Res.string.text_keyboard_shortcuts_alt)) },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 520.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for ((group, rows) in groups) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        for ((keys, action) in rows) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 两栏按比例分，不按内容撑：原先键位一栏只设了最小宽度，最长的「Backspace 或 Alt+← / Alt+→」
                                // 超出后那一行的说明往右错开。窄窗口里键位放不下时在键帽里折行
                                KeyCap(keys, Modifier.weight(0.4f))
                                Text(action, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f).padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun KeyCap(keys: String, modifier: Modifier = Modifier) {
    Row(modifier) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Text(
                keys,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
