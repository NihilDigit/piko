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
        tr("全局", "Global", "全体", "전역") to listOf(
            "${primary}K" to tr("命令面板：跳到文件夹或执行命令", "Command palette: go to folder or run a command", "コマンドパレット：フォルダー移動・コマンド実行", "명령 팔레트: 폴더 이동 또는 명령 실행"),
            "${primary}1 / 2 / 3" to tr("切到文件、传输、我的", "Switch to Files, Tasks or Me", "ファイル・タスク・マイページへ切り替え", "파일, 작업, 내 정보로 전환"),
            tr("F1 或 $primary/", "F1 or $primary/", "F1 または $primary/", "F1 또는 $primary/") to tr("快捷键一览", "Keyboard shortcuts", "キーボードショートカット", "키보드 단축키"),
            "${primary}," to tr("设置", "Settings", "設定", "설정"),
            "${primary}B" to tr("收起或展开侧边栏", "Collapse or expand sidebar", "サイドバーを折りたたむ・展開", "사이드바 접기 또는 펼치기"),
        ),
        tr("网盘", "Drive", "ドライブ", "드라이브") to listOf(
            tr("方向键", "Arrow keys", "矢印キー", "방향 키") to tr("在条目间移动", "Move between items", "項目間を移動", "항목 간 이동"),
            (if (mac) "⌘↓" else "Enter") to tr("打开", "Open", "開く", "열기"),
            (if (mac) "⌘↑" else "Alt+↑") to tr("上一级", "Parent folder", "上の階層", "상위 폴더"),
            (if (mac) "⌘[ / ⌘]" else tr("Backspace 或 Alt+← / Alt+→", "Backspace or Alt+← / Alt+→", "Backspace または Alt+← / Alt+→", "Backspace 또는 Alt+← / Alt+→")) to tr("后退、前进（鼠标侧键也行）", "Back or forward (mouse side buttons also work)", "戻る・進む（マウスのサイドボタンも可）", "뒤로 및 앞으로 (마우스 측면 버튼도 가능)"),
            (if (mac) "⌘L" else tr("Ctrl+L、Alt+D 或 F4", "Ctrl+L, Alt+D or F4", "Ctrl+L、Alt+D または F4", "Ctrl+L, Alt+D 또는 F4")) to tr("在地址栏输入路径", "Enter path in address bar", "アドレスバーにパスを入力", "주소 표시줄에 경로 입력"),
            "Tab" to tr("地址栏中补全当前一段", "Complete current path segment", "アドレスバーの現在の区間を補完", "현재 경로 구간 자동 완성"),
            "Delete" to tr("地址栏中删除所选的最近记录", "Remove selected recent entry", "選択した最近の記録を削除", "선택한 최근 기록 삭제"),
            (if (mac) "⌘⇧E" else "Ctrl+Shift+E") to tr("展开或收起目录图", "Expand or collapse folder map", "フォルダーマップを展開・折りたたむ", "폴더 맵 펼치기 또는 접기"),
            "${primary}F" to tr("搜索", "Search", "検索", "검색"),
            "F5" to tr("刷新", "Refresh", "更新", "새로고침"),
            tr("${primary}滚轮", "${primary}Wheel", "${primary}ホイール", "${primary}휠") to tr("放大、缩小视图：列表与三档海报墙之间逐档，图库里换格子大小", "Zoom views: step through list and three poster sizes; resize gallery tiles", "表示を拡大・縮小：リストと3段階のポスター表示を切り替え、ギャラリーではタイルサイズを変更", "보기 확대 및 축소: 목록과 3단계 포스터 크기 전환, 갤러리에서는 타일 크기 변경"),
            "${primary}A" to tr("全选", "Select all", "すべて選択", "모두 선택"),
            (if (mac) "⌘⌫" else "Delete") to tr("将所选条目移入回收站", "Move selected items to trash", "選択した項目をゴミ箱へ移動", "선택한 항목을 휴지통으로 이동"),
            (if (mac) tr("回车 或 F2", "Enter or F2", "Enter または F2", "Enter 또는 F2") else "F2") to tr("重命名，选了几项时批量重命名", "Rename; batch rename multiple selections", "名前を変更。複数選択時は一括変更", "이름 변경, 여러 항목 선택 시 일괄 변경"),
            "${primary}Z" to tr("撤销上一次移动、删除、重命名或归档改动", "Undo the last move, deletion, rename or archive", "前回の移動・削除・改名・アーカイブを元に戻す", "이전 이동, 삭제, 이름 변경 또는 보관 실행 취소"),
            (if (mac) "⌘I" else tr("Alt+Enter 或 Ctrl+I", "Alt+Enter or Ctrl+I", "Alt+Enter または Ctrl+I", "Alt+Enter 또는 Ctrl+I")) to tr("属性", "Properties", "プロパティ", "속성"),
            tr("菜单键 或 Shift+F10", "Menu key or Shift+F10", "メニューキー または Shift+F10", "메뉴 키 또는 Shift+F10") to tr("操作菜单", "Actions menu", "操作メニュー", "작업 메뉴"),
        ),
        tr("添加链接", "Add link", "リンクを追加", "링크 추가") to listOf(
            "${primary}Enter" to tr("保存", "Save", "保存", "저장"),
            "Esc" to tr("关闭面板，或从某条链接返回列表", "Close panel or return from a link to the list", "パネルを閉じる・リンクから一覧に戻る", "패널 닫기 또는 링크에서 목록으로 돌아가기"),
        ),
        tr("任务", "Tasks", "タスク", "작업") to listOf(
            tr("单击 / 双击", "Click / double-click", "クリック / ダブルクリック", "클릭 / 두 번 클릭") to tr("选中、执行这一项的主操作", "Select or run the item’s primary action", "選択・項目の主な操作を実行", "선택 또는 항목의 기본 작업 실행"),
            tr("${primary.removeSuffix("+")} 点选 / Shift 点选", "${primary.removeSuffix("+")}-click / Shift-click", "${primary.removeSuffix("+")} クリック / Shift クリック", "${primary.removeSuffix("+")} 클릭 / Shift 클릭") to tr("加选、连选", "Add or range-select", "追加選択・範囲選択", "추가 및 범위 선택"),
            "${primary}A" to tr("全选眼前列出的任务", "Select all visible tasks", "表示中のタスクをすべて選択", "현재 표시된 작업 모두 선택"),
            (if (mac) tr("⌘⌫ 或 Delete", "⌘⌫ or Delete", "⌘⌫ または Delete", "⌘⌫ 또는 Delete") else "Delete") to tr("删除所选", "Delete selection", "選択した項目を削除", "선택 항목 삭제"),
            "Esc" to tr("取消选择", "Deselect", "選択を解除", "선택 해제"),
        ),
        tr("标签页", "Tabs", "タブ", "탭") to listOf(
            "${primary}T" to tr("新建标签页", "New tab", "新しいタブ", "새 탭"),
            "${primary}W" to tr("关闭标签页", "Close tab", "タブを閉じる", "탭 닫기"),
            "Ctrl+Tab / Ctrl+Shift+Tab" to tr("下一个、上一个标签页", "Next or previous tab", "次・前のタブ", "다음 또는 이전 탭"),
            "${primary}Shift+PageUp / PageDown" to tr("向前、向后移动标签页", "Move tab forward or backward", "タブを前後に移動", "탭을 앞 또는 뒤로 이동"),
            tr("中键点文件夹", "Middle-click a folder", "フォルダーを中クリック", "폴더를 가운데 버튼으로 클릭") to tr("在后台的新标签页打开", "Open in background tab", "バックグラウンドの新しいタブで開く", "백그라운드 새 탭에서 열기"),
        ),
        tr("鼠标", "Mouse", "マウス", "마우스") to listOf(
            tr("单击 / 双击", "Click / double-click", "クリック / ダブルクリック", "클릭 / 두 번 클릭") to tr("选中、打开", "Select or open", "選択・開く", "선택 또는 열기"),
            tr("右键", "Right-click", "右クリック", "오른쪽 클릭") to tr("操作菜单", "Actions menu", "操作メニュー", "작업 메뉴"),
            tr("${primary.removeSuffix("+")} 点选 / Shift 点选", "${primary.removeSuffix("+")}-click / Shift-click", "${primary.removeSuffix("+")} クリック / Shift クリック", "${primary.removeSuffix("+")} 클릭 / Shift 클릭") to tr("加选、连选", "Add or range-select", "追加選択・範囲選択", "추가 및 범위 선택"),
            tr("在空白处拖动", "Drag on empty space", "空白部分をドラッグ", "빈 공간에서 드래그") to tr("框选", "Box select", "範囲選択", "영역 선택"),
            tr("把条目拖到文件夹上", "Drag an item onto a folder", "項目をフォルダーにドラッグ", "항목을 폴더로 드래그") to tr("移动（按着 ${if (mac) "⌥" else "Ctrl"} 是复制）", "Move (hold ${if (mac) "⌥" else "Ctrl"} to copy)", "移動（${if (mac) "⌥" else "Ctrl"} を押しながらでコピー）", "이동 (${if (mac) "⌥" else "Ctrl"} 키를 누른 채로 드래그하면 복사)"),
        ),
        tr("播放器", "Player", "プレーヤー", "플레이어") to listOf(
            tr("空格 / K", "Space / K", "スペース / K", "스페이스 / K") to tr("播放、暂停", "Play or pause", "再生・一時停止", "재생 또는 일시 중지"),
            tr("← / → 或 J / L", "← / → or J / L", "← / → または J / L", "← / → 또는 J / L") to tr("后退、快进一步", "Seek backward or forward", "設定した間隔で戻る・進む", "설정한 간격만큼 뒤로 또는 앞으로"),
            tr("按住 ← / →", "Hold ← / →", "← / → を長押し", "← / → 길게 누르기") to tr("快退、快进", "Rewind or fast forward", "巻き戻し・早送り", "되감기 또는 빨리 감기"),
            "Shift+← / →" to tr("后退、快进 1 分钟", "Seek back or forward one minute", "1分戻る・進む", "1분 뒤로 또는 앞으로"),
            tr("0 至 9", "0 to 9", "0 ～ 9", "0~9") to tr("跳到全片的对应成数处", "Seek to the corresponding tenth of the video", "動画の対応する10%刻みの位置に移動", "동영상의 해당 10% 구간으로 이동"),
            "Home" to tr("回到开头", "Go to start", "先頭に戻る", "처음으로 이동"),
            tr("↑ / ↓ 或滚轮", "↑ / ↓ or mouse wheel", "↑ / ↓ またはホイール", "↑ / ↓ 또는 마우스 휠") to tr("音量", "Volume", "音量", "볼륨"),
            "M" to tr("静音", "Mute", "ミュート", "음소거"),
            "[ / ]" to tr("减速、加速", "Decrease or increase speed", "減速・加速", "감속 또는 가속"),
            "Backspace" to tr("恢复原速", "Reset playback speed", "通常速度に戻す", "기본 속도로 복원"),
            "C" to tr("开关字幕", "Toggle subtitles", "字幕を切り替え", "자막 켜기 또는 끄기"),
            "PageUp / PageDown" to tr("上一集、下一集", "Previous or next episode", "前・次のエピソード", "이전 또는 다음 에피소드"),
            "F" to tr("全屏", "Fullscreen", "全画面", "전체 화면"),
            "R" to tr("画面顺时针旋转 90 度", "Rotate video 90° clockwise", "映像を右に90度回転", "화면을 시계 방향으로 90도 회전"),
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
