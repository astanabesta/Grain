import Foundation
import CoreGraphics
import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit
import ImageIO
import UniformTypeIdentifiers

public enum ColorMathEngine {
    // Fujifilm F-Log2 constants (Data Sheet Ver.1.0)
    private static let a: Float = 5.555556
    private static let b: Float = 0.064829
    private static let c: Float = 0.245281
    private static let d: Float = 0.384316
    private static let cut1: Float = 0.000889
    private static let e: Float = 8.799461
    private static let f: Float = 0.092864
    private static let cut2: Float = 0.100686685

    @inline(__always)
    public static func fLog2Encode(_ x: Float) -> Float {
        let v = max(0, x)
        if v < cut1 {
            return e * v + f
        }
        return c * log10f(a * v + b) + d
    }

    @inline(__always)
    public static func fLog2Decode(_ y: Float) -> Float {
        if y < cut2 {
            return max(0, (y - f) / e)
        }
        return max(0, (powf(10.0, (y - d) / c) - b) / a)
    }

    @inline(__always)
    public static func sLog3Encode(_ x: Float) -> Float {
        let v = max(0, x)
        if v >= 0.01125 {
            return (420.0 + log10f((v + 0.01) / (0.18 + 0.01)) * 261.5) / 1023.0
        }
        return (v * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0
    }

    @inline(__always)
    public static func sLog3Decode(_ y: Float) -> Float {
        let code = y * 1023.0
        if code >= 171.2102946929 {
            return powf(10.0, (code - 420.0) / 261.5) * (0.18 + 0.01) - 0.01
        }
        return max(0, (code - 95.0) * 0.01125 / (171.2102946929 - 95.0))
    }

    @inline(__always)
    public static func srgbDecode(_ c: Float) -> Float {
        let v = max(0, min(1, c))
        return v <= 0.04045 ? (v / 12.92) : powf((v + 0.055) / 1.055, 2.4)
    }

    @inline(__always)
    public static func srgbEncode(_ c: Float) -> Float {
        let v = max(0, min(1, c))
        return v <= 0.0031308 ? (v * 12.92) : (1.055 * powf(v, 1.0 / 2.4) - 0.055)
    }

    /// Maps Fujifilm menu Kelvin to calibrated scene CCT (prevents 6600K K800 from overshooting yellow).
    public static func calibratedKelvin(_ menuK: Int, profile: SensorProfile) -> Float {
        let k = Float(max(2500, min(10000, menuK)))
        guard profile != .none else { return k }
        if k <= 5000 { return k }
        return 5000.0 + (k - 5000.0) * 0.433
    }

    @inline(__always)
    public static func linearSrgbToOklab(r: Float, g: Float, b: Float) -> (L: Float, a: Float, b: Float) {
        let l = cbrtf(max(0, 0.41222147 * r + 0.53633254 * g + 0.05144599 * b))
        let m = cbrtf(max(0, 0.21190350 * r + 0.68069955 * g + 0.10739696 * b))
        let s = cbrtf(max(0, 0.08830246 * r + 0.28171884 * g + 0.62997870 * b))
        return (
            0.21045426 * l + 0.79361779 * m - 0.00407205 * s,
            1.97799850 * l - 2.42859220 * m + 0.45059371 * s,
            0.02590404 * l + 0.78277177 * m - 0.80867577 * s
        )
    }

    @inline(__always)
    public static func oklabToLinearSrgb(L: Float, a: Float, b: Float) -> (Float, Float, Float) {
        let l_ = L + 0.39633778 * a + 0.21580376 * b
        let m_ = L - 0.10556135 * a - 0.06385417 * b
        let s_ = L - 0.08948418 * a - 1.29148555 * b
        let l = l_ * l_ * l_
        let m = m_ * m_ * m_
        let s = s_ * s_ * s_
        let r = +4.07674166 * l - 3.30771159 * m + 0.23096993 * s
        let g = -1.26843800 * l + 2.60975740 * m - 0.34131940 * s
        let bl = -0.00419609 * l - 0.70341861 * m + 1.70761470 * s
        return softGamutCompress(r: r, g: g, b: bl)
    }

    @inline(__always)
    public static func softGamutCompress(r: Float, g: Float, b: Float) -> (Float, Float, Float) {
        let y = max(0, min(1, 0.2126 * r + 0.7152 * g + 0.0722 * b))
        var scale: Float = 1.0
        for ch in [r, g, b] {
            let d = ch - y
            if d > 1e-5 && y + d * scale > 1.0 {
                scale = min(scale, (1.0 - y) / d)
            } else if d < -1e-5 && y + d * scale < 0.0 {
                scale = min(scale, -y / d)
            }
        }
        if scale < 1.0 {
            scale = max(0, scale * 0.985)
        }
        return (
            max(0, min(1, y + (r - y) * scale)),
            max(0, min(1, y + (g - y) * scale)),
            max(0, min(1, y + (b - y) * scale))
        )
    }
}

public struct DevelopedOutput {
    public let jpegData: Data
    public let previewImage: UIImage
    public let receipt: [String: Any]
}

public enum GrainDeveloper {
    private static let ciContext: CIContext = {
        let rec2020Linear = CGColorSpace(name: CGColorSpace.extendedLinearITUR_2020)
            ?? CGColorSpaceCreateDeviceRGB()
        return CIContext(options: [
            .workingColorSpace: rec2020Linear,
            .cacheIntermediates: false
        ])
    }()

    public static func develop(
        rawDngData: Data?,
        fallbackImageData: Data?,
        film: GrainFilm,
        tuning: FilmTuning,
        aspectRatio: String,
        userEv: Float,
        captureMeta: [String: Any],
        maxDimension: Int = 3200
    ) -> DevelopedOutput? {
        let startTime = CFAbsoluteTimeGetCurrent()

        // Stage 1 & 2: Decode either hardware Bayer RAW DNG (Pro iPhones) or scene-linearized image (all iPhones)
        var usedTrueRaw = false
        var linearBuffer: (width: Int, height: Int, rgb: [Float])?

        if let rawData = rawDngData, film.isRawPipeline {
            if let decoded = decodeRawDngToLinearRec2020(rawData: rawData, maxDimension: maxDimension) {
                linearBuffer = decoded
                usedTrueRaw = true
            }
        }
        if linearBuffer == nil, let imgData = fallbackImageData ?? rawDngData {
            linearBuffer = decodeStandardImageToSceneLinearRec2020(imageData: imgData, maxDimension: maxDimension)
        }
        guard var buf = linearBuffer else { return nil }

        // Crop to selected framing ratio (3:4, 1:1, 9:16) in upright orientation
        buf = cropBuffer(buf, ratioString: aspectRatio)
        let width = buf.width
        let height = buf.height
        let count = width * height
        guard count > 0 else { return nil }

        // Stage 3: White balance (calibrated Kelvin / Vision3 3450K tungsten / WB R-B shifts)
        let (gainR, gainG, gainB) = whiteBalanceGains(film: film, tuning: tuning, usedTrueRaw: usedTrueRaw)
        let drStops: Float = tuning.dynamicRange == 400 ? 1.0 : (tuning.dynamicRange == 200 ? 0.5 : 0.0)
        let profileEv: Float = (film.sensorProfile == .cinestill800T ? -0.35 : 0.0)
        let exposureScale = powf(2.0, userEv + profileEv - drStops * 0.35)

        for i in 0..<count {
            let o = i * 3
            buf.rgb[o] = max(0, buf.rgb[o] * gainR * exposureScale)
            buf.rgb[o + 1] = max(0, buf.rgb[o + 1] * gainG * exposureScale)
            buf.rgb[o + 2] = max(0, buf.rgb[o + 2] * gainB * exposureScale)
        }

        // Stage 4: Scene-linear Halation (including CineStill 800T remjet-free red-orange halation) & Bloom
        let effHalation = max(tuning.halation, film.sensorProfile == .cinestill800T ? 4 : 0)
        let effBloom = max(tuning.bloom, film.sensorProfile == .cinestill800T ? 2 : 0)
        if effHalation > 0 || effBloom > 0 {
            applyHalationAndBloom(&buf.rgb, width: width, height: height, halation: effHalation, bloom: effBloom, profile: film.sensorProfile)
        }

        // Stage 5 & 7: F-Log2 / S-Log3 encoding + calibrated Highlight/Shadow tone curve + 3D LUT + Perceptual OKLab
        let lut = LutVault.shared.lut(for: film.simulationKey)
        let toneLUT = buildToneTable(film: film, tuning: tuning, drStops: drStops)
        let isS709 = (film.kind == .sonyS709)
        let isMono = (film.simulationKey == "ACROS")

        var srgb = [Float](repeating: 0, count: count * 3)
        let colorStrength = tuning.color
        let cce = tuning.colorChromeEffect
        let fxb = tuning.colorChromeBlue
        let profile = film.sensorProfile

        for i in 0..<count {
            let o = i * 3
            let rl = buf.rgb[o]
            let gl = buf.rgb[o + 1]
            let bl = buf.rgb[o + 2]

            let rLog: Float
            let gLog: Float
            let bLog: Float
            if isS709 {
                rLog = ColorMathEngine.sLog3Encode(rl)
                gLog = ColorMathEngine.sLog3Encode(gl)
                bLog = ColorMathEngine.sLog3Encode(bl)
            } else {
                rLog = applyToneTable(ColorMathEngine.fLog2Encode(rl), table: toneLUT)
                gLog = applyToneTable(ColorMathEngine.fLog2Encode(gl), table: toneLUT)
                bLog = applyToneTable(ColorMathEngine.fLog2Encode(bl), table: toneLUT)
            }

            var (rs, gs, bs) = lut.lookup(r: rLog, g: gLog, b: bLog)

            if isMono {
                var mono = 0.299 * rs + 0.587 * gs + 0.114 * bs
                if film.monochromeFilter == "Y" {
                    mono = 0.38 * rs + 0.56 * gs + 0.06 * bs
                } else if film.monochromeFilter == "R" {
                    mono = 0.62 * rs + 0.34 * gs + 0.04 * bs
                } else if film.monochromeFilter == "G" {
                    mono = 0.20 * rs + 0.72 * gs + 0.08 * bs
                }
                srgb[o] = mono; srgb[o + 1] = mono; srgb[o + 2] = mono
                continue
            }

            // Perceptual OKLab grading: Color, Color Chrome Effect, FX Blue, and Sensor Calibrations
            var linR = ColorMathEngine.srgbDecode(rs)
            var linG = ColorMathEngine.srgbDecode(gs)
            var linB = ColorMathEngine.srgbDecode(bs)
            var (L, a, bCh) = ColorMathEngine.linearSrgbToOklab(r: linR, g: linG, b: linB)
            let chroma = hypotf(a, bCh)

            if chroma > 1e-4 {
                let hue = atan2f(bCh, a) // radians
                // Skin protection around warm peach/orange hue (~0.6..1.15 rad)
                let skinAlign = max(0, cosf(hue - 0.85))
                let skinWeight = powf(skinAlign, 4.0) * min(1.0, chroma * 9.0)

                var satGain: Float = 1.0 + colorStrength * 0.072
                if skinWeight > 0.1 && colorStrength > 0 {
                    satGain = 1.0 + (satGain - 1.0) * (1.0 - 0.42 * skinWeight)
                }

                // Color Chrome Effect: deepens luminance slightly on high-chroma pigments so saturated reds/yellows/blues keep rich density instead of bleaching
                if cce > 0 {
                    let cceStrength: Float = cce == 2 ? 0.15 : 0.08
                    let dense = min(1.0, chroma * 6.2)
                    L = max(0, L * (1.0 - cceStrength * dense * (1.0 - 0.5 * skinWeight)))
                    satGain *= (1.0 + cceStrength * 0.45 * dense)
                }

                // Color Chrome FX Blue: deepens sky/water blues (-pi/2 .. -0.9 rad)
                if fxb > 0 && bCh < -0.01 {
                    let blueWeight = min(1.0, (-bCh) * 7.5)
                    let fxbAmt: Float = fxb == 2 ? 0.16 : 0.09
                    L = max(0, L * (1.0 - fxbAmt * blueWeight))
                    satGain *= (1.0 + fxbAmt * 0.65 * blueWeight)
                }

                // Dedicated Phone-Sensor Film Calibrations (K800, Leica X, CineStill 800T)
                switch profile {
                case .kodak800v3:
                    // Creamy Portra warm gold highlights + natural muted sage foliage
                    if hue > 1.65 && hue < 2.65 { // yellow-green foliage -> calm Portra green
                        satGain *= 0.88
                    }
                    a *= satGain
                    bCh *= satGain
                    let hiW = max(0, (L - 0.55) / 0.45)
                    bCh += 0.010 * hiW
                    a += 0.003 * hiW
                case .leicaX:
                    // Deep Leica M11/Q3 cobalt skies & rich crimson density without neon skin
                    satGain *= (1.06 - 0.08 * skinWeight)
                    a *= satGain
                    bCh *= satGain
                    let midContrast = (L - 0.50) * 0.045
                    L = max(0, min(1, L + midContrast))
                case .cinestill800T:
                    // Vision3 500T cyan-teal shadow split x warm-amber tungsten highlight glow
                    satGain *= 1.08
                    a *= satGain
                    bCh *= satGain
                    let shW = powf(max(0, 1.0 - L * 1.45), 1.4)
                    let hiW = powf(max(0, (L - 0.48) / 0.52), 1.2)
                    a -= 0.016 * shW
                    bCh -= 0.022 * shW
                    a += 0.010 * hiW
                    bCh += 0.020 * hiW
                case .none:
                    a *= satGain
                    bCh *= satGain
                }

                (linR, linG, linB) = ColorMathEngine.oklabToLinearSrgb(L: L, a: a, b: bCh)
                rs = ColorMathEngine.srgbEncode(linR)
                gs = ColorMathEngine.srgbEncode(linG)
                bs = ColorMathEngine.srgbEncode(linB)
            }

            srgb[o] = rs
            srgb[o + 1] = gs
            srgb[o + 2] = bs
        }

        // Stage 6: Mid-band Clarity & Sharpness
        if tuning.clarity != 0 || tuning.sharpness != 0 {
            applyClarityAndSharpness(&srgb, width: width, height: height, clarity: tuning.clarity, sharpness: tuning.sharpness)
        }

        // Stage 7b: Calibrated Dual-Scale Organic Film Grain
        if tuning.grainRoughness > 0 {
            applyFilmGrain(&srgb, width: width, height: height, roughness: tuning.grainRoughness, large: tuning.grainSize > 0)
        }

        // Stage 8: Encode high-quality sRGB JPEG + preview UIImage + technical JSON receipt
        guard let cgImg = makeSrgbCGImage(rgb: srgb, width: width, height: height) else { return nil }
        let uiImage = UIImage(cgImage: cgImg)
        guard let jpeg = encodeJpegWithExif(cgImage: cgImg, film: film, captureMeta: captureMeta) else { return nil }

        let elapsedMs = Int(((CFAbsoluteTimeGetCurrent() - startTime) * 1000.0).rounded())
        let receipt: [String: Any] = [
            "filmCode": film.code,
            "filmName": film.name,
            "simulation": film.simulationKey,
            "lutSource": lut.isOfficial ? "official_cube_\(lut.size)" : "analytical_flog2_\(lut.size)",
            "pipeline": usedTrueRaw ? "bayer_raw_dng_flog2" : "scene_linear_p3_flog2",
            "sensorProfile": String(describing: film.sensorProfile),
            "dimensions": "\(width)x\(height)",
            "aspectRatio": aspectRatio,
            "evCompensation": userEv,
            "dynamicRange": tuning.dynamicRange == 0 ? "AUTO" : "DR\(tuning.dynamicRange)",
            "wbMode": tuning.wbMode.rawValue,
            "wbShift": "R\(tuning.wbShiftRed >= 0 ? "+" : "")\(tuning.wbShiftRed) B\(tuning.wbShiftBlue >= 0 ? "+" : "")\(tuning.wbShiftBlue)",
            "highlightTone": tuning.highlightTone,
            "shadowTone": tuning.shadowTone,
            "color": tuning.color,
            "clarity": tuning.clarity,
            "grain": tuning.grainRoughness == 0 ? "OFF" : (tuning.grainRoughness == 2 ? "STRONG" : "WEAK"),
            "halation": effHalation,
            "bloom": effBloom,
            "sourceURL": film.sourceURL,
            "developMs": elapsedMs,
            "capture": captureMeta
        ]

        return DevelopedOutput(jpegData: jpeg, previewImage: uiImage, receipt: receipt)
    }

    // MARK: - Decoding (Hardware Bayer RAW DNG + Non-Pro iPhone Scene-Linear Fallback)

    private static func decodeRawDngToLinearRec2020(rawData: Data, maxDimension: Int) -> (width: Int, height: Int, rgb: [Float])? {
        guard let rawFilter = CIRAWFilter(imageData: rawData, identifierHint: "com.adobe.raw-image") else {
            return nil
        }
        rawFilter.boostAmount = 0.0
        rawFilter.boostShadowAmount = 0.0
        rawFilter.localToneMapAmount = 0.0
        rawFilter.colorNoiseReductionAmount = 0.35
        rawFilter.luminanceNoiseReductionAmount = 0.15
        rawFilter.sharpnessAmount = 0.0
        let native = rawFilter.nativeSize
        if max(native.width, native.height) > CGFloat(maxDimension) && native.width > 0 {
            rawFilter.scaleFactor = Float(CGFloat(maxDimension) / max(native.width, native.height))
        }
        guard let ciImg = rawFilter.outputImage else { return nil }
        return renderCIImageToLinearRec2020(ciImg, isAlreadyLinearRaw: true)
    }

    private static func decodeStandardImageToSceneLinearRec2020(imageData: Data, maxDimension: Int) -> (width: Int, height: Int, rgb: [Float])? {
        guard var ciImg = CIImage(data: imageData, options: [.applyOrientationProperty: true]) else {
            return nil
        }
        let ext = ciImg.extent
        let longest = max(ext.width, ext.height)
        if longest > CGFloat(maxDimension) && longest > 0 {
            let scale = CGFloat(maxDimension) / longest
            ciImg = ciImg.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        }
        return renderCIImageToLinearRec2020(ciImg, isAlreadyLinearRaw: false)
    }

    private static func renderCIImageToLinearRec2020(_ image: CIImage, isAlreadyLinearRaw: Bool) -> (width: Int, height: Int, rgb: [Float])? {
        let rect = image.extent.integral
        let w = Int(rect.width)
        let h = Int(rect.height)
        guard w > 16, h > 16 else { return nil }

        let colorSpace = CGColorSpace(name: CGColorSpace.extendedLinearITUR_2020)
            ?? CGColorSpace(name: CGColorSpace.linearSRGB)
            ?? CGColorSpaceCreateDeviceRGB()

        let rowBytes = w * 4 * MemoryLayout<Float>.size
        var rgba = [Float](repeating: 0, count: w * h * 4)
        rgba.withUnsafeMutableBytes { rawPtr in
            guard let base = rawPtr.baseAddress else { return }
            ciContext.render(
                image,
                toBitmap: base,
                rowBytes: rowBytes,
                bounds: rect,
                format: .RGBAf,
                colorSpace: colorSpace
            )
        }

        var rgb = [Float](repeating: 0, count: w * h * 3)
        let count = w * h
        for i in 0..<count {
            var r = max(0, rgba[i * 4])
            var g = max(0, rgba[i * 4 + 1])
            var b = max(0, rgba[i * 4 + 2])
            if !isAlreadyLinearRaw {
                // Inverse shoulder expansion for standard non-Pro iPhone captures so highlights (>0.65)
                // unpack smoothly into scene-referred headroom (~2.8x) for F-Log2 & halation.
                r = expandDisplayShoulderToScene(r)
                g = expandDisplayShoulderToScene(g)
                b = expandDisplayShoulderToScene(b)
            } else {
                // Scale linear RAW to 18% middle-grey scene reference
                r *= 1.15; g *= 1.15; b *= 1.15
            }
            rgb[i * 3] = r
            rgb[i * 3 + 1] = g
            rgb[i * 3 + 2] = b
        }
        return (w, h, rgb)
    }

    @inline(__always)
    private static func expandDisplayShoulderToScene(_ v: Float) -> Float {
        if v <= 0.60 { return v * 0.85 }
        let t = min(0.995, v)
        let hi = (t - 0.60) / (1.02 - t)
        return 0.51 + hi * 0.26
    }

    private static func cropBuffer(_ src: (width: Int, height: Int, rgb: [Float]), ratioString: String) -> (width: Int, height: Int, rgb: [Float]) {
        let portrait = src.height >= src.width
        let targetAspect: Double // width / height
        switch ratioString {
        case "1:1": targetAspect = 1.0
        case "9:16": targetAspect = portrait ? (9.0 / 16.0) : (16.0 / 9.0)
        default: targetAspect = portrait ? (3.0 / 4.0) : (4.0 / 3.0)
        }

        let sw = Double(src.width), sh = Double(src.height)
        var cw = sw, ch = sw / targetAspect
        if ch > sh {
            ch = sh
            cw = sh * targetAspect
        }
        let outW = max(2, Int(cw.rounded()) & ~1)
        let outH = max(2, Int(ch.rounded()) & ~1)
        if outW == src.width && outH == src.height { return src }

        let x0 = max(0, (src.width - outW) / 2)
        let y0 = max(0, (src.height - outH) / 2)
        var dst = [Float](repeating: 0, count: outW * outH * 3)
        for y in 0..<outH {
            let srcRow = ((y0 + y) * src.width + x0) * 3
            let dstRow = (y * outW) * 3
            dst[dstRow..<(dstRow + outW * 3)] = src.rgb[srcRow..<(srcRow + outW * 3)]
        }
        return (outW, outH, dst)
    }

    // MARK: - White Balance, Tone Table, Halation, Clarity & Grain

    private static func whiteBalanceGains(film: GrainFilm, tuning: FilmTuning, usedTrueRaw: Bool) -> (Float, Float, Float) {
        let refK: Float = 5200.0
        let targetK: Float
        if film.sensorProfile == .cinestill800T && tuning.wbMode == .fluorescent3 {
            targetK = 3450.0 // True Vision3 500T tungsten balance
        } else if tuning.wbMode == .kelvin {
            targetK = ColorMathEngine.calibratedKelvin(tuning.wbKelvin, profile: film.sensorProfile)
        } else {
            targetK = tuning.wbMode.baseKelvin
        }

        // Relative warm/cool ratio vs camera's neutral D52 reference
        let kDelta = (targetK - refK) / 4000.0
        let strength: Float = usedTrueRaw ? 0.42 : 0.30
        var rGain = 1.0 + kDelta * strength + Float(tuning.wbShiftRed) * 0.024
        var bGain = 1.0 - kDelta * strength + Float(tuning.wbShiftBlue) * 0.024
        rGain = max(0.58, min(1.68, rGain))
        bGain = max(0.58, min(1.68, bGain))
        let norm = (0.299 * rGain + 0.587 + 0.114 * bGain)
        return (rGain / norm, 1.0 / norm, bGain / norm)
    }

    private static func buildToneTable(film: GrainFilm, tuning: FilmTuning, drStops: Float) -> [Float] {
        let n = 1024
        var table = [Float](repeating: 0, count: n)
        let hTone = tuning.highlightTone
        let sTone = tuning.shadowTone
        let profile = film.sensorProfile

        for i in 0..<n {
            var x = Float(i) / Float(n - 1) // F-Log2 in [0..1], middle grey ~0.391
            let mid: Float = 0.391

            // DR200 / DR400 highlight compression shoulder
            if drStops > 0 && x > mid {
                let t = (x - mid) / (1.0 - mid)
                let comp = t / (1.0 + drStops * 0.32 * t) * (1.0 + drStops * 0.32 * 0.72)
                x = mid + min(1.0 - mid, comp * (1.0 - mid))
            }

            // Fujifilm Highlight (-2..+4) & Shadow (-2..+4) modifiers around mid-grey pivot
            if x < mid {
                let u = (mid - x) / mid // 0 at mid, 1 at black
                let push = -sTone * 0.028 * u * (1.0 - u * 0.35)
                x = max(0, x + push)
            } else {
                let u = (x - mid) / (1.0 - mid)
                let push = hTone * 0.032 * u * (1.0 - u * 0.42)
                x = min(1.0, x + push)
            }

            // Sensor-calibrated toe & shoulder profiles
            switch profile {
            case .kodak800v3:
                // Open Portra 800 shadow toe + creamy highlight rolloff
                let shLift = 0.022 * powf(max(0, 1.0 - x / mid), 1.6)
                let hiSoft = x > 0.62 ? (x - 0.62) * 0.14 : 0
                x = max(0, min(1, x + shLift - hiSoft))
            case .leicaX:
                // Grounded Leica micro-contrast toe + protected highlight shoulder
                if x < mid {
                    let u = (mid - x) / mid
                    x = max(0, x - 0.014 * u * (1.0 - u * 0.4))
                } else if x > 0.66 {
                    x -= (x - 0.66) * 0.12
                }
            case .cinestill800T:
                // Rescue Eterna's lifted cinema blacks into grounded night shadows while keeping lamp highlights smooth
                if x < mid {
                    let u = (mid - x) / mid
                    x = max(0, x - 0.028 * u * (1.0 - u * 0.25))
                } else if x > 0.64 {
                    x -= (x - 0.64) * 0.15
                }
            case .none:
                break
            }

            table[i] = max(0, min(1, x))
        }
        return table
    }

    @inline(__always)
    private static func applyToneTable(_ v: Float, table: [Float]) -> Float {
        let clamped = max(0, min(1, v))
        let f = clamped * Float(table.count - 1)
        let i0 = min(table.count - 2, Int(f))
        let frac = f - Float(i0)
        return table[i0] + (table[i0 + 1] - table[i0]) * frac
    }

    private static func applyHalationAndBloom(
        _ rgb: inout [Float],
        width: Int,
        height: Int,
        halation: Int,
        bloom: Int,
        profile: SensorProfile
    ) {
        // Compute quarter-resolution highlight scatter map for fast separable multi-radius glow
        let qw = max(4, width / 4)
        let qh = max(4, height / 4)
        var haloSrc = [Float](repeating: 0, count: qw * qh)
        var bloomSrc = [Float](repeating: 0, count: qw * qh)

        for qy in 0..<qh {
            let sy = min(height - 1, qy * 4 + 1)
            for qx in 0..<qw {
                let sx = min(width - 1, qx * 4 + 1)
                let o = (sy * width + sx) * 3
                let r = rgb[o], g = rgb[o + 1], b = rgb[o + 2]
                let yLin = 0.2627 * r + 0.6780 * g + 0.0593 * b
                if yLin > 0.50 {
                    let remjetScatter: Float = profile == .cinestill800T ? (yLin - 0.50) * 6.5 : max(0, (yLin - 0.72) * 3.2)
                    haloSrc[qy * qw + qx] = min(14.0, remjetScatter)
                }
                if yLin > 0.58 {
                    bloomSrc[qy * qw + qx] = min(6.0, (yLin - 0.58) * 2.0)
                }
            }
        }

        let radius = max(3, min(qw, qh) / 16)
        var haloBlur = boxBlur2D(haloSrc, w: qw, h: qh, radius: radius)
        haloBlur = boxBlur2D(haloBlur, w: qw, h: qh, radius: max(2, radius / 2))
        let bloomBlur = bloom > 0 ? boxBlur2D(bloomSrc, w: qw, h: qh, radius: max(3, radius)) : bloomSrc

        let haloAmt = Float(halation) * (profile == .cinestill800T ? 0.048 : 0.032)
        let bloomAmt = Float(bloom) * 0.026

        for y in 0..<height {
            let fy = (Float(y) + 0.5) / Float(height) * Float(qh) - 0.5
            let y0 = max(0, min(qh - 1, Int(floorf(fy))))
            let y1 = min(qh - 1, y0 + 1)
            let wy = max(0, min(1, fy - Float(y0)))

            for x in 0..<width {
                let fx = (Float(x) + 0.5) / Float(width) * Float(qw) - 0.5
                let x0 = max(0, min(qw - 1, Int(floorf(fx))))
                let x1 = min(qw - 1, x0 + 1)
                let wx = max(0, min(1, fx - Float(x0)))

                let h00 = haloBlur[y0 * qw + x0], h10 = haloBlur[y0 * qw + x1]
                let h01 = haloBlur[y1 * qw + x0], h11 = haloBlur[y1 * qw + x1]
                let hVal = (h00 + (h10 - h00) * wx) * (1 - wy) + (h01 + (h11 - h01) * wx) * wy

                let b00 = bloomBlur[y0 * qw + x0], b10 = bloomBlur[y0 * qw + x1]
                let b01 = bloomBlur[y1 * qw + x0], b11 = bloomBlur[y1 * qw + x1]
                let bVal = (b00 + (b10 - b00) * wx) * (1 - wy) + (b01 + (b11 - b01) * wx) * wy

                let o = (y * width + x) * 3
                // Red-orange pressure-plate scatter (strong R, secondary warm G, minimal B) + neutral-warm bloom
                rgb[o] += hVal * haloAmt * 1.00 + bVal * bloomAmt * 1.04
                rgb[o + 1] += hVal * haloAmt * 0.27 + bVal * bloomAmt * 0.98
                rgb[o + 2] += hVal * haloAmt * 0.04 + bVal * bloomAmt * 0.90
            }
        }
    }

    private static func boxBlur2D(_ src: [Float], w: Int, h: Int, radius: Int) -> [Float] {
        guard radius > 0, w > 1, h > 1 else { return src }
        var tmp = [Float](repeating: 0, count: w * h)
        var dst = [Float](repeating: 0, count: w * h)
        let inv = 1.0 / Float(radius * 2 + 1)

        for y in 0..<h {
            let row = y * w
            var acc: Float = 0
            for k in -radius...radius {
                acc += src[row + max(0, min(w - 1, k))]
            }
            for x in 0..<w {
                tmp[row + x] = acc * inv
                let outIdx = max(0, x - radius)
                let inIdx = min(w - 1, x + radius + 1)
                acc += src[row + inIdx] - src[row + outIdx]
            }
        }
        for x in 0..<w {
            var acc: Float = 0
            for k in -radius...radius {
                acc += tmp[max(0, min(h - 1, k)) * w + x]
            }
            for y in 0..<h {
                dst[y * w + x] = acc * inv
                let outIdx = max(0, y - radius)
                let inIdx = min(h - 1, y + radius + 1)
                acc += tmp[inIdx * w + x] - tmp[outIdx * w + x]
            }
        }
        return dst
    }

    private static func applyClarityAndSharpness(_ srgb: inout [Float], width: Int, height: Int, clarity: Int, sharpness: Int) {
        let count = width * height
        var luma = [Float](repeating: 0, count: count)
        for i in 0..<count {
            let o = i * 3
            luma[i] = 0.299 * srgb[o] + 0.587 * srgb[o + 1] + 0.114 * srgb[o + 2]
        }

        var clarityBase: [Float]?
        if clarity != 0 {
            let qw = max(4, width / 4), qh = max(4, height / 4)
            var qLuma = [Float](repeating: 0, count: qw * qh)
            for qy in 0..<qh {
                let sy = min(height - 1, qy * 4 + 1)
                for qx in 0..<qw {
                    qLuma[qy * qw + qx] = luma[sy * width + min(width - 1, qx * 4 + 1)]
                }
            }
            let blurredQ = boxBlur2D(qLuma, w: qw, h: qh, radius: max(2, min(qw, qh) / 28))
            var up = [Float](repeating: 0, count: count)
            for y in 0..<height {
                let qy = min(qh - 1, y / 4)
                for x in 0..<width {
                    up[y * width + x] = blurredQ[qy * qw + min(qw - 1, x / 4)]
                }
            }
            clarityBase = up
        }

        let fineBase = sharpness != 0 ? boxBlur2D(luma, w: width, h: height, radius: 1) : nil
        let clAmt = Float(clarity) * 0.065
        let shAmt = Float(sharpness) * 0.085

        for i in 0..<count {
            let y = luma[i]
            let midMask = 4.0 * y * (1.0 - y)
            var delta: Float = 0
            if let cb = clarityBase {
                delta += (y - cb[i]) * clAmt * midMask
            }
            if let fb = fineBase {
                delta += (y - fb[i]) * shAmt
            }
            if abs(delta) > 1e-5 {
                let o = i * 3
                srgb[o] = max(0, min(1, srgb[o] + delta))
                srgb[o + 1] = max(0, min(1, srgb[o + 1] + delta))
                srgb[o + 2] = max(0, min(1, srgb[o + 2] + delta))
            }
        }
    }

    private static func applyFilmGrain(_ srgb: inout [Float], width: Int, height: Int, roughness: Int, large: Bool) {
        let strength: Float = (roughness == 2 ? 0.044 : 0.024) * (large ? 1.18 : 1.0)
        var seed: UInt64 = UInt64(width * 73856093 ^ height * 19349663) &+ 0x9e3779b97f4a7c15

        @inline(__always)
        func nextGaussianApprox() -> Float {
            seed &+= 0x9e3779b97f4a7c15
            var z = seed
            z = (z ^ (z >> 30)) &* 0xbf58476d1ce4e5b9
            z = (z ^ (z >> 27)) &* 0x94d049bb133111eb
            z = z ^ (z >> 31)
            let u1 = Float(z & 0xffff) / 65535.0
            let u2 = Float((z >> 16) & 0xffff) / 65535.0
            let u3 = Float((z >> 32) & 0xffff) / 65535.0
            return (u1 + u2 + u3) - 1.5 // smooth bell distribution in [-1.5..+1.5]
        }

        let coarseW = max(2, width / (large ? 2 : 1))
        let coarseH = max(2, height / (large ? 2 : 1))
        var grainField = [Float](repeating: 0, count: coarseW * coarseH)
        for i in 0..<grainField.count {
            grainField[i] = nextGaussianApprox()
        }
        if large {
            grainField = boxBlur2D(grainField, w: coarseW, h: coarseH, radius: 1)
        }

        for y in 0..<height {
            let gy = min(coarseH - 1, large ? (y / 2) : y)
            for x in 0..<width {
                let gx = min(coarseW - 1, large ? (x / 2) : x)
                let gVal = grainField[gy * coarseW + gx]
                let o = (y * width + x) * 3
                let luma = 0.299 * srgb[o] + 0.587 * srgb[o + 1] + 0.114 * srgb[o + 2]
                // Emulsion bell mask: strongest in midtones, gentle in pure highlights & deep shadows
                let mask = 0.30 + 0.70 * (4.0 * luma * (1.0 - luma))
                let n = gVal * strength * mask
                srgb[o] = max(0, min(1, srgb[o] + n))
                srgb[o + 1] = max(0, min(1, srgb[o + 1] + n * 0.96))
                srgb[o + 2] = max(0, min(1, srgb[o + 2] + n * 1.04))
            }
        }
    }

    private static func makeSrgbCGImage(rgb: [Float], width: Int, height: Int) -> CGImage? {
        let count = width * height
        var bytes = [UInt8](repeating: 255, count: count * 4)
        for i in 0..<count {
            let o = i * 3
            let d = i * 4
            bytes[d] = UInt8(max(0, min(255, Int((rgb[o] * 255.0).rounded()))))
            bytes[d + 1] = UInt8(max(0, min(255, Int((rgb[o + 1] * 255.0).rounded()))))
            bytes[d + 2] = UInt8(max(0, min(255, Int((rgb[o + 2] * 255.0).rounded()))))
        }
        let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        guard let provider = CGDataProvider(data: Data(bytes) as CFData) else { return nil }
        return CGImage(
            width: width,
            height: height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
            provider: provider,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
        )
    }

    private static func encodeJpegWithExif(cgImage: CGImage, film: GrainFilm, captureMeta: [String: Any]) -> Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else {
            return nil
        }
        var exif: [CFString: Any] = [
            kCGImagePropertyExifUserComment: "Grain iOS · \(film.name) (\(film.code))"
        ]
        if let iso = captureMeta["iso"] as? Int {
            exif[kCGImagePropertyExifISOSpeedRatings] = [iso]
        }
        if let expSec = captureMeta["exposureSeconds"] as? Double, expSec > 0 {
            exif[kCGImagePropertyExifExposureTime] = expSec
        }
        if let fNum = captureMeta["aperture"] as? Double, fNum > 0 {
            exif[kCGImagePropertyExifFNumber] = fNum
        }
        let tiff: [CFString: Any] = [
            kCGImagePropertyTIFFSoftware: "Grain Photo iOS 0.7.6"
        ]
        let props: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: 0.95,
            kCGImagePropertyExifDictionary: exif,
            kCGImagePropertyTIFFDictionary: tiff
        ]
        CGImageDestinationAddImage(dest, cgImage, props as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return out as Data
    }
}
