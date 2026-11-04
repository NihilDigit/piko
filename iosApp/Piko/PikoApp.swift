import SwiftUI
import UIKit
import PikoKit

/// SwiftUI 只当壳：界面全在 Kotlin 的 Compose 里，这里把 `MainViewController()` 包成
/// `UIViewControllerRepresentable` 交给窗口。服务、日志、Keychain 会话都在 Kotlin 侧建好并保持进程级，
/// 所以 Swift 这层不持有任何应用状态，视图重建也不会重连一遍。
@main
struct PikoApp: App {
    var body: some Scene {
        WindowGroup {
            PikoRootView()
                // Compose 自己处理安全区与键盘避让，SwiftUI 再加一层会重复留白
                .ignoresSafeArea()
                .statusBarHidden(false)
        }
    }
}

private struct PikoRootView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {
        // 界面状态在 Compose 内，这里没有要同步进去的东西
    }
}
