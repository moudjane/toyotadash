import SwiftUI
import Shared

/**
 * CarPlay dashboard: the head-unit cockpit laid out for a landscape car screen, on Liquid Glass
 * cards. A soft glow behind the glass takes the colour of what the hybrid system is doing
 * (green = recharging, amber = petrol engine, blue = electric).
 */
struct CarDashView: View {
    @EnvironmentObject private var model: DashModel
    /** Close affordance when shown inside the app; nil in CarPlay (the system top bar has the button). */
    var onClose: (() -> Void)? = nil

    var body: some View {
        ZStack {
            Flat.bg.ignoresSafeArea()
            Glow(tint: tint).ignoresSafeArea()
            if let v = model.values {
                VStack(spacing: 10) {
                    TopLine(name: model.deviceName, ambient: v.ambient.f, volts: v.volts12.f, onClose: onClose)
                    HStack(spacing: 10) {
                        DriveCard(v: v).glassCard()
                        BatteryCard(v: v).glassCard()
                    }
                    BottomCard(v: v, history: model.kwHistory).glassCard()
                }
                .padding(12)
            } else {
                Idle(phase: model.phase, step: model.step, error: model.error)
            }
        }
        .environment(\.colorScheme, .dark)
    }

    private var tint: Color {
        guard let v = model.values else { return Flat.text3 }
        if (v.kw.f ?? 0) < -0.2 { return Flat.regen }
        if (v.rpm.f ?? 0) > 200 { return Flat.engine }
        if v.state == HybridState.ev { return Flat.ev }
        return Color(hex: 0x3A3A44)
    }
}

// ── Cards ───────────────────────────────────────────────────────────────────

private struct DriveCard: View {
    let v: DashValues
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                Text(v.state.label).font(.barlow(14, .medium)).tracking(3).foregroundStyle(v.state.color)
                DriveSelectPill(select: v.driveSelect)
            }
            Spacer(minLength: 0)
            HStack(alignment: .lastTextBaseline, spacing: 6) {
                Text(fmt(v.speed.f)).font(.barlow(112, .thin)).lineLimit(1).minimumScaleFactor(0.5)
                Text("km/h").font(.barlow(18)).foregroundStyle(Flat.text2)
            }
            Spacer(minLength: 0)
            PowerMeter(kw: v.kw.f)
            Text(engineLine(v.rpm.f)).font(.barlow(14)).foregroundStyle(Flat.text2).padding(.top, 8)
        }
        .padding(18)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}

private struct BatteryCard: View {
    let v: DashValues
    var body: some View {
        let amps = v.hvAmp.f
        let charging = (amps ?? 0) < -1
        VStack(alignment: .leading, spacing: 8) {
            Caps("BATTERIE HYBRIDE")
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(fmt(v.soc.f)).font(.barlow(46, .light))
                Text("%").font(.barlow(16)).foregroundStyle(Flat.text2)
            }
            SocBar(soc: v.soc.f)
            BlockStrip(volts: v.blockVoltsOrNaN().map { $0.floatValue })
            HStack {
                Metric(label: "TENSION", value: fmt(v.hvVolt.f), unit: "V", size: 20)
                Metric(label: charging ? "RECHARGE" : "COURANT", value: fmt(amps.map { abs($0) }), unit: "A",
                       color: charging ? Flat.regen : Flat.text, size: 20)
                Metric(label: "TEMP.", value: fmt(v.hvTemp.f), unit: "°C",
                       color: (v.hvTemp.f ?? 0) > 45 ? Flat.engine : Flat.text, size: 20)
            }
            Spacer(minLength: 0)
            Caps("FLUX D'ÉNERGIE")
            Text(v.flows.sentence).font(.barlow(14)).lineLimit(1).minimumScaleFactor(0.7)
            FlowStrip(flows: v.flows).frame(height: 38)
        }
        .padding(18)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}

private struct BottomCard: View {
    let v: DashValues
    let history: [Float]
    var body: some View {
        HStack(spacing: 12) {
            Metric(label: "MOTEUR", value: fmt(v.coolant.f), unit: "°C", size: 20)
            Metric(label: "PÉDALE", value: fmt(v.pedal.f), unit: "%", size: 20)
            Metric(label: "ESSENCE", value: fmt(v.fuel.f), unit: "%", size: 20)
            VStack(alignment: .leading, spacing: 6) {
                Caps("ÉNERGIE · DERNIÈRE MINUTE")
                EnergyHistory(samples: history).frame(height: 26)
            }
            .frame(minWidth: 220)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
    }
}

/** Shown before the demo or a dongle is started (the buttons are in CarPlay's top bar). */
private struct Idle: View {
    let phase: DashModel.Phase
    let step: String?
    let error: String?
    var body: some View {
        VStack(spacing: 12) {
            Text("ToyotaDash").font(.barlow(34, .light)).foregroundStyle(Flat.text)
            if phase == .connecting {
                ProgressView().tint(Flat.text2)
                Text(step ?? "Connexion…").font(.barlow(16)).foregroundStyle(Flat.text2)
            } else {
                Text(error ?? "Non connecté").font(.barlow(16)).foregroundStyle(error == nil ? Flat.text2 : Flat.alert)
                Text("« Démo » ou « Reconnecter » en haut de l'écran").font(.barlow(13)).foregroundStyle(Flat.text3)
            }
        }
        .padding(.horizontal, 36)
        .padding(.vertical, 28)
        .glassCard(radius: 28)
    }
}

// ── Pieces ──────────────────────────────────────────────────────────────────

private struct TopLine: View {
    let name: String
    let ambient: Float?
    let volts: Float?
    var onClose: (() -> Void)? = nil
    @State private var pulse = false

    var body: some View {
        ZStack {
            HStack(spacing: 8) {
                Circle().fill(Flat.regen).frame(width: 6, height: 6).opacity(pulse ? 0.3 : 1)
                    .onAppear { withAnimation(.easeInOut(duration: 1).repeatForever()) { pulse = true } }
                Caps(name, color: Flat.text2)
                Spacer()
                Text("\(fmt(ambient))°   \(fmt(volts, 1)) V").font(.barlow(14)).foregroundStyle(Flat.text2)
                if let onClose {
                    Button(action: onClose) {
                        Image(systemName: "xmark").font(.system(size: 12, weight: .medium)).foregroundStyle(Flat.text2)
                            .frame(width: 32, height: 32)
                            .glassEffect(.regular.interactive(), in: Circle())
                    }
                    .buttonStyle(.plain)
                }
            }
            TimelineView(.everyMinute) { context in
                Text(context.date, format: .dateTime.hour().minute()).font(.barlow(15, .medium)).foregroundStyle(Flat.text)
            }
        }
        .padding(.horizontal, 10)
    }
}

/** Power over the last minute: drawn above the line (grey), recovered below (green). */
private struct EnergyHistory: View {
    let samples: [Float]
    var body: some View {
        Canvas { ctx, size in
            let slots = DashModel.historySize
            let w = size.width / CGFloat(slots)
            let mid = size.height / 2
            let scale = CGFloat(max(samples.map { abs($0) }.max() ?? 0, 10))
            ctx.fill(Path(CGRect(x: 0, y: mid - 0.5, width: size.width, height: 1)), with: .color(Flat.line))
            let start = slots - samples.count
            for (i, kw) in samples.enumerated() {
                let h = max(CGFloat(abs(kw)) / scale * mid, 1)
                let x = CGFloat(start + i) * w
                let rect = kw >= 0
                    ? CGRect(x: x, y: mid - h, width: max(w - 1, 1), height: h)
                    : CGRect(x: x, y: mid, width: max(w - 1, 1), height: h)
                ctx.fill(Path(rect), with: .color(kw >= 0 ? Flat.text2 : Flat.regen))
            }
        }
    }
}

/** Blurred colour blobs behind the glass, so it has something to refract. */
private struct Glow: View {
    let tint: Color
    var body: some View {
        GeometryReader { g in
            ZStack {
                Circle().fill(tint.opacity(0.45))
                    .frame(width: g.size.width * 0.55)
                    .blur(radius: 90)
                    .offset(x: -g.size.width * 0.22, y: g.size.height * 0.2)
                Circle().fill(tint.opacity(0.25))
                    .frame(width: g.size.width * 0.45)
                    .blur(radius: 100)
                    .offset(x: g.size.width * 0.28, y: -g.size.height * 0.25)
            }
            .frame(width: g.size.width, height: g.size.height)
        }
        .animation(.easeInOut(duration: 1.2), value: tint)
    }
}

private extension View {
    /** Liquid Glass card. */
    func glassCard(radius: CGFloat = 22) -> some View {
        glassEffect(.regular, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}
