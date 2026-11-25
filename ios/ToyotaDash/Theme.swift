import SwiftUI
import Shared

/** Same flat palette as the head-unit app: black, white, colour only when it means something. */
enum Flat {
    static let bg     = Color.black
    static let line   = Color(hex: 0x1C1C1F)
    static let track  = Color(hex: 0x1E1E22)
    static let text   = Color.white
    static let text2  = Color(hex: 0x9A9AA0)
    static let text3  = Color(hex: 0x55555B)
    static let regen  = Color(hex: 0x3DDC84) // energy going into the battery
    static let engine = Color(hex: 0xFFB547) // petrol engine, warnings
    static let ev     = Color(hex: 0x4FC3F7)
    static let alert  = Color(hex: 0xFF453A)
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255)
    }
}

/**
 * App backdrop. Still essentially black, but with faint corner glows so the Liquid Glass
 * panels on top have something to refract — otherwise glass over pure black is invisible.
 */
struct DashBackground: View {
    var body: some View {
        ZStack {
            Flat.bg
            LinearGradient(colors: [Color(hex: 0x0D0F12), .black], startPoint: .top, endPoint: .bottom)
            RadialGradient(colors: [Color(hex: 0x142A20).opacity(0.8), .clear],
                           center: .init(x: 0.14, y: 0.08), startRadius: 0, endRadius: 520)
            RadialGradient(colors: [Color(hex: 0x102130).opacity(0.8), .clear],
                           center: .init(x: 0.9, y: 0.9), startRadius: 0, endRadius: 520)
        }
    }
}

extension View {
    /** The Liquid Glass panel used across the phone dashboard. Reacts to the system Liquid Glass setting. */
    func glassPanel(_ radius: CGFloat = 26) -> some View {
        glassEffect(.regular, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

enum Barlow: String {
    case thin = "Barlow-Thin", light = "Barlow-Light", regular = "Barlow-Regular"
    case medium = "Barlow-Medium", semibold = "Barlow-SemiBold"
}

extension Font {
    /** Barlow with tabular figures, so digits never jiggle when values change. */
    static func barlow(_ size: CGFloat, _ weight: Barlow = .regular) -> Font {
        .custom(weight.rawValue, size: size).monospacedDigit()
    }
}

/** Small spaced capitals used for every section title. */
struct Caps: View {
    let text: String
    var color: Color = Flat.text3
    init(_ text: String, color: Color = Flat.text3) { self.text = text; self.color = color }
    var body: some View {
        Text(text).font(.barlow(11, .medium)).tracking(2).foregroundStyle(color)
    }
}

extension Optional where Wrapped == KotlinFloat {
    /** Kotlin `Float?` arrives boxed; this unwraps it. */
    var f: Float? { self?.floatValue }
}

/** "—" when a value is unknown. */
func fmt(_ value: Float?, _ decimals: Int = 0) -> String {
    guard let v = value, v.isFinite else { return "—" }
    return String(format: "%.\(decimals)f", v)
}
