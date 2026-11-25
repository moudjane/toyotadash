import CarPlay
import Combine
import SwiftUI
import Shared

/**
 * CarPlay screen. Only navigation apps may draw their own screen in CarPlay (the base view
 * under CPMapTemplate, normally a map), so the app declares the navigation category and draws
 * the dashboard there instead of a map. Apple would not approve that for the App Store; this
 * is a personal build, used in the simulator.
 *
 * Same DashModel as the iPhone screen: start the demo on either side, both follow.
 */
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {

    private let model = DashModel.shared
    private var mapTemplate: CPMapTemplate?
    private var subscriptions = Set<AnyCancellable>()

    func templateApplicationScene(
        _ scene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController,
        to window: CPWindow
    ) {
        let host = UIHostingController(rootView: CarDashView().environmentObject(model))
        host.view.backgroundColor = .black
        window.rootViewController = host

        let map = CPMapTemplate()
        mapTemplate = map
        interfaceController.setRootTemplate(map, animated: false, completion: nil)

        model.$phase
            .receive(on: RunLoop.main)
            .sink { [weak self] phase in self?.updateButtons(phase) }
            .store(in: &subscriptions)
    }

    func templateApplicationScene(
        _ scene: CPTemplateApplicationScene,
        didDisconnect interfaceController: CPInterfaceController,
        from window: CPWindow
    ) {
        subscriptions.removeAll()
        mapTemplate = nil
        window.rootViewController = nil
    }

    /** Buttons in CarPlay's top bar (drawn by the system, in Liquid Glass). */
    private func updateButtons(_ phase: DashModel.Phase) {
        guard let map = mapTemplate else { return }
        switch phase {
        case .connected, .demo:
            map.trailingNavigationBarButtons = [button("Arrêter") { $0.disconnect() }]
            map.automaticallyHidesNavigationBar = true // full screen while driving, tap to show
        case .connecting:
            map.trailingNavigationBarButtons = [button("Annuler") { $0.disconnect() }]
            map.automaticallyHidesNavigationBar = false
        case .home:
            var buttons = [button("Démo") { $0.startDemo() }]
            if let last = model.lastDongle {
                buttons.insert(button("Reconnecter") { $0.connect(id: last.id, name: last.name) }, at: 0)
            }
            map.trailingNavigationBarButtons = buttons
            map.automaticallyHidesNavigationBar = false
        }
    }

    private func button(_ title: String, _ action: @escaping (DashModel) -> Void) -> CPBarButton {
        CPBarButton(title: title) { [weak self] _ in
            if let model = self?.model { action(model) }
        }
    }
}
