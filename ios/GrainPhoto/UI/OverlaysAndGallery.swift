import SwiftUI
import UniformTypeIdentifiers
import ImageIO
import UIKit

// MARK: - 16-Tile Q Menu Overlay

public struct QMenuOverlayView: View {
    @ObservedObject var camera: GrainCaptureController
    @Binding var selectedTile: Int
    let onClose: () -> Void

    public init(camera: GrainCaptureController, selectedTile: Binding<Int>, onClose: @escaping () -> Void) {
        self.camera = camera
        self._selectedTile = selectedTile
        self.onClose = onClose
    }

    public var body: some View {
        let tuning = camera.currentTuning
        let tiles: [(title: String, value: String)] = [
            ("FILM", camera.currentFilm.code),
            ("WB", tuning.wbMode.rawValue),
            ("WB RED", signedInt(tuning.wbShiftRed)),
            ("WB BLUE", signedInt(tuning.wbShiftBlue)),
            ("D-RANGE", tuning.dynamicRange == 0 ? "AUTO" : "DR\(tuning.dynamicRange)"),
            ("HIGHLIGHT", signedFloat(tuning.highlightTone)),
            ("SHADOW", signedFloat(tuning.shadowTone)),
            ("COLOR", signedFloat(tuning.color)),
            ("SHARP", signedInt(tuning.sharpness)),
            ("NR", signedInt(tuning.noiseReduction)),
            ("CLARITY", signedInt(tuning.clarity)),
            ("GRAIN", tuning.grainRoughness == 0 ? "OFF" : (tuning.grainRoughness == 2 ? "STRONG" : "WEAK")),
            ("CHROME", tuning.colorChromeEffect == 0 ? "OFF" : (tuning.colorChromeEffect == 2 ? "STRONG" : "WEAK")),
            ("FX BLUE", tuning.colorChromeBlue == 0 ? "OFF" : (tuning.colorChromeBlue == 2 ? "STRONG" : "WEAK")),
            ("HALATION", "\(tuning.halation)"),
            ("RATIO", camera.aspectRatio)
        ]

        VStack(spacing: 8) {
            HStack {
                Text("QUICK MENU · \(camera.currentFilm.name.uppercased())")
                    .font(.custom("AvenirNextCondensed-Bold", size: 12))
                    .tracking(0.8)
                    .foregroundColor(GrainPalette.warmIvory)
                Spacer()
                Button("DONE") { onClose() }
                    .font(.custom("AvenirNextCondensed-Bold", size: 12))
                    .foregroundColor(GrainPalette.orange)
            }
            .padding(.horizontal, 10)
            .padding(.top, 8)

            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 4), spacing: 6) {
                ForEach(0..<tiles.count, id: \.self) { idx in
                    let active = idx == selectedTile
                    Button {
                        if selectedTile == idx {
                            stepQTile(idx, delta: 1, camera: camera)
                        } else {
                            selectedTile = idx
                        }
                        UISelectionFeedbackGenerator().selectionChanged()
                    } label: {
                        VStack(spacing: 3) {
                            Text(tiles[idx].title)
                                .font(.custom("AvenirNextCondensed-DemiBold", size: 9.5))
                                .foregroundColor(active ? .black.opacity(0.75) : GrainPalette.mutedEngrave)
                            Text(tiles[idx].value)
                                .font(.custom("AvenirNextCondensed-Bold", size: 13))
                                .lineLimit(1)
                                .minimumScaleFactor(0.7)
                                .foregroundColor(active ? .black : .white)
                        }
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(
                            RoundedRectangle(cornerRadius: 5, style: .continuous)
                                .fill(active ? GrainPalette.orange : Color.white.opacity(0.08))
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 10)

            Text("TAP TILE OR TURN COMMAND DIAL · FOCUS LEVER MOVES")
                .font(.custom("AvenirNextCondensed-DemiBold", size: 9.5))
                .tracking(0.6)
                .foregroundColor(GrainPalette.mutedEngrave)
                .padding(.bottom, 8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.88))
    }

    private func signedInt(_ v: Int) -> String { v > 0 ? "+\(v)" : "\(v)" }
    private func signedFloat(_ v: Float) -> String {
        if abs(v - v.rounded()) < 0.05 {
            let iv = Int(v.rounded())
            return iv > 0 ? "+\(iv)" : "\(iv)"
        }
        return String(format: "%+.1f", v)
    }
}

@MainActor
public func stepQTile(_ index: Int, delta: Int, camera: GrainCaptureController) {
    let d = delta >= 0 ? 1 : -1
    switch index {
    case 0:
        let n = FilmCatalog.all.count
        camera.filmIndex = (camera.filmIndex + d + n) % n
        camera.savePreferences()
    case 1:
        let all = WhiteBalanceMode.allCases
        camera.updateTuning { t in
            let cur = all.firstIndex(of: t.wbMode) ?? 0
            t.wbMode = all[(cur + d + all.count) % all.count]
        }
    case 2:
        camera.updateTuning { $0.wbShiftRed = max(-9, min(9, $0.wbShiftRed + d)) }
    case 3:
        camera.updateTuning { $0.wbShiftBlue = max(-9, min(9, $0.wbShiftBlue + d)) }
    case 4:
        let drs = [0, 100, 200, 400]
        camera.updateTuning { t in
            let cur = drs.firstIndex(of: t.dynamicRange) ?? 0
            t.dynamicRange = drs[(cur + d + drs.count) % drs.count]
        }
    case 5:
        camera.updateTuning { $0.highlightTone = max(-2, min(4, ($0.highlightTone + Float(d) * 0.5))) }
    case 6:
        camera.updateTuning { $0.shadowTone = max(-2, min(4, ($0.shadowTone + Float(d) * 0.5))) }
    case 7:
        camera.updateTuning { $0.color = max(-4, min(4, $0.color + Float(d))) }
    case 8:
        camera.updateTuning { $0.sharpness = max(-4, min(4, $0.sharpness + d)) }
    case 9:
        camera.updateTuning { $0.noiseReduction = max(-4, min(4, $0.noiseReduction + d)) }
    case 10:
        camera.updateTuning { $0.clarity = max(-5, min(5, $0.clarity + d)) }
    case 11:
        camera.updateTuning { $0.grainRoughness = ($0.grainRoughness + d + 3) % 3 }
    case 12:
        camera.updateTuning { $0.colorChromeEffect = ($0.colorChromeEffect + d + 3) % 3 }
    case 13:
        camera.updateTuning { $0.colorChromeBlue = ($0.colorChromeBlue + d + 3) % 3 }
    case 14:
        camera.updateTuning { $0.halation = max(0, min(5, $0.halation + d)) }
    case 15:
        let ratios = ["3:4", "1:1", "9:16"]
        let cur = ratios.firstIndex(of: camera.aspectRatio) ?? 0
        camera.aspectRatio = ratios[(cur + d + ratios.count) % ratios.count]
        camera.savePreferences()
    default:
        break
    }
}

// MARK: - 4-Tab Camera Menu (I.Q. / AF-FACE / SHOOTING / SET UP)

public struct CameraMenuOverlayView: View {
    @ObservedObject var camera: GrainCaptureController
    @ObservedObject var lutVault = LutVault.shared
    @Binding var selectedTab: Int
    @Binding var selectedRow: Int
    let onImportLutTap: () -> Void
    let onOpenFilmLab: () -> Void
    let onOpenOnboarding: () -> Void
    let onClose: () -> Void

    private let tabs = ["I.Q.", "AF / FACE", "SHOOTING", "SET UP"]

    public init(
        camera: GrainCaptureController,
        selectedTab: Binding<Int>,
        selectedRow: Binding<Int>,
        onImportLutTap: @escaping () -> Void,
        onOpenFilmLab: @escaping () -> Void,
        onOpenOnboarding: @escaping () -> Void,
        onClose: @escaping () -> Void
    ) {
        self.camera = camera
        self._selectedTab = selectedTab
        self._selectedRow = selectedRow
        self.onImportLutTap = onImportLutTap
        self.onOpenFilmLab = onOpenFilmLab
        self.onOpenOnboarding = onOpenOnboarding
        self.onClose = onClose
    }

    public var body: some View {
        let rows = menuRows(for: selectedTab)
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                ForEach(0..<tabs.count, id: \.self) { t in
                    Button {
                        selectedTab = t
                        selectedRow = 0
                        UISelectionFeedbackGenerator().selectionChanged()
                    } label: {
                        Text(tabs[t])
                            .font(.custom("AvenirNextCondensed-Bold", size: 12))
                            .tracking(0.6)
                            .foregroundColor(selectedTab == t ? .black : GrainPalette.warmIvory)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(
                                Capsule().fill(selectedTab == t ? GrainPalette.orange : Color.white.opacity(0.08))
                            )
                    }
                    .buttonStyle(.plain)
                }
                Spacer()
                Button("BACK") { onClose() }
                    .font(.custom("AvenirNextCondensed-Bold", size: 12))
                    .foregroundColor(GrainPalette.mutedEngrave)
            }
            .padding(10)

            Divider().background(Color.white.opacity(0.14))

            ScrollView {
                VStack(spacing: 4) {
                    ForEach(0..<rows.count, id: \.self) { idx in
                        let active = idx == selectedRow
                        Button {
                            selectedRow = idx
                            rows[idx].action()
                            UISelectionFeedbackGenerator().selectionChanged()
                        } label: {
                            HStack {
                                Text(rows[idx].title)
                                    .font(.custom("AvenirNextCondensed-DemiBold", size: 13.5))
                                    .foregroundColor(active ? .black : .white)
                                Spacer()
                                Text(rows[idx].value)
                                    .font(.custom("AvenirNextCondensed-Bold", size: 13))
                                    .foregroundColor(active ? .black.opacity(0.85) : GrainPalette.orange)
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 9)
                            .background(
                                RoundedRectangle(cornerRadius: 5, style: .continuous)
                                    .fill(active ? GrainPalette.orange : Color.white.opacity(0.05))
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(10)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.92))
    }

    public struct MenuRow {
        let title: String
        let value: String
        let action: () -> Void
    }

    public func menuRows(for tab: Int) -> [MenuRow] {
        switch tab {
        case 0:
            return [
                MenuRow(title: "FILM SIMULATION", value: camera.currentFilm.name) {
                    camera.filmIndex = (camera.filmIndex + 1) % FilmCatalog.all.count
                    camera.savePreferences()
                },
                MenuRow(title: "TUNE ACTIVE FILM", value: "OPEN LAB ›") {
                    onOpenFilmLab()
                },
                MenuRow(title: "IMPORT LUT (.ZIP / .CUBE)", value: "\(lutVault.importedKeys.count) LOADED") {
                    onImportLutTap()
                },
                MenuRow(title: "ASPECT RATIO", value: camera.aspectRatio) {
                    let all = ["3:4", "1:1", "9:16"]
                    let cur = all.firstIndex(of: camera.aspectRatio) ?? 0
                    camera.aspectRatio = all[(cur + 1) % all.count]
                    camera.savePreferences()
                },
                MenuRow(title: "RAW ARCHIVAL", value: camera.keepRawDng ? "FINE + RAW" : "FINE JPEG") {
                    camera.keepRawDng.toggle()
                    camera.savePreferences()
                },
                MenuRow(title: "RESET FILM TUNING", value: camera.currentFilm.code) {
                    camera.resetCurrentTuning()
                }
            ]
        case 1:
            return [
                MenuRow(title: "FOCUS MODE", value: camera.focusMode.label) {
                    let all = FocusModeSetting.allCases
                    let cur = all.firstIndex(of: camera.focusMode) ?? 0
                    camera.focusMode = all[(cur + 1) % all.count]
                    camera.applyExposureAndFocusState()
                    camera.savePreferences()
                },
                MenuRow(title: "AF AREA", value: camera.afArea.label) {
                    let all = AfAreaSetting.allCases
                    let cur = all.firstIndex(of: camera.afArea) ?? 0
                    camera.afArea = all[(cur + 1) % all.count]
                    camera.applyExposureAndFocusState()
                    camera.savePreferences()
                },
                MenuRow(title: "MAGNIFIER LOUPE", value: camera.magnifierSetting.label) {
                    let all = MagnifierSetting.allCases
                    let cur = all.firstIndex(of: camera.magnifierSetting) ?? 0
                    camera.magnifierSetting = all[(cur + 1) % all.count]
                    camera.savePreferences()
                },
                MenuRow(title: "AF LOCK", value: camera.afLocked ? "ON" : "OFF") {
                    camera.afLocked.toggle()
                    camera.applyExposureAndFocusState()
                }
            ]
        case 2:
            return [
                MenuRow(title: "DRIVE / BRACKETING", value: camera.driveSetting.label) {
                    let all = DriveSetting.allCases
                    let cur = all.firstIndex(of: camera.driveSetting) ?? 0
                    camera.driveSetting = all[(cur + 1) % all.count]
                },
                MenuRow(title: "FLASH MODE", value: camera.flashSetting.label) {
                    let all = FlashSetting.allCases
                    let cur = all.firstIndex(of: camera.flashSetting) ?? 0
                    camera.flashSetting = all[(cur + 1) % all.count]
                    camera.applyExposureAndFocusState()
                },
                MenuRow(title: "FRAMING GRID", value: camera.showGrid ? "ON" : "OFF") {
                    camera.showGrid.toggle()
                    camera.savePreferences()
                },
                MenuRow(title: "LIVE HISTOGRAM", value: camera.showHistogram ? "ON" : "OFF") {
                    camera.showHistogram.toggle()
                    camera.savePreferences()
                },
                MenuRow(title: "ELECTRONIC LEVEL", value: camera.showLevel ? "ON" : "OFF") {
                    camera.showLevel.toggle()
                    camera.savePreferences()
                }
            ]
        default:
            let finishes = ["SILVER", "BLACK", "GRAPHITE"]
            return [
                MenuRow(title: "BODY FINISH", value: finishes[camera.bodyFinish % 3]) {
                    camera.bodyFinish = (camera.bodyFinish + 1) % 3
                    camera.savePreferences()
                },
                MenuRow(title: "SENSOR PIPELINE", value: camera.supportsHardwareRaw ? "BAYER RAW DNG" : "SCENE-LINEAR P3") {},
                MenuRow(title: "PURE BLACKOUT EVF", value: "ENTER ›") {
                    onClose()
                    camera.blackoutEvf = true
                },
                MenuRow(title: "INTRO GUIDE", value: "VIEW ›") {
                    onOpenOnboarding()
                }
            ]
        }
    }
}

// MARK: - Film Lab & Recipe Tuning Sheet

public struct FilmLabSheetView: View {
    @ObservedObject var camera: GrainCaptureController
    @Environment(\.dismiss) private var dismiss

    public init(camera: GrainCaptureController) {
        self.camera = camera
    }

    public var body: some View {
        let film = camera.currentFilm
        let t = camera.currentTuning

        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(film.code)
                                .font(.custom("AvenirNextCondensed-Bold", size: 13))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(Capsule().fill(GrainPalette.hexColor(film.boxHex)))
                                .foregroundColor(.white)
                            Text(film.subtitle)
                                .font(.custom("AvenirNextCondensed-DemiBold", size: 13))
                                .foregroundColor(GrainPalette.mutedEngrave)
                        }
                        Text(film.name)
                            .font(.custom("Georgia-Bold", size: 24))
                            .foregroundColor(.white)
                        Text(film.exposureNote)
                            .font(.system(size: 13))
                            .foregroundColor(GrainPalette.warmIvory.opacity(0.82))
                    }
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.06)))

                    VStack(spacing: 14) {
                        tuningSlider("Highlight Tone", value: t.highlightTone, range: -2...4, step: 0.5) { v in
                            camera.updateTuning { $0.highlightTone = v }
                        }
                        tuningSlider("Shadow Tone", value: t.shadowTone, range: -2...4, step: 0.5) { v in
                            camera.updateTuning { $0.shadowTone = v }
                        }
                        tuningSlider("Color", value: t.color, range: -4...4, step: 1) { v in
                            camera.updateTuning { $0.color = v }
                        }
                        tuningSlider("Clarity", value: Float(t.clarity), range: -5...5, step: 1) { v in
                            camera.updateTuning { $0.clarity = Int(v.rounded()) }
                        }
                        tuningSlider("Halation (Red-Orange Scatter)", value: Float(t.halation), range: 0...5, step: 1) { v in
                            camera.updateTuning { $0.halation = Int(v.rounded()) }
                        }
                        tuningSlider("Bloom", value: Float(t.bloom), range: 0...5, step: 1) { v in
                            camera.updateTuning { $0.bloom = Int(v.rounded()) }
                        }
                        tuningSlider("WB Shift Red", value: Float(t.wbShiftRed), range: -9...9, step: 1) { v in
                            camera.updateTuning { $0.wbShiftRed = Int(v.rounded()) }
                        }
                        tuningSlider("WB Shift Blue", value: Float(t.wbShiftBlue), range: -9...9, step: 1) { v in
                            camera.updateTuning { $0.wbShiftBlue = Int(v.rounded()) }
                        }
                    }
                    .padding()
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.05)))

                    Button {
                        camera.resetCurrentTuning()
                    } label: {
                        Text("Reset to Published Recipe")
                            .font(.custom("AvenirNextCondensed-Bold", size: 15))
                            .foregroundColor(GrainPalette.orange)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(RoundedRectangle(cornerRadius: 10).stroke(GrainPalette.orange.opacity(0.6), lineWidth: 1))
                    }
                }
                .padding()
            }
            .background(GrainPalette.bodyDark.ignoresSafeArea())
            .navigationTitle("Film Lab")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .foregroundColor(GrainPalette.orange)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private func tuningSlider(_ label: String, value: Float, range: ClosedRange<Float>, step: Float, onChange: @escaping (Float) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(label.uppercased())
                    .font(.custom("AvenirNextCondensed-DemiBold", size: 12))
                    .foregroundColor(GrainPalette.mutedEngrave)
                Spacer()
                Text(String(format: step < 1 ? "%+.1f" : "%+.0f", value))
                    .font(.custom("AvenirNextCondensed-Bold", size: 13))
                    .foregroundColor(.white)
            }
            Slider(
                value: Binding(get: { value }, set: { onChange($0) }),
                in: range,
                step: step
            )
            .tint(GrainPalette.orange)
        }
    }
}

// MARK: - Darkroom Gallery with Multi-Select Batch Delete (with 7s Undo) & Batch Save

public struct DarkroomGalleryView: View {
    @ObservedObject var store = PhotoStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var multiSelectMode = false
    @State private var selectedIds: Set<String> = []
    @State private var focusedEntry: RollEntry?
    @State private var showReceipt = false
    @State private var statusBanner: String?

    public init() {}

    public var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                GrainPalette.bodyDark.ignoresSafeArea()

                if store.entries.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "film")
                            .font(.system(size: 36, weight: .light))
                            .foregroundColor(GrainPalette.mutedEngrave)
                        Text("NO EXPOSURES ON ROLL")
                            .font(.custom("AvenirNextCondensed-Bold", size: 16))
                            .tracking(1.0)
                            .foregroundColor(GrainPalette.warmIvory)
                        Text("Press the orange shutter button to expose your first frame.")
                            .font(.system(size: 13))
                            .foregroundColor(GrainPalette.mutedEngrave)
                    }
                } else if let entry = focusedEntry, !multiSelectMode {
                    singleFrameDetail(entry)
                } else {
                    ScrollView {
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 3), spacing: 4) {
                            ForEach(store.entries) { entry in
                                let isSelected = selectedIds.contains(entry.id)
                                Button {
                                    if multiSelectMode {
                                        if isSelected { selectedIds.remove(entry.id) }
                                        else { selectedIds.insert(entry.id) }
                                        UISelectionFeedbackGenerator().selectionChanged()
                                    } else {
                                        focusedEntry = entry
                                    }
                                } label: {
                                    ZStack(alignment: .topTrailing) {
                                        ThumbnailImageView(url: entry.jpegURL)
                                            .aspectRatio(1, contentMode: .fill)
                                            .clipped()
                                            .overlay(
                                                Rectangle()
                                                    .stroke(isSelected ? GrainPalette.orange : Color.clear, lineWidth: 3)
                                            )

                                        HStack(spacing: 4) {
                                            Text(entry.filmCode)
                                                .font(.custom("AvenirNextCondensed-Bold", size: 9.5))
                                                .padding(.horizontal, 5)
                                                .padding(.vertical, 2)
                                                .background(Color.black.opacity(0.68))
                                                .foregroundColor(.white)
                                                .cornerRadius(3)
                                            Spacer()
                                            if multiSelectMode {
                                                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                                                    .font(.system(size: 18, weight: .semibold))
                                                    .foregroundColor(isSelected ? GrainPalette.orange : .white)
                                                    .shadow(radius: 2)
                                            }
                                        }
                                        .padding(6)
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(4)
                        .padding(.bottom, 84)
                    }
                }

                VStack(spacing: 8) {
                    if let banner = statusBanner {
                        Text(banner)
                            .font(.custom("AvenirNextCondensed-Bold", size: 13))
                            .foregroundColor(.white)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(Capsule().fill(Color.black.opacity(0.85)))
                    }

                    if store.pendingUndoCount > 0 {
                        HStack(spacing: 14) {
                            Text("Deleted \(store.pendingUndoCount) frame\(store.pendingUndoCount == 1 ? "" : "s")")
                                .font(.system(size: 13, weight: .medium))
                                .foregroundColor(.white)
                            Button("UNDO") {
                                store.undoDelete()
                            }
                            .font(.custom("AvenirNextCondensed-Bold", size: 14))
                            .foregroundColor(GrainPalette.orange)
                        }
                        .padding(.horizontal, 18)
                        .padding(.vertical, 10)
                        .background(Capsule().fill(Color(white: 0.16)))
                    }

                    if multiSelectMode {
                        HStack(spacing: 12) {
                            Button {
                                if selectedIds.count == store.entries.count {
                                    selectedIds.removeAll()
                                } else {
                                    selectedIds = Set(store.entries.map(\.id))
                                }
                            } label: {
                                Text(selectedIds.count == store.entries.count ? "Deselect All" : "Select All")
                                    .font(.custom("AvenirNextCondensed-Bold", size: 14))
                                    .foregroundColor(GrainPalette.warmIvory)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 10)
                                    .background(Capsule().fill(Color.white.opacity(0.12)))
                            }

                            Spacer()

                            Button {
                                let ids = selectedIds
                                store.exportToPhotoLibrary(ids: ids) { saved, err in
                                    statusBanner = err ?? "Saved \(saved) photo\(saved == 1 ? "" : "s") to Photos"
                                    selectedIds.removeAll()
                                    multiSelectMode = false
                                }
                            } label: {
                                Text("Save (\(selectedIds.count))")
                                    .font(.custom("AvenirNextCondensed-Bold", size: 14))
                                    .foregroundColor(.black)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 10)
                                    .background(Capsule().fill(selectedIds.isEmpty ? Color.gray : GrainPalette.warmIvory))
                            }
                            .disabled(selectedIds.isEmpty)

                            Button {
                                let ids = selectedIds
                                selectedIds.removeAll()
                                multiSelectMode = false
                                store.deleteBatch(ids: ids)
                            } label: {
                                Text("Delete (\(selectedIds.count))")
                                    .font(.custom("AvenirNextCondensed-Bold", size: 14))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 10)
                                    .background(Capsule().fill(selectedIds.isEmpty ? Color.gray.opacity(0.4) : Color.red.opacity(0.85)))
                            }
                            .disabled(selectedIds.isEmpty)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .background(Color.black.opacity(0.92))
                    }
                }
            }
            .navigationTitle(focusedEntry == nil ? "Darkroom Roll (\(store.entries.count))" : focusedEntry!.filmName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if focusedEntry != nil && !multiSelectMode {
                        Button("Roll") {
                            focusedEntry = nil
                            showReceipt = false
                        }
                        .foregroundColor(GrainPalette.warmIvory)
                    } else if !store.entries.isEmpty {
                        Button(multiSelectMode ? "Cancel" : "Select") {
                            multiSelectMode.toggle()
                            selectedIds.removeAll()
                        }
                        .foregroundColor(GrainPalette.orange)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Camera") { dismiss() }
                        .foregroundColor(GrainPalette.orange)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private func singleFrameDetail(_ entry: RollEntry) -> some View {
        VStack(spacing: 12) {
            ZStack {
                if let data = try? Data(contentsOf: entry.jpegURL), let ui = UIImage(data: data) {
                    Image(uiImage: ui)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                if showReceipt {
                    ScrollView {
                        Text(store.receiptText(for: entry))
                            .font(.system(size: 12))
                            .foregroundColor(GrainPalette.warmIvory)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(14)
                    }
                    .background(Color.black.opacity(0.86))
                }
            }

            HStack(spacing: 16) {
                Button {
                    showReceipt.toggle()
                } label: {
                    Label(showReceipt ? "Hide Receipt" : "Receipt", systemImage: "doc.text")
                        .font(.custom("AvenirNextCondensed-Bold", size: 14))
                }
                .foregroundColor(GrainPalette.warmIvory)

                Spacer()

                ShareLink(item: entry.jpegURL) {
                    Label("Share", systemImage: "square.and.arrow.up")
                        .font(.custom("AvenirNextCondensed-Bold", size: 14))
                }
                .foregroundColor(GrainPalette.warmIvory)

                Button {
                    store.exportToPhotoLibrary(ids: [entry.id]) { saved, err in
                        statusBanner = err ?? (saved > 0 ? "Saved to Photos" : "Could not save")
                    }
                } label: {
                    Label("Save", systemImage: "arrow.down.to.line")
                        .font(.custom("AvenirNextCondensed-Bold", size: 14))
                }
                .foregroundColor(GrainPalette.orange)

                Button {
                    let id = entry.id
                    focusedEntry = nil
                    store.deleteBatch(ids: [id])
                } label: {
                    Label("Delete", systemImage: "trash")
                        .font(.custom("AvenirNextCondensed-Bold", size: 14))
                }
                .foregroundColor(.red)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 16)
        }
    }
}

private struct ThumbnailImageView: View {
    let url: URL
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color(white: 0.12)
            if let img = image {
                Image(uiImage: img)
                    .resizable()
                    .scaledToFill()
            }
        }
        .onAppear {
            guard image == nil else { return }
            DispatchQueue.global(qos: .userInitiated).async {
                if let src = CGImageSourceCreateWithURL(url as CFURL, nil) {
                    let opts: [CFString: Any] = [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceThumbnailMaxPixelSize: 420,
                        kCGImageSourceCreateThumbnailWithTransform: true
                    ]
                    if let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) {
                        let thumb = UIImage(cgImage: cg)
                        DispatchQueue.main.async { self.image = thumb }
                    }
                }
            }
        }
    }
}

// MARK: - Minimalist 4-Step Hardware Onboarding (Clean, Literal Visuals, Zero Clutter)

public struct MinimalistOnboardingView: View {
    let onFinish: () -> Void
    @State private var step = 0

    public init(onFinish: @escaping () -> Void) {
        self.onFinish = onFinish
    }

    private struct Page {
        let symbol: String
        let kicker: String
        let title: String
        let line: String
    }

    private let pages: [Page] = [
        Page(
            symbol: "camera.aperture",
            kicker: "01 · RANGEFINDER",
            title: "Tactile Film Camera",
            line: "Physical shutter, ISO, EV, and film dials with a smooth 360° analog focus stick."
        ),
        Page(
            symbol: "film.stack",
            kicker: "02 · FILM LAB",
            title: "11 Calibrated Recipes",
            line: "Reggie's Portra, Kodak 800 v3, Leica X, and CineStill 800T developed in linear F-Log2."
        ),
        Page(
            symbol: "cube.transparent",
            kicker: "03 · OFFICIAL LUTS",
            title: "Ready Now · Import .Cube Anytime",
            line: "Shoot immediately out of the box, or load official Fujifilm & Sony .zip LUTs in MENU → I.Q."
        ),
        Page(
            symbol: "lock.shield",
            kicker: "04 · PRIVATE ROLL",
            title: "On-Device Darkroom",
            line: "Zero cloud or tracking. Frames stay private until you export them to Photos."
        )
    ]

    public var body: some View {
        let p = pages[step]
        VStack(spacing: 28) {
            HStack {
                Text(p.kicker)
                    .font(.custom("AvenirNextCondensed-Bold", size: 12))
                    .tracking(1.4)
                    .foregroundColor(GrainPalette.mutedEngrave)
                Spacer()
                Button("Skip") { onFinish() }
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(GrainPalette.mutedEngrave)
            }
            .padding(.horizontal, 28)
            .padding(.top, 24)

            Spacer()

            ZStack {
                Circle()
                    .fill(Color.white.opacity(0.05))
                    .frame(width: 112, height: 112)
                    .overlay(Circle().stroke(Color.white.opacity(0.12), lineWidth: 1))
                Image(systemName: p.symbol)
                    .font(.system(size: 40, weight: .light))
                    .foregroundColor(GrainPalette.orange)
            }

            VStack(spacing: 10) {
                Text(p.title)
                    .font(.custom("Georgia-Bold", size: 26))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                Text(p.line)
                    .font(.system(size: 15))
                    .foregroundColor(GrainPalette.warmIvory.opacity(0.78))
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 300)
            }
            .padding(.horizontal, 24)

            Spacer()

            HStack {
                HStack(spacing: 7) {
                    ForEach(0..<pages.count, id: \.self) { i in
                        Capsule()
                            .fill(i == step ? GrainPalette.orange : Color.white.opacity(0.2))
                            .frame(width: i == step ? 20 : 6, height: 6)
                    }
                }
                Spacer()
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    if step < pages.count - 1 {
                        withAnimation(.easeInOut(duration: 0.2)) { step += 1 }
                    } else {
                        onFinish()
                    }
                } label: {
                    Text(step < pages.count - 1 ? "Next" : "Start Shooting")
                        .font(.custom("AvenirNextCondensed-Bold", size: 15))
                        .tracking(0.6)
                        .foregroundColor(.black)
                        .padding(.horizontal, 24)
                        .padding(.vertical, 12)
                        .background(Capsule().fill(GrainPalette.warmIvory))
                }
            }
            .padding(.horizontal, 28)
            .padding(.bottom, 32)
        }
        .background(Color(red: 0.065, green: 0.068, blue: 0.062).ignoresSafeArea())
    }
}

// MARK: - UIDocumentPicker Wrapper for .zip / .cube LUT Import

public struct LutDocumentPicker: UIViewControllerRepresentable {
    let onPick: (URL) -> Void

    public init(onPick: @escaping (URL) -> Void) {
        self.onPick = onPick
    }

    public func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let cubeType = UTType(filenameExtension: "cube") ?? .data
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.zip, cubeType, .data], asCopy: true)
        picker.delegate = context.coordinator
        return picker
    }

    public func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    public func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    public final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void
        init(onPick: @escaping (URL) -> Void) { self.onPick = onPick }
        public func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            if let first = urls.first {
                onPick(first)
            }
        }
    }
}
