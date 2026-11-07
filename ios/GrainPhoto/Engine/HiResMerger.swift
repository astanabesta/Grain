import Foundation
import CoreGraphics

/// Streaming 4-frame Tripod Multi-Frame Super-Resolution accumulator (~36 MP or ~48 MP) in 16-bit scene-linear Rec.2020.
///
/// Designed specifically to fit comfortably within 3 GB RAM devices (e.g. iPhone XR):
/// - Holds only a single `[UInt16]` RGB accumulator (~216 MB at 36 MP, ~288 MB at 48 MP) plus a 768 KB anchor pyramid.
/// - Each 12 MP Bayer RAW frame is decoded inside an `autoreleasepool`, sub-pixel aligned across a 4x4
///   quadrant tile grid (~0.02 px parabolic refinement), splatted with anti-ghost rejection across all CPU
///   cores via `DispatchQueue.concurrentPerform`, and immediately released before the next frame arrives.
public final class HiResAccumulator {
    /// Target output pixel count for the 36 MP mode.
    public static let targetPixels: Int = 36_000_000
    /// Target output pixel count for the 48 MP mode.
    public static let targetPixels48: Int = 48_000_000
    /// Fixed-point linear scale: 1.0 scene-linear = 8192, max per frame = 16383 (~2.0x linear headroom),
    /// so 4 accumulated frames (4 * 16383 = 65,532) never overflow UInt16 (65,535).
    public static let oneLinear: Float = 8192.0
    public static let maxPerFrameCode: Int = 16383

    private static let alignLong: Int = 512
    private static let tilesX: Int = 4
    private static let tilesY: Int = 4
    private static let searchRadius: Int = 4

    public let outWidth: Int
    public let outHeight: Int
    /// Target output pixel count for this merge (36 MP or 48 MP).
    public let targetPixelCount: Int
    private let aspectRatio: String
    private let digitalZoom: Float

    private var srcWidth: Int = 0
    private var srcHeight: Int = 0
    private var cropX0: Double = 0
    private var cropY0: Double = 0
    private var cropW: Double = 0
    private var cropH: Double = 0

    private var gridW: Int = 0
    private var gridH: Int = 0
    private var refGrid: [Float] = []
    public var accRgb: [UInt16] = []
    public private(set) var framesAdded: Int = 0
    public private(set) var usedTrueRaw: Bool = false
    private var shiftReports: [String] = []

    public init(aspectRatio: String, digitalZoom: Float, targetPixelCount: Int = HiResAccumulator.targetPixels) {
        self.aspectRatio = aspectRatio
        self.digitalZoom = max(1.0, min(5.0, digitalZoom))
        self.targetPixelCount = targetPixelCount
        // Provisional dimensions; locked to exact sensor orientation on Frame 1
        let dims = Self.computeOutputDimensions(portrait: true, ratioString: aspectRatio, targetPixelCount: targetPixelCount)
        self.outWidth = dims.width
        self.outHeight = dims.height
    }

    public static func computeOutputDimensions(portrait: Bool, ratioString: String, targetPixelCount: Int = HiResAccumulator.targetPixels) -> (width: Int, height: Int) {
        let aspect: Double // width / height
        switch ratioString {
        case "1:1":
            aspect = 1.0
        case "9:16":
            aspect = portrait ? (9.0 / 16.0) : (16.0 / 9.0)
        default:
            aspect = portrait ? (3.0 / 4.0) : (4.0 / 3.0)
        }
        let h = sqrt(Double(targetPixelCount) / aspect)
        let w = h * aspect
        let evenW = max(64, (Int(w.rounded()) + 2) & ~3)
        let evenH = max(64, (Int(h.rounded()) + 2) & ~3)
        return (evenW, evenH)
    }

    public private(set) var actualOutWidth: Int = 0
    public private(set) var actualOutHeight: Int = 0

    public var shiftsSummary: String {
        shiftReports.joined(separator: " ")
    }

    /// Decodes, sub-pixel aligns across 4x4 quadrant tiles, and accumulates one frame, immediately freeing the 12MP input.
    public func addCapture(rawDngData: Data?, fallbackImageData: Data?, preferRaw: Bool) -> Bool {
        var success = false
        autoreleasepool {
            guard let decoded = GrainDeveloper.decodeFrameForHiRes(
                rawDngData: rawDngData,
                fallbackImageData: fallbackImageData,
                preferRaw: preferRaw
            ) else {
                return
            }
            if framesAdded == 0 {
                srcWidth = decoded.width
                srcHeight = decoded.height
                usedTrueRaw = decoded.usedRaw
                let portrait = srcHeight >= srcWidth
                let dims = Self.computeOutputDimensions(portrait: portrait, ratioString: aspectRatio, targetPixelCount: targetPixelCount)
                actualOutWidth = dims.width
                actualOutHeight = dims.height

                let targetAspect = Double(actualOutWidth) / Double(actualOutHeight)
                let sw = Double(srcWidth), sh = Double(srcHeight)
                var cw = sw, ch = sw / targetAspect
                if ch > sh {
                    ch = sh
                    cw = sh * targetAspect
                }
                let effZoom = decoded.usedRaw ? Double(digitalZoom) : 1.0
                cw /= effZoom
                ch /= effZoom
                cropW = cw
                cropH = ch
                cropX0 = (sw - cw) * 0.5
                cropY0 = (sh - ch) * 0.5

                if srcWidth >= srcHeight {
                    gridW = Self.alignLong
                    gridH = max(32, Int((Double(Self.alignLong) * sh / sw).rounded()))
                } else {
                    gridH = Self.alignLong
                    gridW = max(32, Int((Double(Self.alignLong) * sw / sh).rounded()))
                }
                accRgb = [UInt16](repeating: 0, count: actualOutWidth * actualOutHeight * 3)
            } else if decoded.width != srcWidth || decoded.height != srcHeight {
                return
            }

            let curGrid = Self.buildLuminanceGrid(
                rgb: decoded.rgb,
                srcW: srcWidth,
                srcH: srcHeight,
                gw: gridW,
                gh: gridH
            )

            var tileDx = [Float](repeating: 0, count: Self.tilesX * Self.tilesY)
            var tileDy = [Float](repeating: 0, count: Self.tilesX * Self.tilesY)
            var tileTrust = [Float](repeating: 1.0, count: Self.tilesX * Self.tilesY)

            if framesAdded == 0 {
                refGrid = curGrid
                shiftReports.append("f1:(0.00,0.00)px")
            } else {
                let scaleToFullX = Float(srcWidth) / Float(gridW)
                let scaleToFullY = Float(srcHeight) / Float(gridH)
                var meanDx: Float = 0
                var meanDy: Float = 0
                for ty in 0..<Self.tilesY {
                    for tx in 0..<Self.tilesX {
                        let idx = ty * Self.tilesX + tx
                        let est = Self.estimateTileShift(ref: refGrid, cur: curGrid, gw: gridW, gh: gridH, tx: tx, ty: ty)
                        tileDx[idx] = est.dx * scaleToFullX
                        tileDy[idx] = est.dy * scaleToFullY
                        let mad = est.mad
                        let trust: Float = mad <= 0.030 ? 1.0 : (mad >= 0.075 ? 0.0 : (0.075 - mad) / 0.045)
                        tileTrust[idx] = trust
                        meanDx += tileDx[idx]
                        meanDy += tileDy[idx]
                    }
                }
                let denom = Float(Self.tilesX * Self.tilesY)
                shiftReports.append(String(format: "f%d:(%+.2f,%+.2f)px", framesAdded + 1, meanDx / denom, meanDy / denom))
            }

            splatFrame(
                srcRgb: decoded.rgb,
                frameIndex: framesAdded,
                tileDx: tileDx,
                tileDy: tileDy,
                tileTrust: tileTrust
            )
            framesAdded += 1
            success = true
        }
        return success
    }

    private func splatFrame(
        srcRgb: [Float],
        frameIndex: Int,
        tileDx: [Float],
        tileDy: [Float],
        tileTrust: [Float]
    ) {
        let ow = actualOutWidth
        let oh = actualOutHeight
        let sw = srcWidth
        let sh = srcHeight
        let cx0 = Float(cropX0)
        let cy0 = Float(cropY0)
        let invScaleX = Float(cropW) / Float(ow)
        let invScaleY = Float(cropH) / Float(oh)
        let isAnchor = (frameIndex == 0)
        let prevCountInv: Float = frameIndex > 0 ? (1.0 / Float(frameIndex)) : 1.0
        let stripH = 128
        let numStrips = (oh + stripH - 1) / stripH

        srcRgb.withUnsafeBufferPointer { srcPtr in
            tileDx.withUnsafeBufferPointer { dxPtr in
                tileDy.withUnsafeBufferPointer { dyPtr in
                    tileTrust.withUnsafeBufferPointer { trustPtr in
                        accRgb.withUnsafeMutableBufferPointer { accPtr in
                            guard let sBase = srcPtr.baseAddress,
                                  let dxBase = dxPtr.baseAddress,
                                  let dyBase = dyPtr.baseAddress,
                                  let trBase = trustPtr.baseAddress,
                                  let aBase = accPtr.baseAddress else { return }

                            DispatchQueue.concurrentPerform(iterations: numStrips) { stripIdx in
                                let ys = stripIdx * stripH
                                let ye = min(oh, ys + stripH)
                                for oy in ys..<ye {
                                    let baseSy = cy0 + (Float(oy) + 0.5) * invScaleY - 0.5
                                    let tyCoord = (Float(oy) / Float(oh)) * Float(Self.tilesY) - 0.5
                                    let rowOff = oy * ow * 3

                                    for ox in 0..<ow {
                                        var sx = cx0 + (Float(ox) + 0.5) * invScaleX - 0.5
                                        var sy = baseSy
                                        var trust: Float = 1.0

                                        if !isAnchor {
                                            let txCoord = (Float(ox) / Float(ow)) * Float(Self.tilesX) - 0.5
                                            let interp = Self.bilerpTile(dxBase: dxBase, dyBase: dyBase, trBase: trBase, tx: txCoord, ty: tyCoord)
                                            sx += interp.dx
                                            sy += interp.dy
                                            trust = interp.trust
                                        }

                                        let sample = Self.sampleCatmullRomRGB(sBase: sBase, w: sw, h: sh, x: sx, y: sy)
                                        let rCode = min(Self.maxPerFrameCode, max(0, Int((sample.r * Self.oneLinear).rounded())))
                                        let gCode = min(Self.maxPerFrameCode, max(0, Int((sample.g * Self.oneLinear).rounded())))
                                        let bCode = min(Self.maxPerFrameCode, max(0, Int((sample.b * Self.oneLinear).rounded())))

                                        let pOff = rowOff + ox * 3
                                        if isAnchor {
                                            aBase[pOff] = UInt16(rCode)
                                            aBase[pOff + 1] = UInt16(gCode)
                                            aBase[pOff + 2] = UInt16(bCode)
                                        } else {
                                            let refR = Float(aBase[pOff]) * prevCountInv
                                            let refG = Float(aBase[pOff + 1]) * prevCountInv
                                            let refB = Float(aBase[pOff + 2]) * prevCountInv
                                            let diff = max(abs(Float(rCode) - refR), max(abs(Float(gCode) - refG), abs(Float(bCode) - refB)))
                                            let pixTrust: Float = diff <= 650 ? 1.0 : (diff >= 1550 ? 0.0 : (1550.0 - diff) / 900.0)
                                            let w = trust * pixTrust
                                            let addR = UInt16(min(Self.maxPerFrameCode, max(0, Int((w * Float(rCode) + (1.0 - w) * refR).rounded()))))
                                            let addG = UInt16(min(Self.maxPerFrameCode, max(0, Int((w * Float(gCode) + (1.0 - w) * refG).rounded()))))
                                            let addB = UInt16(min(Self.maxPerFrameCode, max(0, Int((w * Float(bCode) + (1.0 - w) * refB).rounded()))))
                                            aBase[pOff] = aBase[pOff] &+ addR
                                            aBase[pOff + 1] = aBase[pOff + 1] &+ addG
                                            aBase[pOff + 2] = aBase[pOff + 2] &+ addB
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static func buildLuminanceGrid(rgb: [Float], srcW: Int, srcH: Int, gw: Int, gh: Int) -> [Float] {
        var grid = [Float](repeating: 0, count: gw * gh)
        var counts = [Int](repeating: 0, count: gw * gh)
        // Stride by 2x2 in source for fast, alias-free box luminance pyramid
        var y = 0
        while y < srcH {
            let gy = min(gh - 1, (y * gh) / srcH)
            let rOff = y * srcW * 3
            var x = 0
            while x < srcW {
                let gx = min(gw - 1, (x * gw) / srcW)
                let o = rOff + x * 3
                let luma = max(0, 0.2126 * rgb[o] + 0.7152 * rgb[o + 1] + 0.0722 * rgb[o + 2])
                let idx = gy * gw + gx
                grid[idx] += sqrtf(min(1.5, luma))
                counts[idx] += 1
                x += 2
            }
            y += 2
        }
        for i in 0..<(gw * gh) where counts[i] > 0 {
            grid[i] /= Float(counts[i])
        }
        return grid
    }

    private static func estimateTileShift(
        ref: [Float],
        cur: [Float],
        gw: Int,
        gh: Int,
        tx: Int,
        ty: Int
    ) -> (dx: Float, dy: Float, mad: Float) {
        let x0 = (tx * gw) / tilesX + searchRadius + 2
        let x1 = ((tx + 1) * gw) / tilesX - searchRadius - 2
        let y0 = (ty * gh) / tilesY + searchRadius + 2
        let y1 = ((ty + 1) * gh) / tilesY - searchRadius - 2
        guard x1 > x0 + 4, y1 > y0 + 4 else { return (0, 0, 0) }

        let side = 2 * searchRadius + 1
        var costs = [Float](repeating: 0, count: side * side)
        var bestDx = 0
        var bestDy = 0
        var bestCost = Float.greatestFiniteMagnitude
        let count = Float((x1 - x0) * (y1 - y0))

        for dy in -searchRadius...searchRadius {
            for dx in -searchRadius...searchRadius {
                var sad: Float = 0
                for y in y0..<y1 {
                    let rRow = y * gw
                    let cRow = (y + dy) * gw + dx
                    for x in x0..<x1 {
                        sad += abs(ref[rRow + x] - cur[cRow + x])
                    }
                }
                let normSad = sad / count
                costs[(dy + searchRadius) * side + (dx + searchRadius)] = normSad
                if normSad < bestCost {
                    bestCost = normSad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        var subX: Float = 0
        var subY: Float = 0
        if abs(bestDx) < searchRadius {
            let cL = costs[(bestDy + searchRadius) * side + (bestDx - 1 + searchRadius)]
            let cR = costs[(bestDy + searchRadius) * side + (bestDx + 1 + searchRadius)]
            let denom = 2.0 * (cL - 2.0 * bestCost + cR)
            if abs(denom) > 1e-6 {
                subX = max(-0.5, min(0.5, (cL - cR) / denom))
            }
        }
        if abs(bestDy) < searchRadius {
            let cU = costs[(bestDy - 1 + searchRadius) * side + (bestDx + searchRadius)]
            let cD = costs[(bestDy + 1 + searchRadius) * side + (bestDx + searchRadius)]
            let denom = 2.0 * (cU - 2.0 * bestCost + cD)
            if abs(denom) > 1e-6 {
                subY = max(-0.5, min(0.5, (cU - cD) / denom))
            }
        }
        return (Float(bestDx) + subX, Float(bestDy) + subY, bestCost)
    }

    @inline(__always)
    private static func bilerpTile(
        dxBase: UnsafePointer<Float>,
        dyBase: UnsafePointer<Float>,
        trBase: UnsafePointer<Float>,
        tx: Float,
        ty: Float
    ) -> (dx: Float, dy: Float, trust: Float) {
        let cx = max(0, min(Float(tilesX - 1), tx))
        let cy = max(0, min(Float(tilesY - 1), ty))
        let x0 = Int(cx), y0 = Int(cy)
        let x1 = min(tilesX - 1, x0 + 1)
        let y1 = min(tilesY - 1, y0 + 1)
        let fx = cx - Float(x0), fy = cy - Float(y0)

        let i00 = y0 * tilesX + x0, i10 = y0 * tilesX + x1
        let i01 = y1 * tilesX + x0, i11 = y1 * tilesX + x1
        let w00 = (1 - fx) * (1 - fy), w10 = fx * (1 - fy)
        let w01 = (1 - fx) * fy, w11 = fx * fy

        return (
            dxBase[i00] * w00 + dxBase[i10] * w10 + dxBase[i01] * w01 + dxBase[i11] * w11,
            dyBase[i00] * w00 + dyBase[i10] * w10 + dyBase[i01] * w01 + dyBase[i11] * w11,
            trBase[i00] * w00 + trBase[i10] * w10 + trBase[i01] * w01 + trBase[i11] * w11
        )
    }

    @inline(__always)
    private static func sampleCatmullRomRGB(
        sBase: UnsafePointer<Float>,
        w: Int,
        h: Int,
        x: Float,
        y: Float
    ) -> (r: Float, g: Float, b: Float) {
        let cx = max(0, min(Float(w - 1), x))
        let cy = max(0, min(Float(h - 1), y))
        let ix = Int(floorf(cx))
        let iy = Int(floorf(cy))
        let fx = cx - Float(ix)
        let fy = cy - Float(iy)

        let wx0 = cubicWeight(-1 - fx), wx1 = cubicWeight(-fx)
        let wx2 = cubicWeight(1 - fx), wx3 = cubicWeight(2 - fx)
        let wy0 = cubicWeight(-1 - fy), wy1 = cubicWeight(-fy)
        let wy2 = cubicWeight(1 - fy), wy3 = cubicWeight(2 - fy)

        let x0 = max(0, min(w - 1, ix - 1)) * 3
        let x1 = max(0, min(w - 1, ix)) * 3
        let x2 = max(0, min(w - 1, ix + 1)) * 3
        let x3 = max(0, min(w - 1, ix + 2)) * 3

        let y0 = max(0, min(h - 1, iy - 1)) * w * 3
        let y1 = max(0, min(h - 1, iy)) * w * 3
        let y2 = max(0, min(h - 1, iy + 1)) * w * 3
        let y3 = max(0, min(h - 1, iy + 2)) * w * 3

        @inline(__always)
        func rowInterp(_ rOff: Int) -> (Float, Float, Float) {
            let r = sBase[rOff + x0] * wx0 + sBase[rOff + x1] * wx1 + sBase[rOff + x2] * wx2 + sBase[rOff + x3] * wx3
            let g = sBase[rOff + x0 + 1] * wx0 + sBase[rOff + x1 + 1] * wx1 + sBase[rOff + x2 + 1] * wx2 + sBase[rOff + x3 + 1] * wx3
            let b = sBase[rOff + x0 + 2] * wx0 + sBase[rOff + x1 + 2] * wx1 + sBase[rOff + x2 + 2] * wx2 + sBase[rOff + x3 + 2] * wx3
            return (r, g, b)
        }

        let r0 = rowInterp(y0)
        let r1 = rowInterp(y1)
        let r2 = rowInterp(y2)
        let r3 = rowInterp(y3)

        let rr = max(0, r0.0 * wy0 + r1.0 * wy1 + r2.0 * wy2 + r3.0 * wy3)
        let gg = max(0, r0.1 * wy0 + r1.1 * wy1 + r2.1 * wy2 + r3.1 * wy3)
        let bb = max(0, r0.2 * wy0 + r1.2 * wy1 + r2.2 * wy2 + r3.2 * wy3)
        return (rr, gg, bb)
    }

    @inline(__always)
    private static func cubicWeight(_ t: Float) -> Float {
        let a = abs(t)
        if a <= 1.0 {
            return (1.5 * a - 2.5) * a * a + 1.0
        } else if a < 2.0 {
            return ((-0.5 * a + 2.5) * a - 4.0) * a + 2.0
        }
        return 0.0
    }
}
