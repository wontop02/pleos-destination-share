import SwiftUI
import UIKit

@MainActor final class RoadAppDelegate: NSObject, UIApplicationDelegate {
    let store = RoomStore()
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool { true }
}
@MainActor @main struct TogetherRoadApp: App {
    @UIApplicationDelegateAdaptor(RoadAppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var phase
    var body: some Scene { WindowGroup { ContentView(store: delegate.store).onChange(of: phase) { delegate.store.sceneActive($0 == .active) }.onAppear { delegate.store.sceneActive(phase == .active) } } }
}
