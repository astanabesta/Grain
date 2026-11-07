import Foundation
import Compression

public final class CubeLUT {
    public let size: Int
    public let table: [Float] // length = size^3 * 3, R fastest then G then B
    public let isOfficial: Bool

    public init(size: Int, table: [Float], isOfficial: Bool) {
        self.size = size
        self.table = table
        self.isOfficial = isOfficial
    }

    @inline(__always)
    public func lookup(r: Float, g: Float, b: Float) -> (Float, Float, Float) {
        let n = Float(size - 1)
        let rf = max(0, min(n, r * n))
        let gf = max(0, min(n, g * n))
        let bf = max(0, min(n, b * n))

        let r0 = min(size - 2, Int(rf))
        let g0 = min(size - 2, Int(gf))
        let b0 = min(size - 2, Int(bf))
        let dr = rf - Float(r0)
        let dg = gf - Float(g0)
        let db = bf - Float(b0)

        let s = size
        let s2 = s * s
        let idx000 = (b0 * s2 + g0 * s + r0) * 3
        let idx100 = idx000 + 3
        let idx010 = idx000 + s * 3
        let idx110 = idx010 + 3
        let idx001 = idx000 + s2 * 3
        let idx101 = idx001 + 3
        let idx011 = idx001 + s * 3
        let idx111 = idx011 + 3

        @inline(__always)
        func interp(_ c: Int) -> Float {
            let c00 = table[idx000 + c] + (table[idx100 + c] - table[idx000 + c]) * dr
            let c10 = table[idx010 + c] + (table[idx110 + c] - table[idx010 + c]) * dr
            let c01 = table[idx001 + c] + (table[idx101 + c] - table[idx001 + c]) * dr
            let c11 = table[idx011 + c] + (table[idx111 + c] - table[idx011 + c]) * dr
            let c0 = c00 + (c10 - c00) * dg
            let c1 = c01 + (c11 - c01) * dg
            return max(0, min(1, c0 + (c1 - c0) * db))
        }

        return (interp(0), interp(1), interp(2))
    }

    public static func parse(data: Data, isOfficial: Bool = true) -> CubeLUT? {
        guard let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .ascii) else {
            return nil
        }
        var lutSize = 0
        var values: [Float] = []
        values.reserveCapacity(33 * 33 * 33 * 3)

        for rawLine in text.split(whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty || line.hasPrefix("#") || line.hasPrefix("TITLE") || line.hasPrefix("DOMAIN_") || line.hasPrefix("LUT_1D_") {
                continue
            }
            if line.hasPrefix("LUT_3D_SIZE") {
                let parts = line.split(whereSeparator: \.isWhitespace)
                if parts.count >= 2, let n = Int(parts[1]), n >= 8, n <= 65 {
                    lutSize = n
                }
                continue
            }
            let parts = line.split(whereSeparator: \.isWhitespace)
            if parts.count >= 3,
               let r = Float(parts[0]),
               let g = Float(parts[1]),
               let b = Float(parts[2]) {
                values.append(r)
                values.append(g)
                values.append(b)
            }
        }
        guard lutSize >= 8, values.count == lutSize * lutSize * lutSize * 3 else {
            return nil
        }
        return CubeLUT(size: lutSize, table: values, isOfficial: isOfficial)
    }
}

/// Pure-Swift PKZIP extractor using Apple's built-in Compression framework (raw DEFLATE + STORE).
public enum SimpleZipExtractor {
    public struct Entry {
        public let name: String
        public let data: Data
    }

    public static func extractCubeFiles(from archive: Data) -> [Entry] {
        var results: [Entry] = []
        let bytes = [UInt8](archive)
        let n = bytes.count
        guard n >= 22 else { return results }

        // Locate End of Central Directory (0x06054b50)
        var eocd = -1
        let minOffset = max(0, n - 65557)
        for i in stride(from: n - 22, through: minOffset, by: -1) {
            if bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 0x05 && bytes[i + 3] == 0x06 {
                eocd = i
                break
            }
        }
        guard eocd >= 0 else { return results }

        let totalEntries = Int(readU16(bytes, eocd + 10))
        var cdOffset = Int(readU32(bytes, eocd + 16))

        for _ in 0..<totalEntries {
            guard cdOffset + 46 <= n else { break }
            guard bytes[cdOffset] == 0x50, bytes[cdOffset + 1] == 0x4b,
                  bytes[cdOffset + 2] == 0x01, bytes[cdOffset + 3] == 0x02 else { break }

            let method = readU16(bytes, cdOffset + 10)
            let compSize = Int(readU32(bytes, cdOffset + 20))
            let uncompSize = Int(readU32(bytes, cdOffset + 24))
            let nameLen = Int(readU16(bytes, cdOffset + 28))
            let extraLen = Int(readU16(bytes, cdOffset + 30))
            let commentLen = Int(readU16(bytes, cdOffset + 32))
            let localOffset = Int(readU32(bytes, cdOffset + 42))

            guard cdOffset + 46 + nameLen <= n else { break }
            let nameData = Data(bytes[(cdOffset + 46)..<(cdOffset + 46 + nameLen)])
            let entryName = String(data: nameData, encoding: .utf8) ?? ""
            cdOffset += 46 + nameLen + extraLen + commentLen

            guard entryName.lowercased().hasSuffix(".cube"),
                  !entryName.contains("__MACOSX"),
                  uncompSize > 0, uncompSize < 24_000_000,
                  localOffset + 30 <= n else { continue }

            guard bytes[localOffset] == 0x50, bytes[localOffset + 1] == 0x4b,
                  bytes[localOffset + 2] == 0x03, bytes[localOffset + 3] == 0x04 else { continue }
            let lNameLen = Int(readU16(bytes, localOffset + 26))
            let lExtraLen = Int(readU16(bytes, localOffset + 28))
            let dataStart = localOffset + 30 + lNameLen + lExtraLen
            guard dataStart >= 0, dataStart + compSize <= n else { continue }

            let payload = Data(bytes[dataStart..<(dataStart + compSize)])
            if method == 0 {
                results.append(Entry(name: (entryName as NSString).lastPathComponent, data: payload))
            } else if method == 8 {
                if let inflated = inflateRawDeflate(payload, expectedSize: uncompSize) {
                    results.append(Entry(name: (entryName as NSString).lastPathComponent, data: inflated))
                }
            }
        }
        return results
    }

    private static func inflateRawDeflate(_ compressed: Data, expectedSize: Int) -> Data? {
        let dstSize = max(expectedSize + 64, 4096)
        var dst = Data(count: dstSize)
        let decodedCount: Int = compressed.withUnsafeBytes { srcRaw in
            dst.withUnsafeMutableBytes { dstRaw in
                guard let srcPtr = srcRaw.bindMemory(to: UInt8.self).baseAddress,
                      let dstPtr = dstRaw.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                return compression_decode_buffer(dstPtr, dstSize, srcPtr, compressed.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard decodedCount > 0 else { return nil }
        dst.count = decodedCount
        return dst
    }

    private static func readU16(_ b: [UInt8], _ o: Int) -> UInt16 {
        UInt16(b[o]) | (UInt16(b[o + 1]) << 8)
    }

    private static func readU32(_ b: [UInt8], _ o: Int) -> UInt32 {
        UInt32(b[o]) | (UInt32(b[o + 1]) << 8) | (UInt32(b[o + 2]) << 16) | (UInt32(b[o + 3]) << 24)
    }
}

/// Manages imported official .cube LUTs in Documents/LUTs and provides calibrated analytical F-Log2
/// base LUTs whenever official packs have not been imported yet.
public final class LutVault: ObservableObject {
    public static let shared = LutVault()

    @Published public private(set) var importedKeys: Set<String> = []
    private var cache: [String: CubeLUT] = [:]
    private let lock = NSLock()
    private let folder: URL

    private static let matchPatterns: [(key: String, tokens: [String])] = [
        ("CLASSIC_CHROME", ["CLASSICCHROME", "CLASSIC_CHROME", "CLASSIC-CHROME"]),
        ("CLASSIC_NEG", ["CLASSICNEG", "CLASSIC_NEG", "CLASSIC-NEG"]),
        ("NOSTALGIC_NEG", ["NOSTALGICNEG", "NOSTALGIC_NEG", "NOSTALGIC-NEG"]),
        ("ETERNA_BLEACH_BYPASS", ["BLEACHBYPASS", "BLEACH_BYPASS", "ETERNA_BB", "ETERNABB"]),
        ("ETERNA", ["ETERNA"]),
        ("PROVIA", ["PROVIA"]),
        ("VELVIA", ["VELVIA"]),
        ("ASTIA", ["ASTIA"]),
        ("REALA_ACE", ["REALAACE", "REALA_ACE", "REALA-ACE"]),
        ("PRO_NEG_HI", ["PRONEGHI", "PRO_NEG_HI", "PRONEG_HI"]),
        ("PRO_NEG_STD", ["PRONEGSTD", "PRO_NEG_STD", "PRONEG_STD"]),
        ("ACROS", ["ACROS"]),
        ("SONY_S709", ["S709", "SLOG3", "709"])
    ]

    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        folder = docs.appendingPathComponent("LUTs", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        refreshIndex()
    }

    public func refreshIndex() {
        var found: Set<String> = []
        if let files = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) {
            for f in files where f.pathExtension.lowercased() == "cube" {
                let key = f.deletingPathExtension().lastPathComponent.uppercased()
                found.insert(key)
            }
        }
        DispatchQueue.main.async {
            self.importedKeys = found
        }
    }

    public func importFile(url: URL) -> Int {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return 0 }

        var count = 0
        if url.pathExtension.lowercased() == "zip" {
            let entries = SimpleZipExtractor.extractCubeFiles(from: data)
            for e in entries {
                if storeCube(fileName: e.name, data: e.data) { count += 1 }
            }
        } else if url.pathExtension.lowercased() == "cube" {
            if storeCube(fileName: url.lastPathComponent, data: data) { count += 1 }
        }
        lock.lock()
        cache.removeAll()
        lock.unlock()
        refreshIndex()
        return count
    }

    private func storeCube(fileName: String, data: Data) -> Bool {
        guard CubeLUT.parse(data: data, isOfficial: true) != nil else { return false }
        let normalized = fileName.uppercased().replacingOccurrences(of: " ", with: "")
        var mappedKey: String?
        for item in Self.matchPatterns {
            if item.tokens.contains(where: { normalized.contains($0) }) {
                mappedKey = item.key
                break
            }
        }
        guard let key = mappedKey else { return false }
        let dst = folder.appendingPathComponent("\(key).cube")
        do {
            try data.write(to: dst, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    public func lut(for simulationKey: String) -> CubeLUT {
        lock.lock()
        if let hit = cache[simulationKey] {
            lock.unlock()
            return hit
        }
        lock.unlock()

        let fileURL = folder.appendingPathComponent("\(simulationKey).cube")
        if let data = try? Data(contentsOf: fileURL),
           let parsed = CubeLUT.parse(data: data, isOfficial: true) {
            lock.lock()
            cache[simulationKey] = parsed
            lock.unlock()
            return parsed
        }

        let built = Self.buildAnalyticalLUT(for: simulationKey)
        lock.lock()
        cache[simulationKey] = built
        lock.unlock()
        return built
    }

    /// Generates a smooth 17^3 F-Log2 (or S-Log3 for SONY_S709) simulation LUT matched to each
    /// Fujifilm film stock's tonal shoulder, shadow contrast, and colour matrix so recipes render
    /// faithfully even before importing external .cube packs.
    private static func buildAnalyticalLUT(for key: String) -> CubeLUT {
        let size = 17
        var table = [Float](repeating: 0, count: size * size * size * 3)
        let inv = 1.0 / Float(size - 1)

        for bi in 0..<size {
            let bLog = Float(bi) * inv
            for gi in 0..<size {
                let gLog = Float(gi) * inv
                for ri in 0..<size {
                    let rLog = Float(ri) * inv
                    let (ro, go, bo) = analyticalTransform(key: key, rLog: rLog, gLog: gLog, bLog: bLog)
                    let idx = (bi * size * size + gi * size + ri) * 3
                    table[idx] = ro
                    table[idx + 1] = go
                    table[idx + 2] = bo
                }
            }
        }
        return CubeLUT(size: size, table: table, isOfficial: false)
    }

    private static func analyticalTransform(key: String, rLog: Float, gLog: Float, bLog: Float) -> (Float, Float, Float) {
        if key == "SONY_S709" {
            let rl = ColorMathEngine.sLog3Decode(rLog)
            let gl = ColorMathEngine.sLog3Decode(gLog)
            let bl = ColorMathEngine.sLog3Decode(bLog)
            // S-Gamut3.Cine -> Rec.709 matrix + smooth cinema shoulder
            let r7 = 1.6175 * rl - 0.5373 * gl - 0.0802 * bl
            let g7 = -0.0706 * rl + 1.3346 * gl - 0.2640 * bl
            let b7 = -0.0211 * rl - 0.2270 * gl + 1.2481 * bl
            return (
                ColorMathEngine.srgbEncode(cinemaRollOff(r7, contrast: 1.08)),
                ColorMathEngine.srgbEncode(cinemaRollOff(g7, contrast: 1.08)),
                ColorMathEngine.srgbEncode(cinemaRollOff(b7, contrast: 1.08))
            )
        }

        // Decode F-Log2 + F-Gamut (Rec.2020) -> Linear sRGB
        let rl20 = ColorMathEngine.fLog2Decode(rLog)
        let gl20 = ColorMathEngine.fLog2Decode(gLog)
        let bl20 = ColorMathEngine.fLog2Decode(bLog)
        var r = 1.6605 * rl20 - 0.5876 * gl20 - 0.0728 * bl20
        var g = -0.1246 * rl20 + 1.1329 * gl20 - 0.0083 * bl20
        var b = -0.0182 * rl20 - 0.1006 * gl20 + 1.1187 * bl20
        r = max(0, r); g = max(0, g); b = max(0, b)

        if key == "ACROS" {
            let y = 0.23 * r + 0.69 * g + 0.08 * b
            let s = cinemaRollOff(y, contrast: 1.18)
            let v = ColorMathEngine.srgbEncode(s)
            return (v, v, v)
        }

        var contrast: Float = 1.12
        var sat: Float = 1.0
        var warmShadow: Float = 0.0
        var cyanSky: Float = 0.0

        switch key {
        case "CLASSIC_CHROME":
            contrast = 1.16; sat = 0.86; cyanSky = 0.10; warmShadow = -0.015
        case "CLASSIC_NEG":
            contrast = 1.24; sat = 1.05; cyanSky = 0.08; warmShadow = 0.025
        case "ETERNA":
            contrast = 0.94; sat = 0.82; cyanSky = 0.06
        case "ETERNA_BLEACH_BYPASS":
            contrast = 1.32; sat = 0.52
        case "VELVIA":
            contrast = 1.24; sat = 1.28
        case "ASTIA":
            contrast = 1.05; sat = 1.06
        case "NOSTALGIC_NEG":
            contrast = 1.08; sat = 1.08; warmShadow = 0.035
        case "PRO_NEG_STD":
            contrast = 0.98; sat = 0.92
        case "PRO_NEG_HI":
            contrast = 1.15; sat = 0.96
        case "REALA_ACE":
            contrast = 1.13; sat = 0.98; cyanSky = 0.03
        default: // PROVIA
            contrast = 1.12; sat = 1.02
        }

        var rs = cinemaRollOff(r, contrast: contrast)
        var gs = cinemaRollOff(g, contrast: contrast)
        var bs = cinemaRollOff(b, contrast: contrast)

        // Subtle Classic Chrome sky/foliage hue signature
        if cyanSky > 0 && bs > rs && bs > 0.04 {
            let skyW = min(1.0, (bs - rs) * 2.2) * cyanSky
            gs = max(0, gs + skyW * 0.35)
            bs = max(0, bs - skyW * 0.18)
        }
        if warmShadow != 0 {
            let y = 0.2126 * rs + 0.7152 * gs + 0.0722 * bs
            let sw = max(0, 1.0 - y * 2.5)
            rs = max(0, rs + warmShadow * sw)
            bs = max(0, bs - warmShadow * sw * 0.8)
        }

        let luma = 0.2126 * rs + 0.7152 * gs + 0.0722 * bs
        rs = max(0, luma + (rs - luma) * sat)
        gs = max(0, luma + (gs - luma) * sat)
        bs = max(0, luma + (bs - luma) * sat)

        return (
            ColorMathEngine.srgbEncode(rs),
            ColorMathEngine.srgbEncode(gs),
            ColorMathEngine.srgbEncode(bs)
        )
    }

    private static func cinemaRollOff(_ x: Float, contrast: Float) -> Float {
        let v = max(0, x)
        let pivot: Float = 0.18
        let curved = pivot * powf(v / pivot, contrast)
        return curved / (1.0 + curved * 0.82) * 1.12
    }
}
