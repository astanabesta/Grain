import SwiftUI
import UIKit

@main
struct GrainPhotoApp: App {
    @StateObject private var camera = GrainCaptureController()
    @AppStorage("grain.onboardingDone") private var onboardingDone = false

    var body: some Scene {
        WindowGroup {
            Group {
                if !onboardingDone {
                    MinimalistOnboardingView {
                        onboardingDone = true
                        camera.start()
                    }
                } else {
                    RangefinderCameraScreen(
                        camera: camera,
                        onReplayOnboarding: { onboardingDone = false }
                    )
                }
            }
            .statusBarHidden(true)
            .preferredColorScheme(.dark)
        }
    }
}

enum ActiveScreenMode {
    case camera
    case qMenu
    case mainMenu
    case playback
}

struct RangefinderCameraScreen: View {
    @ObservedObject var camera: GrainCaptureController
    @ObservedObject var store = PhotoStore.shared
    let onReplayOnboarding: () -> Void

    @State private var screenMode: ActiveScreenMode = .camera
    @State private var qTileIndex: Int = 0
    @State private var menuTabIndex: Int = 0
    @State private var menuRowIndex: Int = 0
    @State private var playIndex: Int = 0
    @State private var playZoom: CGFloat = 1.0
    @State private var playPinchBase: CGFloat = 1.0
    @State private var playPan: CGSize = .zero
    @State private var playDragBase: CGSize = .zero
    @State private var playShowReceipt: Bool = false
    @State private var showFilmLab = false
    @State private var showGallery = false
    @State private var showLutPicker = false
    @State private var pinchBaseZoom: CGFloat = 1.0

    var body: some View {
        ZStack {
            if camera.blackoutEvf {
                pureBlackoutViewfinder
            } else {
                fullCameraBack
            }
        }
        .onAppear {
            camera.start()
        }
        .sheet(isPresented: $showFilmLab) {
            FilmLabSheetView(camera: camera)
        }
        .fullScreenCover(isPresented: $showGallery) {
            DarkroomGalleryView()
        }
        .sheet(isPresented: $showLutPicker) {
            LutDocumentPicker { url in
                let imported = LutVault.shared.importFile(url: url)
                camera.showToast(
                    imported > 0 ? "IMPORTED \(imported) LUT\(imported == 1 ? "" : "S")" : "NO VALID .CUBE FOUND",
                    subtitle: url.lastPathComponent
                )
            }
        }
    }

    // MARK: - Option B Pure Blackout Optical Viewfinder (Top-Left Eyepiece Only, Shutter Ring Hidden)

    private var pureBlackoutViewfinder: some View {
        ZStack(alignment: .topLeading) {
            Color.black
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    camera.blackoutEvf = false
                }

            GeometryReader { _ in
                ZStack {
                    CameraPreviewSurface(camera: camera)
                        .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 9, style: .continuous)
                                .stroke(Color.white.opacity(0.11), lineWidth: 1)
                        )

                    // Minimal optical reticle + bottom telemetry strip
                    GeometryReader { vGeo in
                        let ax = (camera.afArea == .center ? 0.5 : camera.afPoint.x) * vGeo.size.width
                        let ay = (camera.afArea == .center ? 0.5 : camera.afPoint.y) * vGeo.size.height
                        Rectangle()
                            .stroke(camera.afState == 2 ? GrainPalette.green : Color.white.opacity(0.85), lineWidth: 1.2)
                            .frame(width: 24, height: 24)
                            .position(x: ax, y: ay)
                    }

                    VStack {
                        Spacer()
                        HStack(spacing: 6) {
                            Text(camera.currentFilm.code)
                                .foregroundColor(GrainPalette.orange)
                            Text(camera.liveShutterText)
                            Text("ISO \(camera.liveIsoText)")
                            Spacer()
                            Text("\(store.entries.count)")
                        }
                        .font(.custom("AvenirNextCondensed-Bold", size: 9.5))
                        .foregroundColor(.white.opacity(0.88))
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(Color.black.opacity(0.55))
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                }
                .frame(width: 168, height: 114)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onEnded { val in
                            let nx = max(0.06, min(0.94, val.location.x / 168.0))
                            let ny = max(0.06, min(0.94, val.location.y / 114.0))
                            camera.focusAtNormalizedPoint(CGPoint(x: nx, y: ny))
                        }
                )
                .padding(.leading, 18)
                .padding(.top, 14)
            }
        }
    }

    // MARK: - Full Fujifilm Rangefinder Backplate

    private var fullCameraBack: some View {
        GeometryReader { geo in
            let safeTop = geo.safeAreaInsets.top
            let safeBot = geo.safeAreaInsets.bottom

            VStack(spacing: 0) {
                topPlate(safeTop: safeTop)
                viewfinderScreen
                bottomControls(safeBottom: safeBot)
            }
            .background(GrainPalette.bodyDark.ignoresSafeArea())
        }
    }

    private func topPlate(safeTop: CGFloat) -> some View {
        let ink = GrainPalette.plateInk(finish: camera.bodyFinish)
        let filmCodes = FilmCatalog.all.map { $0.isRawPipeline && $0.kind == .recipe ? "\($0.code)◆" : $0.code }
        let shutterLabels = ExposureLadder.shutterStops.map(\.label)
        let isoLabels = ExposureLadder.isoStops.map { $0 == 0 ? "A" : "\($0)" }
        let evLabels = ExposureLadder.evStops.map { ExposureLadder.formatEv($0) } + ["C"]

        return VStack(spacing: 7) {
            // Row 1: Optical Eyepiece (EYE), Flash button, Lens engraving, Power switch
            HStack(spacing: 10) {
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    camera.blackoutEvf = true
                } label: {
                    ZStack {
                        RoundedRectangle(cornerRadius: 9, style: .continuous)
                            .fill(Color(white: 0.08))
                            .frame(width: 96, height: 36)
                            .overlay(
                                RoundedRectangle(cornerRadius: 9, style: .continuous)
                                    .stroke(Color.black.opacity(0.6), lineWidth: 1.5)
                            )
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .fill(
                                LinearGradient(
                                    colors: [Color(red: 0.14, green: 0.21, blue: 0.27), Color(red: 0.05, green: 0.08, blue: 0.11)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                            .frame(width: 84, height: 26)
                    }
                }
                .buttonStyle(.plain)

                Circle()
                    .fill(camera.isCapturing ? GrainPalette.orange : (camera.afState == 2 ? GrainPalette.green : Color(white: 0.22)))
                    .frame(width: 6, height: 6)

                HardwareKeyButton("EYE", fontSize: 9.5) {
                    camera.blackoutEvf = true
                }
                .frame(width: 40, height: 20)

                HardwareKeyButton(camera.flashSetting.label, active: camera.flashSetting != .off, fontSize: 9.5) {
                    let all = FlashSetting.allCases
                    let cur = all.firstIndex(of: camera.flashSetting) ?? 0
                    camera.flashSetting = all[(cur + 1) % all.count]
                    camera.applyExposureAndFocusState()
                }
                .frame(width: 58, height: 20)

                Spacer(minLength: 4)

                Text(camera.lensDescription.uppercased())
                    .font(.custom("AvenirNextCondensed-Bold", size: 9.5))
                    .foregroundColor(ink.opacity(0.8))
                    .lineLimit(1)

                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    camera.setPower(!camera.powerOn)
                } label: {
                    ZStack(alignment: camera.powerOn ? .trailing : .leading) {
                        Capsule()
                            .fill(Color(white: 0.10))
                            .frame(width: 52, height: 22)
                        RoundedRectangle(cornerRadius: 5, style: .continuous)
                            .fill(Color(white: 0.86))
                            .frame(width: 24, height: 20)
                            .overlay(
                                Rectangle()
                                    .fill(GrainPalette.orange)
                                    .frame(width: 2, height: 11)
                            )
                    }
                }
                .buttonStyle(.plain)
            }

            // Row 2: Shutter / ISO Drum + Exposure Compensation Drum
            HStack(spacing: 10) {
                VStack(spacing: 2) {
                    if camera.isoDialActive {
                        DrumDialView(
                            items: isoLabels,
                            selectedIndex: $camera.isoIndex,
                            finish: camera.bodyFinish,
                            accentFirstItem: true
                        ) { _ in camera.applyExposureAndFocusState() }
                        .frame(height: 28)
                    } else {
                        DrumDialView(
                            items: shutterLabels,
                            selectedIndex: $camera.shutterIndex,
                            finish: camera.bodyFinish,
                            accentFirstItem: true
                        ) { _ in camera.applyExposureAndFocusState() }
                        .frame(height: 28)
                    }
                    HStack(spacing: 6) {
                        Text(camera.isoDialActive ? "ISO SENSITIVITY" : "SHUTTER")
                            .font(.custom("AvenirNextCondensed-Bold", size: 9.5))
                            .tracking(0.8)
                            .foregroundColor(ink)
                        Button {
                            UISelectionFeedbackGenerator().selectionChanged()
                            camera.isoDialActive.toggle()
                        } label: {
                            Text("ISO")
                                .font(.custom("AvenirNextCondensed-Bold", size: 9))
                                .foregroundColor(camera.isoDialActive ? .white : ink)
                                .padding(.horizontal, 7)
                                .padding(.vertical, 1.5)
                                .background(
                                    Capsule()
                                        .fill(camera.isoDialActive ? GrainPalette.orange : Color.black.opacity(0.08))
                                )
                                .overlay(Capsule().stroke(camera.isoDialActive ? GrainPalette.orange : ink, lineWidth: 0.9))
                        }
                        .buttonStyle(.plain)
                    }
                }

                VStack(spacing: 2) {
                    DrumDialView(
                        items: evLabels,
                        selectedIndex: Binding(
                            get: { camera.customEvMode ? evLabels.count - 1 : camera.evIndex },
                            set: { idx in
                                if idx == evLabels.count - 1 {
                                    camera.customEvMode = true
                                } else {
                                    camera.customEvMode = false
                                    camera.evIndex = idx
                                }
                            }
                        ),
                        finish: camera.bodyFinish
                    ) { _ in camera.applyExposureAndFocusState() }
                    .frame(height: 28)

                    Text("EXPOSURE  ±")
                        .font(.custom("AvenirNextCondensed-Bold", size: 9.5))
                        .tracking(0.8)
                        .foregroundColor(ink)
                }
            }

            // Row 3: Full-width Film Simulation Drum Dial
            DrumDialView(
                items: filmCodes,
                selectedIndex: $camera.filmIndex,
                finish: camera.bodyFinish,
                accentFromIndex: 4
            ) { _ in
                camera.savePreferences()
                camera.showToast(camera.currentFilm.name.uppercased(), subtitle: camera.currentFilm.subtitle)
            }
            .frame(height: 28)
        }
        .padding(.horizontal, 14)
        .padding(.top, max(6, safeTop * 0.25))
        .padding(.bottom, 8)
        .background(GrainPalette.plateGradient(finish: camera.bodyFinish))
        .overlay(alignment: .bottom) {
            Rectangle().fill(Color.black.opacity(0.65)).frame(height: 1.5)
        }
    }

    // MARK: - LCD Viewfinder Window + OSD + Overlays

    private var viewfinderScreen: some View {
        GeometryReader { geo in
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color(white: 0.03))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .stroke(Color.white.opacity(0.14), lineWidth: 1)
                    )

                if camera.powerOn {
                    let frameSize = fittedPreviewSize(in: CGSize(width: geo.size.width - 12, height: geo.size.height - 12))
                    ZStack {
                        if camera.permissionGranted {
                            CameraPreviewSurface(camera: camera)
                        } else {
                            VStack(spacing: 8) {
                                Image(systemName: "camera.fill")
                                    .font(.system(size: 28))
                                    .foregroundColor(GrainPalette.orange)
                                Text("CAMERA ACCESS REQUIRED")
                                    .font(.custom("AvenirNextCondensed-Bold", size: 14))
                                    .foregroundColor(.white)
                                Text("Enable Camera permission in iOS Settings → Grain.")
                                    .font(.system(size: 12))
                                    .foregroundColor(GrainPalette.mutedEngrave)
                            }
                        }

                        if !camera.infoHidden {
                            osdLayer(size: frameSize)
                        }

                        // 4x / 8x Focus Check Magnifier Loupe
                        if let patch = camera.magnifierPatch {
                            let loupeSide = min(frameSize.width, frameSize.height) * 0.44
                            Image(uiImage: patch)
                                .resizable()
                                .interpolation(.none)
                                .scaledToFill()
                                .frame(width: loupeSide, height: loupeSide)
                                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                                        .stroke(GrainPalette.orange, lineWidth: 1.5)
                                )
                                .overlay(alignment: .topLeading) {
                                    Text("\(camera.magnifierSetting.factor)×")
                                        .font(.custom("AvenirNextCondensed-Bold", size: 10))
                                        .padding(.horizontal, 5)
                                        .padding(.vertical, 2)
                                        .background(Color.black.opacity(0.7))
                                        .foregroundColor(GrainPalette.orange)
                                        .padding(5)
                                }
                        }

                        if let title = camera.toastTitle {
                            VStack(spacing: 3) {
                                Text(title)
                                    .font(.custom("AvenirNextCondensed-Bold", size: 14))
                                    .tracking(0.8)
                                    .foregroundColor(.white)
                                if let sub = camera.toastSubtitle, !sub.isEmpty {
                                    Text(sub)
                                        .font(.system(size: 11))
                                        .foregroundColor(GrainPalette.warmIvory.opacity(0.82))
                                }
                            }
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(Capsule().fill(Color.black.opacity(0.78)))
                        }
                    }
                    .frame(width: frameSize.width, height: frameSize.height)
                    .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                    .contentShape(Rectangle())
                    .gesture(
                        SpatialTapGesture()
                            .onEnded { tap in
                                guard screenMode == .camera else { return }
                                let nx = max(0.06, min(0.94, tap.location.x / max(1, frameSize.width)))
                                let ny = max(0.06, min(0.94, tap.location.y / max(1, frameSize.height)))
                                camera.focusAtNormalizedPoint(CGPoint(x: nx, y: ny))
                            }
                    )
                    .simultaneousGesture(
                        LongPressGesture(minimumDuration: 0.35)
                            .sequenced(before: DragGesture(minimumDistance: 0))
                            .onChanged { value in
                                switch value {
                                case .second(true, _):
                                    if !camera.magnifierHeld {
                                        camera.magnifierHeld = true
                                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                    }
                                default:
                                    break
                                }
                            }
                            .onEnded { _ in
                                camera.magnifierHeld = false
                            }
                    )
                    .simultaneousGesture(
                        MagnificationGesture()
                            .onChanged { scale in
                                camera.zoomFactor = max(1.0, min(5.0, pinchBaseZoom * scale))
                                camera.applyExposureAndFocusState()
                            }
                            .onEnded { _ in
                                pinchBaseZoom = camera.zoomFactor
                            }
                    )

                    if screenMode == .qMenu {
                        QMenuOverlayView(camera: camera, selectedTile: $qTileIndex) {
                            screenMode = .camera
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .padding(6)
                    } else if screenMode == .mainMenu {
                        CameraMenuOverlayView(
                            camera: camera,
                            selectedTab: $menuTabIndex,
                            selectedRow: $menuRowIndex,
                            onImportLutTap: { showLutPicker = true },
                            onOpenFilmLab: { showFilmLab = true },
                            onOpenOnboarding: onReplayOnboarding,
                            onClose: { screenMode = .camera }
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .padding(6)
                    } else if screenMode == .playback {
                        inBezelPlaybackOverlay(area: CGSize(width: geo.size.width - 12, height: geo.size.height - 12))
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .padding(6)
                    }
                } else {
                    Text("OFF")
                        .font(.custom("AvenirNextCondensed-Bold", size: 15))
                        .tracking(2)
                        .foregroundColor(Color.white.opacity(0.22))
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
    }

    private func osdLayer(size: CGSize) -> some View {
        ZStack {
            // Rule-of-thirds framing grid
            if camera.showGrid {
                Canvas { ctx, sz in
                    for i in 1..<3 {
                        let x = sz.width * CGFloat(i) / 3.0
                        let y = sz.height * CGFloat(i) / 3.0
                        var pv = Path(); pv.move(to: CGPoint(x: x, y: 0)); pv.addLine(to: CGPoint(x: x, y: sz.height))
                        var ph = Path(); ph.move(to: CGPoint(x: 0, y: y)); ph.addLine(to: CGPoint(x: sz.width, y: y))
                        ctx.stroke(pv, with: .color(Color.white.opacity(0.28)), lineWidth: 0.8)
                        ctx.stroke(ph, with: .color(Color.white.opacity(0.28)), lineWidth: 0.8)
                    }
                }
            }

            // Electronic horizon level
            if camera.showLevel {
                let isLevel = abs(camera.rollDegrees) < 1.2
                HStack(spacing: 28) {
                    Rectangle().frame(width: 28, height: 1.4)
                    Rectangle().frame(width: 28, height: 1.4)
                }
                .foregroundColor(isLevel ? GrainPalette.green : Color.white.opacity(0.65))
                .rotationEffect(.degrees(-camera.rollDegrees))
            }

            // Face tracking brackets or steerable AF reticle
            if camera.afArea == .face, let face = camera.trackedFaceRect {
                Rectangle()
                    .stroke(GrainPalette.green, lineWidth: 1.6)
                    .frame(width: face.width * size.width, height: face.height * size.height)
                    .position(x: face.midX * size.width, y: face.midY * size.height)
            } else {
                let pt = camera.afArea == .center ? CGPoint(x: 0.5, y: 0.5) : camera.afPoint
                let boxSide: CGFloat = camera.afArea == .zone ? 68 : 36
                ZStack {
                    Rectangle()
                        .stroke(camera.afState == 2 ? GrainPalette.green : Color.white.opacity(0.92), lineWidth: 1.4)
                        .frame(width: boxSide, height: boxSide)
                    Rectangle().fill(Color.white.opacity(0.85)).frame(width: 7, height: 1)
                    Rectangle().fill(Color.white.opacity(0.85)).frame(width: 1, height: 7)
                }
                .position(x: pt.x * size.width, y: pt.y * size.height)
            }

            // Top & bottom Fujifilm viewfinder HUD
            VStack {
                HStack(spacing: 6) {
                    Text(camera.currentFilm.code)
                        .font(.custom("AvenirNextCondensed-Bold", size: 11))
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1.5)
                        .background(RoundedRectangle(cornerRadius: 3).stroke(Color.white.opacity(0.8), lineWidth: 1))
                    Text(camera.currentFilm.name.uppercased())
                        .font(.custom("AvenirNextCondensed-Bold", size: 11))
                        .shadow(color: .black.opacity(0.8), radius: 2)
                    Spacer()
                    Text(camera.keepRawDng && camera.currentFilm.isRawPipeline ? "FINE+RAW" : "FINE")
                    Text(camera.aspectRatio)
                    Text("\(store.entries.count)")
                        .foregroundColor(GrainPalette.orange)
                }
                .font(.custom("AvenirNextCondensed-Bold", size: 11))
                .foregroundColor(.white)
                .padding(.horizontal, 10)
                .padding(.top, 8)

                Spacer()

                HStack(alignment: .bottom, spacing: 8) {
                    Text(camera.liveModeBadge)
                        .font(.custom("AvenirNextCondensed-Bold", size: 11))
                        .foregroundColor(.black)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(RoundedRectangle(cornerRadius: 2.5).fill(GrainPalette.warmIvory))

                    Text(camera.liveShutterText)
                    Text(String(format: "F%.1f", camera.apertureValue))
                    Text("ISO \(camera.liveIsoText)")

                    if camera.aeLocked {
                        Text("AE-L")
                            .foregroundColor(GrainPalette.orange)
                    }
                    if abs(camera.effectiveEv) >= 0.05 {
                        Text("\(ExposureLadder.formatEv(camera.effectiveEv)) EV")
                            .foregroundColor(GrainPalette.orange)
                    }

                    Spacer()

                    if camera.showHistogram {
                        HistogramMiniView(bins: camera.histogram)
                            .frame(width: 58, height: 22)
                    }
                }
                .font(.custom("AvenirNextCondensed-Bold", size: 11.5))
                .foregroundColor(.white)
                .shadow(color: .black.opacity(0.85), radius: 2)
                .padding(.horizontal, 10)
                .padding(.bottom, 8)
            }
        }
    }

    // MARK: - Bottom Camera Controls + Enlarged 74pt Analog Focus Lever

    private func bottomControls(safeBottom: CGFloat) -> some View {
        VStack(spacing: 8) {
            // Command row: AEL/AFL + Rear Command Wheel + Q button
            HStack(spacing: 10) {
                HardwareKeyButton("AEL/AFL", active: camera.aeLocked, fontSize: 10) {
                    camera.aeLocked.toggle()
                    camera.applyExposureAndFocusState()
                    camera.showToast(camera.aeLocked ? "AE-L LOCKED" : "AE UNLOCKED")
                }
                .frame(width: 58, height: 24)

                VStack(spacing: 2) {
                    CommandWheelView(
                        isContinuous: { screenMode == .playback },
                        onSlide: { dx in
                            zoomPlaybackByFactor(CGFloat(pow(2.0, Double(dx) / 135.0)))
                        },
                        onStep: { steps in
                            handleCommandWheel(steps: steps)
                        }
                    )
                    .frame(height: 30)
                    Text(screenMode == .playback ? "COMMAND · ZOOM" : "COMMAND")
                        .font(.custom("AvenirNextCondensed-Bold", size: 8.5))
                        .tracking(1.0)
                        .foregroundColor(GrainPalette.mutedEngrave)
                }

                HardwareKeyButton("Q", round: true, active: screenMode == .qMenu, fontSize: 15) {
                    if screenMode == .playback {
                        screenMode = .camera
                        showGallery = true
                    } else {
                        screenMode = (screenMode == .qMenu) ? .camera : .qMenu
                    }
                }
                .frame(width: 44, height: 44)
            }

            // Main lower deck: Memo Box | Drive/Play/Disp | Orange Shutter | Enlarged 360° Focus Lever + MENU/OK
            HStack(alignment: .center, spacing: 10) {
                FilmMemoBoxView(film: camera.currentFilm) {
                    showFilmLab = true
                }
                .frame(width: 58, height: 128)

                VStack(alignment: .leading, spacing: 8) {
                    HardwareKeyButton(camera.driveSetting == .single ? "DRIVE" : camera.driveSetting.shortTag, active: camera.driveSetting != .single, fontSize: 9.5) {
                        if screenMode == .playback {
                            deleteCurrentPlaybackPhoto()
                        } else {
                            let all = DriveSetting.allCases
                            let cur = all.firstIndex(of: camera.driveSetting) ?? 0
                            camera.driveSetting = all[(cur + 1) % all.count]
                            camera.showToast("DRIVE · \(camera.driveSetting.label)")
                        }
                    }
                    .frame(width: 58, height: 25)

                    HardwareKeyButton("▶ PLAY", active: screenMode == .playback, fontSize: 9.5) {
                        if screenMode == .playback {
                            screenMode = .camera
                        } else if store.entries.isEmpty {
                            camera.showToast("NO PHOTOS", subtitle: "Expose a frame first")
                        } else {
                            playIndex = 0
                            resetPlaybackZoom()
                            playShowReceipt = false
                            screenMode = .playback
                        }
                    }
                    .frame(width: 58, height: 25)

                    HardwareKeyButton("DISP/BACK", fontSize: 9.5) {
                        if screenMode == .playback {
                            playShowReceipt.toggle()
                        } else if screenMode != .camera {
                            screenMode = .camera
                        } else {
                            camera.infoHidden.toggle()
                        }
                    }
                    .frame(width: 64, height: 25)
                }

                Spacer(minLength: 2)

                ShutterReleaseButton(countdown: camera.countdownText, busy: camera.isCapturing) {
                    if screenMode != .camera {
                        screenMode = .camera
                    } else {
                        camera.triggerShutter()
                    }
                }
                .frame(width: 108, height: 108)

                Spacer(minLength: 2)

                // Enlarged 72x72 pt 360° Analog Focus Lever + MENU/OK vertically aligned underneath
                VStack(spacing: 10) {
                    AnalogFocusLeverView(
                        isAnalogMode: { camera.powerOn && screenMode == .camera },
                        onAnalogGlide: { vx, vy, dt, finished in
                            camera.glideFocusBy(vx: vx, vy: vy, dt: dt, finished: finished)
                        },
                        onDiscreteKey: { dir in
                            handleLeverDiscrete(dir: dir)
                        }
                    )
                    .frame(width: 72, height: 72)

                    HardwareKeyButton("MENU\nOK", round: true, active: screenMode == .mainMenu, fontSize: 10) {
                        if screenMode == .playback {
                            screenMode = .camera
                            showGallery = true
                        } else if screenMode == .mainMenu {
                            screenMode = .camera
                        } else {
                            screenMode = .mainMenu
                        }
                    }
                    .frame(width: 50, height: 50)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 8)
        .padding(.bottom, max(10, safeBottom * 0.45))
    }

    private func fittedPreviewSize(in area: CGSize) -> CGSize {
        guard area.width > 10, area.height > 10 else { return .zero }
        let aspect: CGFloat
        switch camera.aspectRatio {
        case "1:1": aspect = 1.0
        case "9:16": aspect = 9.0 / 16.0
        default: aspect = 3.0 / 4.0
        }
        let w = min(area.width, area.height * aspect)
        let h = w / aspect
        return CGSize(width: w, height: h)
    }

    private func inBezelPlaybackOverlay(area: CGSize) -> some View {
        let count = store.entries.count
        let safeIdx = max(0, min(max(0, count - 1), playIndex))
        let entry: RollEntry? = count > 0 ? store.entries[safeIdx] : nil

        return ZStack {
            Color.black
            if let item = entry,
               let data = try? Data(contentsOf: item.jpegURL),
               let ui = UIImage(data: data) {
                Image(uiImage: ui)
                    .resizable()
                    .scaledToFit()
                    .scaleEffect(playZoom)
                    .offset(playPan)
                    .frame(width: area.width, height: area.height)
                    .clipped()
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 4)
                            .onChanged { val in
                                if playZoom > 1.02 {
                                    let maxX = max(0, (area.width * (playZoom - 1.0)) * 0.5)
                                    let maxY = max(0, (area.height * (playZoom - 1.0)) * 0.5)
                                    let nx = max(-maxX, min(maxX, playDragBase.width + val.translation.width))
                                    let ny = max(-maxY, min(maxY, playDragBase.height + val.translation.height))
                                    playPan = CGSize(width: nx, height: ny)
                                }
                            }
                            .onEnded { val in
                                if playZoom > 1.02 {
                                    playDragBase = playPan
                                } else if abs(val.translation.width) > 36 {
                                    stepPlaybackPhoto(delta: val.translation.width < 0 ? 1 : -1)
                                }
                            }
                    )
                    .simultaneousGesture(
                        MagnificationGesture()
                            .onChanged { scale in
                                let target = max(1.0, min(8.0, playPinchBase * scale))
                                if playZoom > 0.01 {
                                    zoomPlaybackByFactor(target / playZoom)
                                }
                            }
                            .onEnded { _ in
                                playPinchBase = playZoom
                            }
                    )
                    .onTapGesture(count: 2) {
                        if playZoom > 1.05 {
                            resetPlaybackZoom()
                        } else {
                            playZoom = 2.5
                            playPinchBase = 2.5
                        }
                    }

                if playShowReceipt {
                    ScrollView {
                        Text(store.receiptText(for: item))
                            .font(.system(size: 11.5))
                            .foregroundColor(GrainPalette.warmIvory)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                    }
                    .background(Color.black.opacity(0.84))
                    .padding(.vertical, 26)
                }

                VStack {
                    HStack {
                        Text("\(safeIdx + 1) / \(count)   \(item.filmName.uppercased())\(playZoom > 1.02 ? String(format: "  ·  %.1f×", playZoom) : "")")
                            .font(.custom("AvenirNextCondensed-Bold", size: 11))
                            .foregroundColor(GrainPalette.warmIvory)
                            .lineLimit(1)
                        Spacer()
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(Color.black.opacity(0.65))

                    Spacer()

                    Text("LEVER BROWSE · COMMAND ZOOM · DISP RECEIPT · DRIVE DEL · Q ALL")
                        .font(.custom("AvenirNextCondensed-DemiBold", size: 8.8))
                        .foregroundColor(GrainPalette.mutedEngrave)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                        .background(Color.black.opacity(0.68))
                }
            }
        }
    }

    private func resetPlaybackZoom() {
        playZoom = 1.0
        playPinchBase = 1.0
        playPan = .zero
        playDragBase = .zero
    }

    private func zoomPlaybackByFactor(_ factor: CGFloat) {
        let old = playZoom
        var next = max(1.0, min(8.0, old * factor))
        if next <= 1.015 {
            next = 1.0
            playPan = .zero
            playDragBase = .zero
        } else if old > 0.01 {
            let ratio = next / old
            playPan = CGSize(width: playPan.width * ratio, height: playPan.height * ratio)
            playDragBase = playPan
        }
        playZoom = next
        playPinchBase = next
    }

    private func stepPlaybackPhoto(delta: Int) {
        guard !store.entries.isEmpty else { return }
        let next = max(0, min(store.entries.count - 1, playIndex + (delta >= 0 ? 1 : -1)))
        if next != playIndex {
            playIndex = next
            resetPlaybackZoom()
            UISelectionFeedbackGenerator().selectionChanged()
        }
    }

    private func deleteCurrentPlaybackPhoto() {
        guard !store.entries.isEmpty else { return }
        let idx = max(0, min(store.entries.count - 1, playIndex))
        let id = store.entries[idx].id
        store.deleteBatch(ids: [id])
        resetPlaybackZoom()
        if store.entries.isEmpty {
            screenMode = .camera
        } else {
            playIndex = min(idx, store.entries.count - 1)
        }
    }

    private func handleCommandWheel(steps: Int) {
        switch screenMode {
        case .qMenu:
            stepQTile(qTileIndex, delta: steps, camera: camera)
        case .mainMenu:
            menuRowIndex = max(0, menuRowIndex + steps)
        case .playback:
            zoomPlaybackByFactor(CGFloat(pow(1.22, Double(steps))))
        case .camera:
            if camera.customEvMode {
                camera.fineEv = max(-2.0, min(2.0, ((camera.fineEv + Float(steps) * 0.1) * 10).rounded() / 10))
                camera.applyExposureAndFocusState()
                camera.showToast(String(format: "%+.1f EV", camera.fineEv))
            } else if camera.focusMode == .manual {
                camera.manualFocusStep = max(0, min(30, camera.manualFocusStep + steps))
                camera.applyExposureAndFocusState()
                camera.showToast("MF \(camera.manualFocusStep)/30")
            } else {
                camera.zoomFactor = max(1.0, min(5.0, camera.zoomFactor + CGFloat(steps) * 0.15))
                pinchBaseZoom = camera.zoomFactor
                camera.applyExposureAndFocusState()
                camera.showToast(String(format: "%.1f× ZOOM", camera.zoomFactor))
            }
        }
    }

    private func handleLeverDiscrete(dir: Int) {
        switch screenMode {
        case .qMenu:
            if dir == 0 {
                screenMode = .camera
            } else {
                var row = qTileIndex / 4
                var col = qTileIndex % 4
                if dir == 1 { row = (row + 3) % 4 }
                else if dir == 3 { row = (row + 1) % 4 }
                else if dir == 4 { col = (col + 3) % 4 }
                else if dir == 2 { col = (col + 1) % 4 }
                qTileIndex = row * 4 + col
            }
        case .mainMenu:
            if dir == 0 {
                screenMode = .camera
            } else if dir == 4 || dir == 2 {
                menuTabIndex = (menuTabIndex + (dir == 4 ? 3 : 1)) % 4
                menuRowIndex = 0
            } else {
                menuRowIndex = max(0, menuRowIndex + (dir == 1 ? -1 : 1))
            }
        case .playback:
            if dir == 4 || dir == 1 {
                stepPlaybackPhoto(delta: -1)
            } else if dir == 2 || dir == 3 {
                stepPlaybackPhoto(delta: 1)
            } else {
                screenMode = .camera
                showGallery = true
            }
        case .camera:
            if dir == 0 {
                camera.focusAtNormalizedPoint(CGPoint(x: 0.5, y: 0.5), unlockCenterAndFace: false)
                camera.showToast("AF CENTRED")
            }
        }
    }
}

private struct HistogramMiniView: View {
    let bins: [Float]
    var body: some View {
        Canvas { ctx, size in
            ctx.fill(Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 3), with: .color(Color.black.opacity(0.45)))
            guard !bins.isEmpty else { return }
            let bw = size.width / CGFloat(bins.count)
            for i in 0..<bins.count {
                let h = max(1, CGFloat(bins[i]) * (size.height - 4))
                let r = CGRect(x: CGFloat(i) * bw, y: size.height - h - 2, width: max(1, bw - 0.4), height: h)
                ctx.fill(Path(r), with: .color(Color.white.opacity(0.78)))
            }
        }
    }
}
