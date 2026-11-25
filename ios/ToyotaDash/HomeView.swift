import SwiftUI
import Shared

/** Connect screen: state in big words, one-tap reconnect, demo mode, nearby dongles. */
struct HomeView: View {
    @EnvironmentObject private var model: DashModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Caps("OBD2 · TOYOTA DASH")
                Spacer()
                Clock().font(.barlow(15)).foregroundStyle(Flat.text2)
            }

            Spacer().frame(height: 48)

            Text(title).font(.barlow(42, .light)).foregroundStyle(Flat.text)
            Text(subtitle)
                .font(.barlow(16)).foregroundStyle(model.error != nil ? Flat.alert : Flat.text2)
                .padding(.top, 6)
                .fixedSize(horizontal: false, vertical: true)

            if model.phase == .connecting {
                ProgressView().tint(Flat.text).padding(.top, 20)
            }

            VStack(spacing: 12) {
                if let last = model.lastDongle, model.phase != .connecting {
                    PillButton(title: "Reconnecter \(last.name)", filled: true) {
                        model.connect(id: last.id, name: last.name)
                    }
                }
                PillButton(title: "Mode démo", filled: model.lastDongle == nil) { model.startDemo() }
            }
            .padding(.top, 32)

            HStack {
                Caps("DONGLES À PROXIMITÉ")
                Spacer()
                Button(model.isScanning ? "Recherche…" : "Rechercher") { model.scan() }
                    .font(.barlow(14, .medium))
                    .foregroundStyle(model.isScanning ? Flat.text3 : Flat.text)
                    .disabled(model.isScanning)
            }
            .padding(.top, 44)
            .padding(.bottom, 8)

            ScrollView {
                VStack(spacing: 0) {
                    if model.dongles.isEmpty {
                        Text(model.isScanning ? "Recherche des dongles Bluetooth…" : "Aucun dongle trouvé pour l'instant.")
                            .font(.barlow(15)).foregroundStyle(Flat.text3)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 16)
                    }
                    ForEach(model.dongles, id: \.id) { dongle in
                        DongleRow(dongle: dongle, isLast: dongle.id == model.lastDongle?.id) {
                            model.connect(id: dongle.id, name: dongle.name)
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 12)
    }

    private var title: String {
        if model.error != nil { return "Connexion impossible" }
        if model.phase == .connecting { return "Connexion…" }
        return "Prêt à partir"
    }

    private var subtitle: String {
        if let error = model.error { return error }
        if model.phase == .connecting { return model.step ?? "" }
        if model.lastDongle != nil { return "Voiture en READY, puis reconnecte le dongle." }
        return "Branche le dongle OBD2, puis recherche-le ci-dessous."
    }
}

private struct DongleRow: View {
    let dongle: BleDongle
    let isLast: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 8) {
                        Text(dongle.name).font(.barlow(18, .medium)).foregroundStyle(Flat.text)
                        if isLast { Tag("DERNIER") } else if dongle.looksLikeObd { Tag("OBD") }
                    }
                    Text("Bluetooth LE").font(.barlow(13)).foregroundStyle(Flat.text3)
                }
                Spacer()
                SignalBars(rssi: Int(dongle.rssi))
            }
            .padding(.vertical, 14)
            .overlay(alignment: .bottom) { Rectangle().fill(Flat.line).frame(height: 1) }
        }
        .buttonStyle(.plain)
    }
}

private struct Tag: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.barlow(10, .semibold)).tracking(1.5)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(Flat.text3, lineWidth: 1))
            .foregroundStyle(Flat.text2)
    }
}

private struct SignalBars: View {
    let rssi: Int
    var body: some View {
        let level = rssi > -65 ? 3 : rssi > -80 ? 2 : 1
        HStack(alignment: .bottom, spacing: 3) {
            ForEach(1...3, id: \.self) { i in
                RoundedRectangle(cornerRadius: 1)
                    .fill(i <= level ? Flat.text : Flat.track)
                    .frame(width: 4, height: CGFloat(5 + i * 4))
            }
        }
    }
}

struct PillButton: View {
    let title: String
    let filled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.barlow(17, .medium))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 15)
                .foregroundStyle(filled ? Color.black : Flat.text)
                .glassEffect(filled ? .regular.tint(.white).interactive()
                                    : .regular.interactive(),
                             in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

struct Clock: View {
    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            Text(context.date, format: .dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
        }
    }
}
