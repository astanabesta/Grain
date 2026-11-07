import Foundation
import UIKit
import Photos

public struct RollEntry: Identifiable, Equatable {
    public let id: String
    public let jpegURL: URL
    public let dngURL: URL?
    public let receiptURL: URL
    public let createdAt: Date
    public let filmCode: String
    public let filmName: String
    public let isHiRes: Bool
    /// Nominal output size of the tripod super-resolution merge this frame came from.
    public let hiResMegapixels: Int

    /// Human-readable filename containing the film recipe + timestamp (e.g. `Grain_Kodak_Portra_800_v3_20261108_143512_412.jpg`).
    public func exportFilename(ext: String) -> String {
        if id.hasPrefix("Grain_") && !id.hasPrefix("GRAIN_20") {
            return "\(id).\(ext)"
        }
        let baseSlug = PhotoStore.recipeSlug(filmName)
        let slug = isHiRes ? (baseSlug.isEmpty ? "HiRes" : "\(baseSlug)_HiRes") : baseSlug
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        let stamp = formatter.string(from: createdAt)
        if slug.isEmpty {
            return "Grain_\(stamp).\(ext)"
        }
        return "Grain_\(slug)_\(stamp).\(ext)"
    }
}

@MainActor
public final class PhotoStore: ObservableObject {
    public static let shared = PhotoStore()

    @Published public private(set) var entries: [RollEntry] = []
    @Published public private(set) var pendingUndoCount: Int = 0

    private let rollFolder: URL
    private let trashFolder: URL
    private var undoItems: [RollEntry] = []
    private var undoWorkItem: DispatchWorkItem?

    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        rollFolder = docs.appendingPathComponent("Roll", isDirectory: true)
        trashFolder = docs.appendingPathComponent("RollTrash", isDirectory: true)
        try? FileManager.default.createDirectory(at: rollFolder, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: trashFolder, withIntermediateDirectories: true)
        purgeTrash()
        reload()
    }

    public func reload() {
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: rollFolder,
            includingPropertiesForKeys: [.creationDateKey],
            options: [.skipsHiddenFiles]
        ) else {
            entries = []
            return
        }

        let jpegs = files.filter { $0.pathExtension.lowercased() == "jpg" }
        var loaded: [RollEntry] = []
        for jpg in jpegs {
            let stem = jpg.deletingPathExtension().lastPathComponent
            let dng = rollFolder.appendingPathComponent("\(stem).dng")
            let hasDng = FileManager.default.fileExists(atPath: dng.path)
            let json = rollFolder.appendingPathComponent("\(stem).json")
            let created = (try? jpg.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? Date()

            var code = "GRAIN"
            var name = stem
            var hiRes = stem.contains("_HiRes_")
            var hiResMp = 36
            if let data = try? Data(contentsOf: json),
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                code = (obj["filmCode"] as? String) ?? code
                name = (obj["filmName"] as? String) ?? name
                if obj["hiResMode"] != nil {
                    hiRes = true
                }
                if let mp = obj["hiResMegapixels"] as? Int {
                    hiResMp = mp
                }
            }
            loaded.append(
                RollEntry(
                    id: stem,
                    jpegURL: jpg,
                    dngURL: hasDng ? dng : nil,
                    receiptURL: json,
                    createdAt: created,
                    filmCode: code,
                    filmName: name,
                    isHiRes: hiRes,
                    hiResMegapixels: hiResMp
                )
            )
        }
        loaded.sort { $0.createdAt > $1.createdAt }
        entries = loaded
    }

    /// Converts a recipe display name (e.g. "Reggie's Portra") into a clean filename slug ("Reggies_Portra").
    nonisolated public static func recipeSlug(_ recipeName: String) -> String {
        let cleaned = recipeName
            .replacingOccurrences(of: "'", with: "")
            .replacingOccurrences(of: "’", with: "")
            .replacingOccurrences(of: "`", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        var parts: [String] = []
        var current = ""
        for scalar in cleaned.unicodeScalars {
            if CharacterSet.alphanumerics.contains(scalar) && scalar.isASCII {
                current.unicodeScalars.append(scalar)
            } else if !current.isEmpty {
                parts.append(current)
                current = ""
            }
        }
        if !current.isEmpty {
            parts.append(current)
        }
        let joined = parts.joined(separator: "_")
        if joined.count > 40 {
            return String(joined.prefix(40)).trimmingCharacters(in: CharacterSet(charactersIn: "_"))
        }
        return joined
    }

    nonisolated public func saveCapture(
        jpegData: Data,
        rawDngData: Data?,
        keepRaw: Bool,
        receipt: [String: Any]
    ) {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd_HHmmss_SSS"
        let stamp = formatter.string(from: Date())
        let filmLabel = (receipt["filmName"] as? String) ?? (receipt["filmCode"] as? String) ?? ""
        let isHiRes = (receipt["hiResMode"] as? String) != nil
        let baseSlug = Self.recipeSlug(filmLabel)
        let slug = isHiRes ? (baseSlug.isEmpty ? "HiRes" : "\(baseSlug)_HiRes") : baseSlug
        let stem = slug.isEmpty ? "Grain_\(stamp)" : "Grain_\(slug)_\(stamp)"

        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let folder = docs.appendingPathComponent("Roll", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)

        let jpgURL = folder.appendingPathComponent("\(stem).jpg")
        try? jpegData.write(to: jpgURL, options: .atomic)

        if keepRaw, let raw = rawDngData {
            let dngURL = folder.appendingPathComponent("\(stem).dng")
            try? raw.write(to: dngURL, options: .atomic)
        }

        var fullReceipt = receipt
        fullReceipt["fileName"] = "\(stem).jpg"
        if keepRaw && rawDngData != nil {
            fullReceipt["rawFileName"] = "\(stem).dng"
        }

        let jsonURL = folder.appendingPathComponent("\(stem).json")
        if let jsonData = try? JSONSerialization.data(withJSONObject: fullReceipt, options: [.prettyPrinted, .sortedKeys]) {
            try? jsonData.write(to: jsonURL, options: .atomic)
        }

        Task { @MainActor in
            self.reload()
        }
    }

    public func receiptText(for entry: RollEntry) -> String {
        guard let data = try? Data(contentsOf: entry.receiptURL),
              let text = String(data: data, encoding: .utf8) else {
            return Self.receiptHeader(for: entry, megapixels: nil) + "{ \"film\": \"\(entry.filmName)\" }"
        }
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        var megapixels: Int?
        if let obj = json, let mp = obj["hiResMegapixels"] as? Int {
            megapixels = mp
        }
        return Self.receiptHeader(for: entry, megapixels: megapixels) + text
    }

    private static func receiptHeader(for entry: RollEntry, megapixels: Int?) -> String {
        let modeLine = entry.isHiRes ? "\nMODE   HI-RES \(megapixels ?? 36)MP (4 FRAMES MERGED)" : ""
        return "FILE   \(entry.exportFilename(ext: "jpg"))\(entry.dngURL != nil ? "\nRAW    \(entry.exportFilename(ext: "dng"))" : "")\nFILM   \(entry.filmName) (\(entry.filmCode))\(modeLine)\n\n"
    }

    public func shareURL(for entry: RollEntry) -> URL {
        let wanted = entry.exportFilename(ext: "jpg")
        if entry.jpegURL.lastPathComponent == wanted {
            return entry.jpegURL
        }
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(wanted)
        try? FileManager.default.removeItem(at: tmp)
        if (try? FileManager.default.copyItem(at: entry.jpegURL, to: tmp)) != nil {
            return tmp
        }
        return entry.jpegURL
    }

    /// Multi-select batch delete with 7-second Undo window.
    public func deleteBatch(ids: Set<String>) {
        guard !ids.isEmpty else { return }
        commitPendingTrash()

        let targets = entries.filter { ids.contains($0.id) }
        guard !targets.isEmpty else { return }

        var staged: [RollEntry] = []
        for item in targets {
            let dstJpg = trashFolder.appendingPathComponent(item.jpegURL.lastPathComponent)
            try? FileManager.default.removeItem(at: dstJpg)
            if (try? FileManager.default.moveItem(at: item.jpegURL, to: dstJpg)) != nil {
                var movedDng: URL?
                if let dng = item.dngURL {
                    let dstDng = trashFolder.appendingPathComponent(dng.lastPathComponent)
                    try? FileManager.default.removeItem(at: dstDng)
                    try? FileManager.default.moveItem(at: dng, to: dstDng)
                    movedDng = dstDng
                }
                let dstJson = trashFolder.appendingPathComponent(item.receiptURL.lastPathComponent)
                try? FileManager.default.removeItem(at: dstJson)
                try? FileManager.default.moveItem(at: item.receiptURL, to: dstJson)
                staged.append(
                    RollEntry(
                        id: item.id,
                        jpegURL: dstJpg,
                        dngURL: movedDng,
                        receiptURL: dstJson,
                        createdAt: item.createdAt,
                        filmCode: item.filmCode,
                        filmName: item.filmName,
                        isHiRes: item.isHiRes,
                        hiResMegapixels: item.hiResMegapixels
                    )
                )
            }
        }

        undoItems = staged
        pendingUndoCount = staged.count
        reload()

        let work = DispatchWorkItem { [weak self] in
            self?.commitPendingTrash()
        }
        undoWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 7.0, execute: work)
    }

    public func undoDelete() {
        undoWorkItem?.cancel()
        undoWorkItem = nil
        guard !undoItems.isEmpty else { return }

        for item in undoItems {
            let restoredJpg = rollFolder.appendingPathComponent(item.jpegURL.lastPathComponent)
            try? FileManager.default.moveItem(at: item.jpegURL, to: restoredJpg)
            if let dng = item.dngURL {
                let restoredDng = rollFolder.appendingPathComponent(dng.lastPathComponent)
                try? FileManager.default.moveItem(at: dng, to: restoredDng)
            }
            let restoredJson = rollFolder.appendingPathComponent(item.receiptURL.lastPathComponent)
            try? FileManager.default.moveItem(at: item.receiptURL, to: restoredJson)
        }
        undoItems.removeAll()
        pendingUndoCount = 0
        reload()
    }

    private func commitPendingTrash() {
        undoWorkItem?.cancel()
        undoWorkItem = nil
        undoItems.removeAll()
        pendingUndoCount = 0
        purgeTrash()
    }

    private func purgeTrash() {
        if let files = try? FileManager.default.contentsOfDirectory(at: trashFolder, includingPropertiesForKeys: nil) {
            for f in files {
                try? FileManager.default.removeItem(at: f)
            }
        }
    }

    public func exportToPhotoLibrary(ids: Set<String>, completion: @escaping (Int, String?) -> Void) {
        let targets = entries.filter { ids.contains($0.id) }
        guard !targets.isEmpty else {
            completion(0, nil)
            return
        }

        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                DispatchQueue.main.async {
                    completion(0, "Photo Library permission denied in Settings")
                }
                return
            }
            PHPhotoLibrary.shared().performChanges({
                for item in targets {
                    if let data = try? Data(contentsOf: item.jpegURL) {
                        let req = PHAssetCreationRequest.forAsset()
                        let jpgOpt = PHAssetResourceCreationOptions()
                        jpgOpt.originalFilename = item.exportFilename(ext: "jpg")
                        req.addResource(with: .photo, data: data, options: jpgOpt)
                        if let dng = item.dngURL, let rawData = try? Data(contentsOf: dng) {
                            let dngOpt = PHAssetResourceCreationOptions()
                            dngOpt.originalFilename = item.exportFilename(ext: "dng")
                            req.addResource(with: .alternatePhoto, data: rawData, options: dngOpt)
                        }
                    }
                }
            }, completionHandler: { ok, err in
                DispatchQueue.main.async {
                    completion(ok ? targets.count : 0, err?.localizedDescription)
                }
            })
        }
    }
}
