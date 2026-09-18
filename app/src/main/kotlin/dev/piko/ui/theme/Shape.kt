package dev.piko.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Piko 形状系统，基于 Material 3 Expressive 10 档规范。
 * 由信息密度驱动组件形状的选择。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val PikoShapes = Shapes(
    // 标签、角标、徽章
    extraSmall = RoundedCornerShape(4.dp),
    // 列表里的缩略图、多选勾选框、小按钮
    small = RoundedCornerShape(8.dp),
    // 列表条目、卡片、模态弹窗的小内容块
    medium = RoundedCornerShape(12.dp),
    // 浮动操作栏、对话框、底部 Sheet
    large = RoundedCornerShape(16.dp),
    // 输入框、搜索条 (20dp 增强打字与点击意图)
    largeIncreased = RoundedCornerShape(20.dp),
    // 大容器、抽屉栏卡片
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    // 播放器大容器、沉浸式全屏圆角卡片
    extraExtraLarge = RoundedCornerShape(48.dp),
)
