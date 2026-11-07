import Foundation
import CoreGraphics

public enum SensorProfile: Int, Codable {
    case none = 0
    case kodak800v3 = 1
    case leicaX = 2
    case cinestill800T = 3
}

public enum WhiteBalanceMode: String, CaseIterable, Codable {
    case auto = "AUTO"
    case autoAmbience = "AUTO AMBIENCE"
    case autoWhite = "AUTO WHITE"
    case daylight = "DAYLIGHT"
    case shade = "SHADE"
    case kelvin = "KELVIN"
    case incandescent = "TUNGSTEN"
    case fluorescent1 = "FLUORESCENT 1"
    case fluorescent2 = "FLUORESCENT 2"
    case fluorescent3 = "FLUORESCENT 3"

    public var baseKelvin: Float {
        switch self {
        case .auto: return 5200
        case .autoAmbience: return 5450
        case .autoWhite: return 5000
        case .daylight: return 5300
        case .shade: return 6200
        case .kelvin: return 5500
        case .incandescent: return 3100
        case .fluorescent1: return 4200
        case .fluorescent2: return 4600
        case .fluorescent3: return 6300
        }
    }
}

public enum FilmKind: String, Codable {
    case isp
    case recipe
    case fujiSim
    case sonyS709
}

public struct GrainFilm: Identifiable, Equatable {
    public let id: String
    public let code: String
    public let name: String
    public let subtitle: String
    public let kind: FilmKind
    public let simulationKey: String
    public let dynamicRange: Int
    public let highlightTone: Float
    public let shadowTone: Float
    public let color: Float
    public let sharpness: Int
    public let noiseReduction: Int
    public let clarity: Int
    public let grainRoughness: Int // 0 off, 1 weak, 2 strong
    public let grainSize: Int      // 0 small, 1 large
    public let colorChromeEffect: Int // 0 off, 1 weak, 2 strong
    public let colorChromeBlue: Int   // 0 off, 1 weak, 2 strong
    public let wbMode: WhiteBalanceMode
    public let wbKelvin: Int
    public let wbShiftRed: Int
    public let wbShiftBlue: Int
    public let monochromeFilter: String // NONE, Y, R, G
    public let exposureNote: String
    public let sourceURL: String
    public let boxHex: UInt32
    public let sensorProfile: SensorProfile

    public var isRawPipeline: Bool {
        kind != .isp
    }
}

public struct FilmTuning: Codable, Equatable {
    public var highlightTone: Float
    public var shadowTone: Float
    public var color: Float
    public var sharpness: Int
    public var noiseReduction: Int
    public var clarity: Int
    public var grainRoughness: Int
    public var grainSize: Int
    public var colorChromeEffect: Int
    public var colorChromeBlue: Int
    public var halation: Int
    public var bloom: Int
    public var wbMode: WhiteBalanceMode
    public var wbKelvin: Int
    public var wbShiftRed: Int
    public var wbShiftBlue: Int
    public var dynamicRange: Int

    public static func defaults(for film: GrainFilm) -> FilmTuning {
        FilmTuning(
            highlightTone: film.highlightTone,
            shadowTone: film.shadowTone,
            color: film.color,
            sharpness: film.sharpness,
            noiseReduction: film.noiseReduction,
            clarity: film.clarity,
            grainRoughness: film.grainRoughness,
            grainSize: film.grainSize,
            colorChromeEffect: film.colorChromeEffect,
            colorChromeBlue: film.colorChromeBlue,
            halation: film.sensorProfile == .cinestill800T ? 4 : (film.sensorProfile == .kodak800v3 ? 1 : 0),
            bloom: film.sensorProfile == .cinestill800T ? 2 : (film.sensorProfile == .kodak800v3 ? 1 : 0),
            wbMode: film.wbMode,
            wbKelvin: film.wbKelvin,
            wbShiftRed: film.wbShiftRed,
            wbShiftBlue: film.wbShiftBlue,
            dynamicRange: film.dynamicRange
        )
    }
}

public enum FilmCatalog {
    public static let all: [GrainFilm] = [
        // 4 Instant ISP-style looks
        GrainFilm(
            id: "isp-daylight", code: "DAY", name: "Daylight", subtitle: "Clean everyday colour",
            kind: .isp, simulationKey: "PROVIA", dynamicRange: 100,
            highlightTone: 0, shadowTone: 0, color: 0, sharpness: 0, noiseReduction: 0, clarity: 0,
            grainRoughness: 0, grainSize: 0, colorChromeEffect: 0, colorChromeBlue: 0,
            wbMode: .daylight, wbKelvin: 5300, wbShiftRed: 0, wbShiftBlue: 0, monochromeFilter: "NONE",
            exposureNote: "Balanced neutral daylight rendering", sourceURL: "", boxHex: 0xd9612e, sensorProfile: .none
        ),
        GrainFilm(
            id: "isp-coastal", code: "CST", name: "Coastal", subtitle: "Airy pastel cyan & warm sand",
            kind: .isp, simulationKey: "ASTIA", dynamicRange: 200,
            highlightTone: -1.5, shadowTone: -1.0, color: 1, sharpness: -1, noiseReduction: -2, clarity: -1,
            grainRoughness: 1, grainSize: 0, colorChromeEffect: 1, colorChromeBlue: 1,
            wbMode: .daylight, wbKelvin: 5400, wbShiftRed: 1, wbShiftBlue: -2, monochromeFilter: "NONE",
            exposureNote: "+1/3 EV for bright coastal light", sourceURL: "", boxHex: 0x3b8698, sensorProfile: .none
        ),
        GrainFilm(
            id: "isp-golden", code: "GLN", name: "Golden Hour", subtitle: "Late afternoon amber warmth",
            kind: .isp, simulationKey: "CLASSIC_CHROME", dynamicRange: 200,
            highlightTone: -1.0, shadowTone: 0.5, color: 2, sharpness: 0, noiseReduction: -2, clarity: -1,
            grainRoughness: 1, grainSize: 0, colorChromeEffect: 2, colorChromeBlue: 1,
            wbMode: .shade, wbKelvin: 6000, wbShiftRed: 2, wbShiftBlue: -3, monochromeFilter: "NONE",
            exposureNote: "Rich warm golden highlights", sourceURL: "", boxHex: 0xc9822b, sensorProfile: .none
        ),
        GrainFilm(
            id: "isp-afterhours", code: "AFT", name: "After Hours", subtitle: "High-contrast street monochrome",
            kind: .isp, simulationKey: "ACROS", dynamicRange: 200,
            highlightTone: 1.0, shadowTone: 2.0, color: 0, sharpness: 1, noiseReduction: -3, clarity: 2,
            grainRoughness: 2, grainSize: 1, colorChromeEffect: 0, colorChromeBlue: 0,
            wbMode: .auto, wbKelvin: 5200, wbShiftRed: 0, wbShiftBlue: 0, monochromeFilter: "Y",
            exposureNote: "Expose for highlights", sourceURL: "", boxHex: 0x343633, sensorProfile: .none
        ),

        // 11 Published ◆ Recipes
        GrainFilm(
            id: "reggies-portra", code: "PTR", name: "Reggie's Portra", subtitle: "Reggie Ballesteros · Classic Chrome",
            kind: .recipe, simulationKey: "CLASSIC_CHROME", dynamicRange: 0,
            highlightTone: -1, shadowTone: -1, color: 2, sharpness: -2, noiseReduction: -4, clarity: 0,
            grainRoughness: 1, grainSize: 0, colorChromeEffect: 2, colorChromeBlue: 1,
            wbMode: .auto, wbKelvin: 5200, wbShiftRed: 2, wbShiftBlue: -4, monochromeFilter: "NONE",
            exposureNote: "All-around warm versatile Portra 400 palette",
            sourceURL: "https://fujixweekly.com/2022/06/11/fujifilm-x-trans-iv-film-simulation-recipe-reggies-portra/",
            boxHex: 0xd99b43, sensorProfile: .none
        ),
        GrainFilm(
            id: "reggies-superia", code: "SUP", name: "Reggie's Superia", subtitle: "Reggie Ballesteros · Classic Neg",
            kind: .recipe, simulationKey: "CLASSIC_NEG", dynamicRange: 0,
            highlightTone: -1, shadowTone: -1.5, color: 1, sharpness: -2, noiseReduction: -4, clarity: 0,
            grainRoughness: 1, grainSize: 0, colorChromeEffect: 1, colorChromeBlue: 1,
            wbMode: .auto, wbKelvin: 5200, wbShiftRed: 1, wbShiftBlue: -1, monochromeFilter: "NONE",
            exposureNote: "Emerald greens and punchy consumer Fujicolor snap",
            sourceURL: "https://fujixweekly.com/",
            boxHex: 0x2c8a56, sensorProfile: .none
        ),
        GrainFilm(
            id: "kodak-gold-200", code: "GLD", name: "Kodak Gold 200", subtitle: "Fuji X Weekly · Classic Chrome",
            kind: .recipe, simulationKey: "CLASSIC_CHROME", dynamicRange: 400,
            highlightTone: -2, shadowTone: 1, color: 3, sharpness: -2, noiseReduction: -4, clarity: -2,
            grainRoughness: 2, grainSize: 1, colorChromeEffect: 1, colorChromeBlue: 1,
            wbMode: .daylight, wbKelvin: 5300, wbShiftRed: 4, wbShiftBlue: -5, monochromeFilter: "NONE",
            exposureNote: "+1/3 to +1 EV for sunny nostalgic warmth",
            sourceURL: "https://fujixweekly.com/2020/04/16/my-fujifilm-x-t30-kodak-gold-200-film-simulation-recipe/",
            boxHex: 0xe0a526, sensorProfile: .none
        ),
        GrainFilm(
            id: "bright-kodak", code: "BKD", name: "Bright Kodak", subtitle: "Fuji X Weekly · Classic Chrome",
            kind: .recipe, simulationKey: "CLASSIC_CHROME", dynamicRange: 200,
            highlightTone: -2, shadowTone: -2, color: 4, sharpness: -1, noiseReduction: -4, clarity: -3,
            grainRoughness: 2, grainSize: 1, colorChromeEffect: 2, colorChromeBlue: 1,
            wbMode: .daylight, wbKelvin: 5300, wbShiftRed: 5, wbShiftBlue: -6, monochromeFilter: "NONE",
            exposureNote: "+2/3 to +1 EV for luminous airy daylight",
            sourceURL: "https://fujixweekly.com/2022/06/10/fujifilm-x-trans-iv-film-simulation-recipe-bright-kodak/",
            boxHex: 0xebb638, sensorProfile: .none
        ),
        GrainFilm(
            id: "kodak-800-v3", code: "K800", name: "Kodak 800 v3", subtitle: "Fuji X Weekly · Sensor-Calibrated",
            kind: .recipe, simulationKey: "CLASSIC_CHROME", dynamicRange: 400,
            highlightTone: -1, shadowTone: 1, color: 3, sharpness: 0, noiseReduction: -4, clarity: -4,
            grainRoughness: 2, grainSize: 1, colorChromeEffect: 2, colorChromeBlue: 0,
            wbMode: .kelvin, wbKelvin: 6600, wbShiftRed: -1, wbShiftBlue: -3, monochromeFilter: "NONE",
            exposureNote: "+1/3 to +1 EV; RAF-calibrated 5693K creamy Portra 800 palette",
            sourceURL: "https://fujixweekly.com/2024/02/14/kodak-portra-800-v3-fujifilm-x-t5-x-trans-v-x-e4-x-trans-iv-film-simulation-recipe/",
            boxHex: 0xd98836, sensorProfile: .kodak800v3
        ),
        GrainFilm(
            id: "leica-x", code: "LCX", name: "Leica X", subtitle: "Carl Cruz · Sensor-Calibrated",
            kind: .recipe, simulationKey: "CLASSIC_CHROME", dynamicRange: 0,
            highlightTone: -1, shadowTone: 0, color: 4, sharpness: 0, noiseReduction: -4, clarity: 0,
            grainRoughness: 0, grainSize: 0, colorChromeEffect: 2, colorChromeBlue: 2,
            wbMode: .auto, wbKelvin: 5200, wbShiftRed: 1, wbShiftBlue: -2, monochromeFilter: "NONE",
            exposureNote: "Crisp Leica M11/Q3 micro-contrast and deep primary pigment density",
            sourceURL: "https://carlcruzsnaps.com/leica-x.html",
            boxHex: 0xc9302c, sensorProfile: .leicaX
        ),
        GrainFilm(
            id: "kodachrome-64", code: "K64", name: "Kodachrome 64", subtitle: "Fuji X Weekly · Classic Chrome",
            kind: .recipe, simulationKey: "CLASSIC_CHROME", dynamicRange: 200,
            highlightTone: 0, shadowTone: 0.5, color: 2, sharpness: 1, noiseReduction: -4, clarity: 3,
            grainRoughness: 1, grainSize: 0, colorChromeEffect: 2, colorChromeBlue: 1,
            wbMode: .daylight, wbKelvin: 5300, wbShiftRed: 2, wbShiftBlue: -5, monochromeFilter: "NONE",
            exposureNote: "0 to +2/3 EV; archival slide clarity and saturated reds",
            sourceURL: "https://fujixweekly.com/2020/05/27/my-fujifilm-x100v-kodachrome-64-film-simulation-recipe/",
            boxHex: 0xc83e2b, sensorProfile: .none
        ),
        GrainFilm(
            id: "pacific-blues", code: "PAC", name: "Pacific Blues", subtitle: "Fuji X Weekly · Classic Neg",
            kind: .recipe, simulationKey: "CLASSIC_NEG", dynamicRange: 400,
            highlightTone: -1, shadowTone: 0, color: 4, sharpness: -2, noiseReduction: -4, clarity: -4,
            grainRoughness: 2, grainSize: 1, colorChromeEffect: 2, colorChromeBlue: 2,
            wbMode: .kelvin, wbKelvin: 5800, wbShiftRed: 1, wbShiftBlue: -3, monochromeFilter: "NONE",
            exposureNote: "+1/3 to +1 EV; deep coastal teal skies and warm highlights",
            sourceURL: "https://fujixweekly.com/2022/08/04/fujifilm-x-trans-iv-film-simulation-recipe-pacific-blues/",
            boxHex: 0x286c8e, sensorProfile: .none
        ),
        GrainFilm(
            id: "vibrant-arizona", code: "ARZ", name: "Vibrant Arizona", subtitle: "Fuji X Weekly · Classic Chrome",
            kind: .recipe, simulationKey: "CLASSIC_CHROME", dynamicRange: 400,
            highlightTone: -2, shadowTone: -2, color: 4, sharpness: -2, noiseReduction: -4, clarity: -3,
            grainRoughness: 1, grainSize: 1, colorChromeEffect: 2, colorChromeBlue: 1,
            wbMode: .kelvin, wbKelvin: 4350, wbShiftRed: 6, wbShiftBlue: -8, monochromeFilter: "NONE",
            exposureNote: "+2/3 to +1 1/3 EV; Wes Anderson desert gold & turquoise",
            sourceURL: "https://fujixweekly.com/2023/04/25/getting-a-wes-anderson-look-from-your-fujifilm-camera-4-new-film-simulation-recipes/",
            boxHex: 0xd96e3a, sensorProfile: .none
        ),
        GrainFilm(
            id: "cinestill-800t", code: "800T", name: "CineStill 800T", subtitle: "Vision3 Tungsten · Red Halation",
            kind: .recipe, simulationKey: "ETERNA", dynamicRange: 400,
            highlightTone: -1, shadowTone: 2, color: 4, sharpness: -1, noiseReduction: -4, clarity: -5,
            grainRoughness: 1, grainSize: 1, colorChromeEffect: 2, colorChromeBlue: 2,
            wbMode: .fluorescent3, wbKelvin: 3450, wbShiftRed: -6, wbShiftBlue: -4, monochromeFilter: "NONE",
            exposureNote: "3450K Vision3 tungsten + remjet-free red-orange halation around night lamps",
            sourceURL: "https://fujixweekly.com/2020/10/06/fujifilm-x100v-film-simulation-recipe-cinestill-800t/",
            boxHex: 0xb8352f, sensorProfile: .cinestill800T
        ),
        GrainFilm(
            id: "kodak-tri-x-400", code: "TX", name: "Kodak Tri-X 400", subtitle: "Anders Lindborg · Acros",
            kind: .recipe, simulationKey: "ACROS", dynamicRange: 400,
            highlightTone: 1, shadowTone: 3, color: 0, sharpness: 1, noiseReduction: -4, clarity: 4,
            grainRoughness: 2, grainSize: 1, colorChromeEffect: 0, colorChromeBlue: 0,
            wbMode: .auto, wbKelvin: 5200, wbShiftRed: 0, wbShiftBlue: 0, monochromeFilter: "Y",
            exposureNote: "Classic gritty silver-halide street reportage contrast",
            sourceURL: "https://fujixweekly.com/",
            boxHex: 0x2e2f2c, sensorProfile: .none
        ),

        // 12 Official Fujifilm Film Simulations + Sony s709
        sim("PROVIA", "PRV", "Provia / Standard", "Natural reference colour", 0x2d6da3),
        sim("VELVIA", "VLV", "Velvia / Vivid", "Rich landscape chroma & contrast", 0xb8324f),
        sim("ASTIA", "AST", "Astia / Soft", "Smooth skin tones & gentle contrast", 0xd46b8c),
        sim("CLASSIC_CHROME", "CC", "Classic Chrome", "Documentary editorial palette", 0x6f6858),
        sim("REALA_ACE", "RLA", "Reala Ace", "True-to-life tonal fidelity", 0x3b7d63),
        sim("PRO_NEG_HI", "PNH", "Pro Neg. Hi", "Crisp studio portrait contrast", 0x5c6274),
        sim("PRO_NEG_STD", "PNS", "Pro Neg. Std", "Soft neutral portrait gradation", 0x767c8c),
        sim("CLASSIC_NEG", "CN", "Classic Neg.", "Superia colour-contrast curve", 0x34754c),
        sim("NOSTALGIC_NEG", "NN", "Nostalgic Neg.", "Amber highlights & lush shadows", 0xb66a32),
        sim("ETERNA", "ETN", "Eterna / Cinema", "Wide-latitude motion picture look", 0x3a6470),
        sim("ETERNA_BLEACH_BYPASS", "EBB", "Bleach Bypass", "Silver-retained high contrast", 0x4b5154),
        sim("ACROS", "ACR", "Acros", "Fine-grain monochrome", 0x262725),
        GrainFilm(
            id: "sony-s709", code: "709", name: "Sony s709", subtitle: "S-Gamut3.Cine / S-Log3 Cinema",
            kind: .sonyS709, simulationKey: "SONY_S709", dynamicRange: 400,
            highlightTone: 0, shadowTone: 0, color: 0, sharpness: 0, noiseReduction: -2, clarity: 0,
            grainRoughness: 0, grainSize: 0, colorChromeEffect: 0, colorChromeBlue: 0,
            wbMode: .auto, wbKelvin: 5200, wbShiftRed: 0, wbShiftBlue: 0, monochromeFilter: "NONE",
            exposureNote: "18% middle-grey cinema S-Log3 development", sourceURL: "",
            boxHex: 0xd45b24, sensorProfile: .none
        )
    ]

    private static func sim(_ key: String, _ code: String, _ name: String, _ sub: String, _ hex: UInt32) -> GrainFilm {
        GrainFilm(
            id: "sim-\(key.lowercased())", code: code, name: name, subtitle: sub,
            kind: .fujiSim, simulationKey: key, dynamicRange: 100,
            highlightTone: 0, shadowTone: 0, color: 0, sharpness: 0, noiseReduction: -2, clarity: 0,
            grainRoughness: 0, grainSize: 0, colorChromeEffect: 0, colorChromeBlue: 0,
            wbMode: .auto, wbKelvin: 5200, wbShiftRed: 0, wbShiftBlue: 0, monochromeFilter: "NONE",
            exposureNote: "Official Fujifilm F-Log2 simulation", sourceURL: "",
            boxHex: hex, sensorProfile: .none
        )
    }
}

public enum ExposureLadder {
    public struct Stop: Equatable {
        public let label: String
        public let seconds: Double // 0 = Auto
    }

    public static let shutterStops: [Stop] = [
        Stop(label: "A", seconds: 0),
        Stop(label: "4000", seconds: 1.0 / 4000.0),
        Stop(label: "2000", seconds: 1.0 / 2000.0),
        Stop(label: "1000", seconds: 1.0 / 1000.0),
        Stop(label: "500", seconds: 1.0 / 500.0),
        Stop(label: "250", seconds: 1.0 / 250.0),
        Stop(label: "125", seconds: 1.0 / 125.0),
        Stop(label: "60", seconds: 1.0 / 60.0),
        Stop(label: "30", seconds: 1.0 / 30.0),
        Stop(label: "15", seconds: 1.0 / 15.0),
        Stop(label: "8", seconds: 1.0 / 8.0),
        Stop(label: "4", seconds: 1.0 / 4.0),
        Stop(label: "2", seconds: 1.0 / 2.0),
        Stop(label: "1\"", seconds: 1.0)
    ]

    public static let isoStops: [Int] = [0, 50, 80, 100, 160, 200, 320, 400, 640, 800, 1250, 1600, 3200, 6400]

    public static let evStops: [Float] = [
        -2.0, -1.67, -1.33, -1.0, -0.67, -0.33, 0.0,
        0.33, 0.67, 1.0, 1.33, 1.67, 2.0
    ]

    public static func formatEv(_ ev: Float) -> String {
        if abs(ev) < 0.04 { return "0" }
        let sign = ev > 0 ? "+" : "−"
        let a = abs(ev)
        let whole = Int(floor(a + 0.02))
        let frac = a - Float(whole)
        if frac < 0.16 {
            return "\(sign)\(whole)"
        } else if frac < 0.50 {
            return whole == 0 ? "\(sign)⅓" : "\(sign)\(whole)⅓"
        } else if frac < 0.84 {
            return whole == 0 ? "\(sign)⅔" : "\(sign)\(whole)⅔"
        } else {
            return "\(sign)\(whole + 1)"
        }
    }

    public static func formatShutter(_ seconds: Double) -> String {
        guard seconds > 0 else { return "AUTO" }
        if seconds >= 0.45 {
            return String(format: "%.1f\"", seconds).replacingOccurrences(of: ".0\"", with: "\"")
        }
        let inv = Int((1.0 / seconds).rounded())
        return "1/\(max(1, inv))"
    }
}

/// Pure 360-degree analog focus-lever math matching Android FocusMath.
public enum FocusLeverMath {
    public struct Response {
        public let vx: CGFloat
        public let vy: CGFloat
        public let visX: CGFloat
        public let visY: CGFloat
        public let speed: CGFloat
    }

    public static func response(dx: CGFloat, dy: CGFloat, maxRadius: CGFloat, deadzone: CGFloat = 0.13) -> Response {
        let r = max(1.0, maxRadius)
        let dist = hypot(dx, dy)
        guard dist > 1e-5, dist.isFinite else {
            return Response(vx: 0, vy: 0, visX: 0, visY: 0, speed: 0)
        }
        let ux = dx / dist
        let uy = dy / dist
        let rawMag = min(1.0, dist / r)
        let visX = ux * rawMag
        let visY = uy * rawMag
        let dz = max(0.0, min(0.6, deadzone))
        if rawMag <= dz {
            return Response(vx: 0, vy: 0, visX: visX, visY: visY, speed: 0)
        }
        let m = (rawMag - dz) / (1.0 - dz)
        let speed = 0.24 * m + 0.76 * m * m
        return Response(vx: ux * speed, vy: uy * speed, visX: visX, visY: visY, speed: speed)
    }

    public static func floatingOrigin(origin: CGPoint, touch: CGPoint, leashRadius: CGFloat) -> CGPoint {
        let leash = max(1.0, leashRadius)
        let dx = touch.x - origin.x
        let dy = touch.y - origin.y
        let dist = hypot(dx, dy)
        guard dist > leash, dist.isFinite else { return origin }
        let scale = leash / dist
        return CGPoint(x: touch.x - dx * scale, y: touch.y - dy * scale)
    }

    public static func glideStep(point: CGPoint, vx: CGFloat, vy: CGFloat, dt: CGFloat, maxPerSec: CGFloat = 0.90) -> CGPoint {
        let clampedDt = dt.isFinite ? max(0.0, min(0.05, dt)) : 0.0
        let nx = max(0.06, min(0.94, point.x + vx * maxPerSec * clampedDt))
        let ny = max(0.06, min(0.94, point.y + vy * maxPerSec * clampedDt))
        return CGPoint(x: nx, y: ny)
    }

    /// Returns -1 neutral, 1 UP, 2 RIGHT, 3 DOWN, 4 LEFT with hysteresis.
    public static func cardinal(nx: CGFloat, ny: CGFloat, threshold: CGFloat = 0.32, current: Int = -1) -> Int {
        let mag = hypot(nx, ny)
        let enter = max(0.08, threshold)
        let limit = current > 0 ? enter * 0.72 : enter
        guard mag.isFinite, mag >= limit else { return -1 }
        let ax = abs(nx), ay = abs(ny)
        if current == 2 || current == 4 {
            if ax * 1.15 >= ay { return nx >= 0 ? 2 : 4 }
        } else if current == 1 || current == 3 {
            if ay * 1.15 >= ax { return ny >= 0 ? 3 : 1 }
        }
        if ax >= ay { return nx >= 0 ? 2 : 4 }
        return ny >= 0 ? 3 : 1
    }
}
