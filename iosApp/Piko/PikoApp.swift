import SwiftUI
import UIKit
import PikoKit

/// 供 SwiftUI 根窗口统一裁决屏幕方向，避免子 VC 的 `supportedInterfaceOrientations` 被系统忽略。
final class PikoAppDelegate: NSObject, UIApplicationDelegate {
    static var orientationMask: UIInterfaceOrientationMask =
        UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown

    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        PikoAppDelegate.orientationMask
    }
}

/// SwiftUI 只当壳：界面全在 Kotlin 的 Compose 里，这里把 `MainViewController()` 包进
/// `PikoContainerViewController` 以便精确控制状态栏显隐、浅色状态栏与横竖屏锁定；
/// 服务、日志、Keychain 会话都在 Kotlin 侧建好并保持进程级，视图重建也不会重连一遍。
@main
struct PikoApp: App {
    @UIApplicationDelegateAdaptor(PikoAppDelegate.self) private var appDelegate
    @StateObject private var hostState = PikoHostUiState()

    init() {
        IosAiSubtitleBridge.shared.registerDecoder(decoder: IosNativeAiSubtitleDecoder())
    }

    var body: some Scene {
        WindowGroup {
            PikoRootView(hostState: hostState)
                // Compose 自己处理安全区与键盘避让，SwiftUI 再加一层会重复留白
                .ignoresSafeArea()
                .statusBar(hidden: hostState.statusBarHidden)
        }
    }
}

private final class PikoHostUiState: ObservableObject {
    @Published var statusBarHidden: Bool = false
}

private struct PikoRootView: UIViewControllerRepresentable {
    let hostState: PikoHostUiState

    func makeUIViewController(context: Context) -> PikoContainerViewController {
        PikoContainerViewController(hostState: hostState)
    }

    func updateUIViewController(_ controller: PikoContainerViewController, context: Context) {
        // 界面状态在 Compose 内，系统栏与方向经 IosHostBridge 实时同步
    }
}

private final class WeakSystemUiDelegate: NSObject, IosSystemUiDelegate {
    weak var owner: PikoContainerViewController?

    init(owner: PikoContainerViewController) {
        self.owner = owner
    }

    func updateSystemUi(statusBarHidden: Bool, lightStatusBarContent: Bool) {
        owner?.updateSystemUi(statusBarHidden: statusBarHidden, lightStatusBarContent: lightStatusBarContent)
    }

    func setLandscapeLocked(locked: Bool) {
        owner?.setOrientationMode(mode: locked ? 1 : 0)
    }

    func setOrientationMode(mode: Int32) {
        owner?.setOrientationMode(mode: mode)
    }

    func updateUserInterfaceStyle(style: Int32) {
        owner?.updateUserInterfaceStyle(style: style)
    }
}

private final class PikoContainerViewController: UIViewController {
    private let hostState: PikoHostUiState
    private let composeController: UIViewController = MainViewControllerKt.MainViewController()
    private var bridgeDelegate: WeakSystemUiDelegate?
    private var statusBarHiddenState: Bool = false
    private var lightStatusBarState: Bool = false
    private var orientationModeState: Int32 = 0

    init(hostState: PikoHostUiState) {
        self.hostState = hostState
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        addChild(composeController)
        composeController.view.frame = view.bounds
        composeController.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(composeController.view)
        composeController.didMove(toParent: self)
        let delegate = WeakSystemUiDelegate(owner: self)
        bridgeDelegate = delegate
        IosHostBridge.shared.attachDelegate(delegate: delegate)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        view.window?.overrideUserInterfaceStyle = overrideUserInterfaceStyle
    }

    deinit {
        if let delegate = bridgeDelegate {
            IosHostBridge.shared.detachDelegate(delegate: delegate)
        }
    }

    override var childForStatusBarHidden: UIViewController? { nil }
    override var childForStatusBarStyle: UIViewController? { nil }
    override var childForHomeIndicatorAutoHidden: UIViewController? { nil }

    override var prefersStatusBarHidden: Bool { statusBarHiddenState }

    override var preferredStatusBarStyle: UIStatusBarStyle {
        if lightStatusBarState {
            return .lightContent
        }
        switch overrideUserInterfaceStyle {
        case .dark:
            return .lightContent
        case .light:
            return .darkContent
        default:
            return .default
        }
    }

    override var prefersHomeIndicatorAutoHidden: Bool { statusBarHiddenState }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        resolvedOrientationMask(for: orientationModeState)
    }

    private func resolvedOrientationMask(for mode: Int32) -> UIInterfaceOrientationMask {
        switch mode {
        case 1:
            return .landscape
        case 2:
            return .portrait
        default:
            return UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown
        }
    }

    func updateUserInterfaceStyle(style: Int32) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let resolved: UIUserInterfaceStyle = switch style {
            case 1: .light
            case 2: .dark
            default: .unspecified
            }
            self.overrideUserInterfaceStyle = resolved
            self.view.window?.overrideUserInterfaceStyle = resolved
            self.setNeedsStatusBarAppearanceUpdate()
        }
    }

    func updateSystemUi(statusBarHidden: Bool, lightStatusBarContent: Bool) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.statusBarHiddenState = statusBarHidden
            self.lightStatusBarState = lightStatusBarContent
            if self.hostState.statusBarHidden != statusBarHidden {
                self.hostState.statusBarHidden = statusBarHidden
            }
            self.setNeedsStatusBarAppearanceUpdate()
            self.setNeedsUpdateOfHomeIndicatorAutoHidden()
        }
    }

    func setOrientationMode(mode: Int32) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.orientationModeState = mode
            let mask = self.resolvedOrientationMask(for: mode)
            PikoAppDelegate.orientationMask = mask
            if #available(iOS 16.0, *) {
                self.setNeedsUpdateOfSupportedInterfaceOrientations()
                self.view.window?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
                if let scene = self.view.window?.windowScene {
                    let preferences = UIWindowScene.GeometryPreferences.iOS(interfaceOrientations: mask)
                    scene.requestGeometryUpdate(preferences) { _ in }
                }
            } else {
                UIViewController.attemptRotationToDeviceOrientation()
            }
        }
    }
}
