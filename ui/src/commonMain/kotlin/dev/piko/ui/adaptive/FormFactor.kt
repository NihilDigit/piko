package dev.piko.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import dev.piko.ui.platform.LocalPikoPlatform

/**
 * 交互模型，按平台定，不随窗口宽度变。
 *
 * 桌面是标签、浮动卡片、属性卡片、命令栏与右键菜单，几件事可以同时挂着；移动（手机与平板）是栈导航、
 * 条目上的更多按钮与独占的 sheet，一次一件事。宽度只决定同一模型里的密度：导航是底栏还是 Rail、网格几栏、
 * 面板从侧边还是底部出来。
 *
 * 曾经只按宽度分：平板横握拿到桌面那套（标签、右键菜单），却没有鼠标，条目操作整个不可达；横握的手机宽度够、
 * 高度不够，两套条件交错出一批两边都不管的状态。移动端接鼠标、桌面接触屏，是在各自的模型里补输入能力，不换模型。
 */
enum class FormFactor { Desktop, Mobile }

/** 当前是不是桌面的交互模型，见 [FormFactor]。 */
@Composable
@ReadOnlyComposable
fun isDesktopLayout(): Boolean = LocalPikoPlatform.current.formFactor == FormFactor.Desktop
