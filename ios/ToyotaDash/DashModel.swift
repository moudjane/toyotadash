import Foundation
import Shared

/**
 * App state. All the OBD2 work (Bluetooth, ELM327, PIDs, reading loop) happens in the
 * Kotlin shared module through DashController; this class only keeps what the screens show.
 * Kotlin callbacks arrive on the main thread.
 */
final class DashModel: ObservableObject {

    /** One state for the iPhone screen and the CarPlay screen. */
    static let shared = DashModel()

    enum Phase { case home, connecting, connected, demo }

    @Published private(set) var phase: Phase = .home
    @Published private(set) var step: String?
    @Published private(set) var values: DashValues? { didSet { record(values) } }
    /** Power (kW) sampled every 0.5 s over the last minute, for the CarPlay history graph. */
    @Published private(set) var kwHistory: [Float] = []
    static let historySize = 120
    private var lastSample = Date.distantPast
    @Published private(set) var dongles: [BleDongle] = []
    @Published private(set) var isScanning = false
    @Published private(set) var error: String?
    @Published private(set) var deviceName = ""

    private let controller = DashController()
    private let defaults = UserDefaults.standard

    /** Last dongle that connected successfully, for the one-tap reconnect. */
    var lastDongle: (id: String, name: String)? {
        guard let id = defaults.string(forKey: "lastDongleId") else { return nil }
        return (id, defaults.string(forKey: "lastDongleName") ?? "Dongle")
    }

    func scan() {
        guard !isScanning else { return }
        isScanning = true
        error = nil
        controller.scan(
            onResult: { [weak self] found in
                self?.dongles = found
                self?.isScanning = false
            },
            onError: { [weak self] message in
                self?.error = message
                self?.isScanning = false
            }
        )
    }

    func connect(id: String, name: String) {
        phase = .connecting
        error = nil
        deviceName = name
        step = "Connexion à \(name)…"
        controller.connect(
            dongleId: id,
            onStep: { [weak self] in self?.step = $0 },
            onConnected: { [weak self] in
                self?.phase = .connected
                self?.defaults.set(id, forKey: "lastDongleId")
                self?.defaults.set(name, forKey: "lastDongleName")
            },
            onValues: { [weak self] in self?.values = $0 },
            onError: { [weak self] message in
                self?.phase = .home
                self?.values = nil
                self?.step = nil
                self?.error = message
            }
        )
    }

    func startDemo() {
        phase = .demo
        error = nil
        deviceName = "DÉMO"
        controller.startDemo { [weak self] in self?.values = $0 }
    }

    func disconnect() {
        controller.stop()
        phase = .home
        values = nil
        step = nil
    }

    private func record(_ v: DashValues?) {
        guard let v else { kwHistory = []; return }
        let now = Date()
        guard now.timeIntervalSince(lastSample) >= 0.5 else { return }
        lastSample = now
        kwHistory.append(v.kw.f ?? 0)
        if kwHistory.count > Self.historySize { kwHistory.removeFirst(kwHistory.count - Self.historySize) }
    }
}
