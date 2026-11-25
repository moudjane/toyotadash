import SwiftUI
import Shared

/**
 * iPhone dashboard, portrait. Same reading as the head-unit cockpit:
 *   mode + speed + power meter, then the hybrid battery, the energy flow and the engine.
 */
struct DashboardView: View {
    @EnvironmentObject private var model: DashModel

    var body: some View {
        if let v = model.values {
            VStack(alignment: .leading, spacing: 0) {
                StatusLine(name: model.deviceName, volts: v.volts12.f, ambient: v.ambient.f) { model.disconnect() }

                HStack(spacing: 12) {
                    Text(v.state.label).font(.barlow(15, .medium)).tracking(3).foregroundStyle(v.state.color)
                    DriveSelectPill(select: v.driveSelect)
                }
                .padding(.top, 20)

                HStack(alignment: .lastTextBaseline, spacing: 8) {
                    Text(fmt(v.speed.f)).font(.barlow(132, .thin)).foregroundStyle(Flat.text)
                    Text("km/h").font(.barlow(20)).foregroundStyle(Flat.text2)
                }
                .padding(.top, -8)

                PowerMeter(kw: v.kw.f)

                Text(engineLine(v.rpm.f)).font(.barlow(15)).foregroundStyle(Flat.text2).padding(.top, 10)

                Rectangle().fill(Flat.line).frame(height: 1).padding(.vertical, 22)

                Battery(v: v)

                Rectangle().fill(Flat.line).frame(height: 1).padding(.vertical, 22)

                FlowSection(flows: v.flows)

                Spacer(minLength: 16)

                HStack {
                    Metric(label: "MOTEUR", value: fmt(v.coolant.f), unit: "°C")
                    Metric(label: "PÉDALE", value: fmt(v.pedal.f), unit: "%")
                    Metric(label: "ESSENCE", value: fmt(v.fuel.f), unit: "%")
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 8)
            .padding(.bottom, 4)
        }
    }

    private func engineLine(_ rpm: Float?) -> String {
        guard let rpm, rpm > 200 else { return "Moteur thermique arrêté" }
        return "Moteur thermique  \(Int(rpm).formatted()) tr/min"
    }
}

// ── Pieces ──────────────────────────────────────────────────────────────────

private struct StatusLine: View {
    let name: String
    let volts: Float?
    let ambient: Float?
    let onClose: () -> Void
    @State private var pulse = false

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(Flat.regen).frame(width: 6, height: 6).opacity(pulse ? 0.3 : 1)
                .onAppear { withAnimation(.easeInOut(duration: 1).repeatForever()) { pulse = true } }
            Caps(name, color: Flat.text2)
            Spacer()
            Text("\(fmt(ambient))°   \(fmt(volts, 1)) V").font(.barlow(14)).foregroundStyle(Flat.text2)
            Button(action: onClose) {
                Image(systemName: "xmark").font(.system(size: 13, weight: .medium)).foregroundStyle(Flat.text2)
                    .frame(width: 32, height: 32)
            }
        }
    }
}

private struct DriveSelectPill: View {
    let select: DriveSelect
    var body: some View {
        let pill: (String, Color)? =
            select == DriveSelect.eco ? ("ECO", Flat.regen) :
            select == DriveSelect.ev ? ("EV MODE", Flat.ev) :
            select == DriveSelect.power ? ("POWER", Flat.engine) : nil
        if let (text, color) = pill {
            Text(text).font(.barlow(12, .semibold)).tracking(1.5).foregroundStyle(.black)
                .padding(.horizontal, 10).padding(.vertical, 4)
                .background(Capsule().fill(color))
        }
    }
}

/** Zero at 30 %: power drawn grows to the right (white), regen to the left (green). */
private struct PowerMeter: View {
    let kw: Float?

    var body: some View {
        let k = kw ?? 0
        let regen = k < -0.2
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Caps(regen ? "RECHARGE" : "PUISSANCE", color: regen ? Flat.regen : Flat.text3)
                Spacer()
                HStack(alignment: .firstTextBaseline, spacing: 3) {
                    Text(fmt(kw.map { abs($0) }, 1)).font(.barlow(22, .light))
                    Text("kW").font(.barlow(12)).foregroundStyle(Flat.text2)
                }
                .foregroundStyle(regen ? Flat.regen : Flat.text)
            }
            GeometryReader { geo in
                let w = geo.size.width
                let zero = w * 0.3
                let (from, to): (CGFloat, CGFloat) = k >= 0
                    ? (zero, zero + (w - zero) * CGFloat(min(k / 30, 1)))
                    : (zero - zero * CGFloat(min(-k / 20, 1)), zero)
                ZStack(alignment: .leading) {
                    Capsule().fill(Flat.track).frame(height: 6)
                    Capsule().fill(regen ? Flat.regen : Flat.text)
                        .frame(width: max(to - from, 2), height: 6).offset(x: from)
                    Rectangle().fill(Flat.text2).frame(width: 1.5, height: 14).offset(x: zero)
                }
                .frame(height: 14)
                .animation(.easeOut(duration: 0.3), value: k)
            }
            .frame(height: 14)
        }
        .padding(.top, 8)
    }
}

private struct Battery: View {
    let v: DashValues

    var body: some View {
        let soc = v.soc.f
        let amps = v.hvAmp.f
        let charging = (amps ?? 0) < -1
        VStack(alignment: .leading, spacing: 12) {
            Caps("BATTERIE HYBRIDE")
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(fmt(soc)).font(.barlow(64, .light))
                Text("%").font(.barlow(20)).foregroundStyle(Flat.text2)
            }
            SocBar(soc: soc)
            BlockStrip(volts: v.blockVoltsOrNaN().map { $0.floatValue })
            HStack {
                Metric(label: "TENSION", value: fmt(v.hvVolt.f), unit: "V")
                Metric(label: charging ? "RECHARGE" : "COURANT", value: fmt(amps.map { abs($0) }), unit: "A",
                       color: charging ? Flat.regen : Flat.text)
                Metric(label: "TEMP.", value: fmt(v.hvTemp.f), unit: "°C",
                       color: (v.hvTemp.f ?? 0) > 45 ? Flat.engine : Flat.text)
            }
            .padding(.top, 4)
        }
    }
}

/** Charge bar with the 40 % / 80 % marks: Toyota keeps the battery between them. */
private struct SocBar: View {
    let soc: Float?
    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            ZStack(alignment: .leading) {
                Capsule().fill(Flat.track)
                Capsule().fill(Flat.text).frame(width: w * CGFloat(min(max((soc ?? 0) / 100, 0), 1)))
                ForEach([0.4, 0.8], id: \.self) { mark in
                    Rectangle().fill(Color.black).frame(width: 2).offset(x: w * mark)
                }
            }
        }
        .frame(height: 5)
        .animation(.easeOut(duration: 0.5), value: soc)
    }
}

/** The 10 battery blocks: height = voltage, the weakest one in amber. */
private struct BlockStrip: View {
    let volts: [Float]
    var body: some View {
        let valid = volts.filter { $0.isFinite }
        let lo = valid.min() ?? 0
        let hi = valid.max() ?? 0
        HStack(alignment: .bottom, spacing: 5) {
            ForEach(Array(volts.enumerated()), id: \.offset) { _, v in
                let t = hi - lo > 0.01 && v.isFinite ? CGFloat((v - lo) / (hi - lo)) : 0.5
                RoundedRectangle(cornerRadius: 2)
                    .fill(v.isFinite && v == lo && hi - lo > 0.05 ? Flat.engine : Color(hex: 0x4A4A50))
                    .frame(height: 8 + 14 * t)
                    .frame(maxWidth: .infinity)
            }
        }
        .frame(height: 22, alignment: .bottom)
    }
}

private struct FlowSection: View {
    let flows: EnergyFlows
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Caps("FLUX D'ÉNERGIE")
            Text(flows.sentence).font(.barlow(17)).foregroundStyle(Flat.text)
                .lineLimit(2).fixedSize(horizontal: false, vertical: true)
            FlowStrip(flows: flows).frame(height: 44)
        }
    }
}

/** MOTEUR — ROUES — BATTERIE with chevrons running in the direction of the energy. */
private struct FlowStrip: View {
    let flows: EnergyFlows

    var body: some View {
        TimelineView(.animation) { context in
            let phase = CGFloat(context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1))
            Canvas { ctx, size in
                let y: CGFloat = 12
                let xs: [CGFloat] = [8, size.width / 2, size.width - 8]
                // Left segment: engine → wheels.
                if flows.engineToWheels { chevrons(ctx, from: xs[0], to: xs[1], y: y, phase: phase, color: Flat.engine) }
                // Right segment: battery → wheels (white) or into the battery (green).
                if flows.batteryToWheels {
                    chevrons(ctx, from: xs[2], to: xs[1], y: y, phase: phase, color: Flat.text)
                } else if flows.wheelsToBattery || flows.engineToBattery {
                    chevrons(ctx, from: xs[1], to: xs[2], y: y, phase: phase, color: Flat.regen)
                }
                for x in xs {
                    ctx.fill(Path(ellipseIn: CGRect(x: x - 5, y: y - 5, width: 10, height: 10)), with: .color(Flat.text))
                }
            }
            .overlay(alignment: .bottom) {
                HStack {
                    Caps("MOTEUR", color: Flat.text2); Spacer()
                    Caps("ROUES", color: Flat.text2); Spacer()
                    Caps("BATTERIE", color: Flat.text2)
                }
            }
        }
    }

    private func chevrons(_ ctx: GraphicsContext, from: CGFloat, to: CGFloat, y: CGFloat, phase: CGFloat, color: Color) {
        let dir: CGFloat = to > from ? 1 : -1
        let length = abs(to - from) - 24
        let step: CGFloat = 14
        let count = Int(length / step)
        for i in 0..<count {
            let x = from + dir * (12 + (CGFloat(i) + phase) * step)
            var p = Path()
            p.move(to: CGPoint(x: x - dir * 3, y: y - 4))
            p.addLine(to: CGPoint(x: x + dir * 2, y: y))
            p.addLine(to: CGPoint(x: x - dir * 3, y: y + 4))
            ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: 1.6, lineCap: .round, lineJoin: .round))
        }
    }
}

private struct Metric: View {
    let label: String
    let value: String
    let unit: String
    var color: Color = Flat.text

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Caps(label)
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(value).font(.barlow(26, .light)).foregroundStyle(color)
                Text(unit).font(.barlow(13)).foregroundStyle(Flat.text2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// ── Labels ──────────────────────────────────────────────────────────────────

extension HybridState {
    var label: String {
        if self == HybridState.ev { return "ÉLECTRIQUE" }
        if self == HybridState.hybrid { return "HYBRIDE" }
        if self == HybridState.regen { return "RÉCUPÉRATION" }
        return "READY"
    }
    var color: Color {
        if self == HybridState.ev || self == HybridState.regen { return Flat.regen }
        if self == HybridState.hybrid { return Flat.text }
        return Flat.text2
    }
}

extension EnergyFlows {
    /** What the hybrid system is doing, in plain words. */
    var sentence: String {
        if engineToWheels && engineToBattery { return "Le moteur fait avancer la voiture et recharge la batterie" }
        if engineToWheels && batteryToWheels { return "Moteur et batterie font avancer la voiture ensemble" }
        if engineToWheels { return "Le moteur thermique fait avancer la voiture" }
        if batteryToWheels { return "La batterie fait avancer la voiture" }
        if wheelsToBattery { return "Le freinage recharge la batterie" }
        if engineToBattery { return "Le moteur recharge la batterie à l'arrêt" }
        return "Aucun échange d'énergie"
    }
}
