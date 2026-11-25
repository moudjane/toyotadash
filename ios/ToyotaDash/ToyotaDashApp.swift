import SwiftUI

@main
struct ToyotaDashApp: App {
    @StateObject private var model = DashModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
                .onAppear {
                    // `-demo` launch argument: starts the simulator straight away (screenshots, iOS simulator).
                    if ProcessInfo.processInfo.arguments.contains("-demo") { model.startDemo() }
                }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: DashModel

    var body: some View {
        ZStack {
            Flat.bg.ignoresSafeArea()
            if model.values != nil && (model.phase == .connected || model.phase == .demo) {
                DashboardView().transition(.opacity)
            } else {
                HomeView().transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: model.phase)
        .statusBarHidden(model.phase == .connected || model.phase == .demo)
    }
}
