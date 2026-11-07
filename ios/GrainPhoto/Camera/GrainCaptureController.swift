import Foundation
import AVFoundation
import CoreMotion
import CoreImage
import UIKit
import MediaPlayer

public enum FlashSetting: Int, CaseIterable {
    case off = 0
    case on = 1
    case auto = 2
    case torch = 3

    public var label: String {
        switch self {
        case .off: return "⚡ OFF"
        case .on: return "⚡ ON"
        case .auto: return "⚡ AUTO"
        case .torch: return "⚡ TORCH"
        }
    }
}

public enum DriveSetting: Int, CaseIterable {
    case single = 0
    case timer3 = 1
    case timer5 = 2
    case filmBracket = 3
    case focusBracket = 4
    case hiRes36 = 5
    case hiRes48 = 6

    public var label: String {
        switch self {
        case .single: return "SINGLE"
        case .timer3: return "3s TIMER"
        case .timer5: return "5s TIMER"
        case .filmBracket: return "FS BKT"
        case .focusBracket: return "FOCUS BKT"
        case .hiRes36: return "HI-RES 36MP"
        case .hiRes48: return "HI-RES 48MP"
        }
    }

    public var shortTag: String {
        switch self {
        case .single: return "S"
        case .timer3: return "3s"
        case .timer5: return "5s"
        case .filmBracket: return "FS-B"
        case .focusBracket: return "FC-B"
        case .hiRes36: return "HR 36MP"
        case .hiRes48: return "HR 48MP"
        }
    }

    /// The target output pixel count of a tripod super-resolution mode, or nil for the other modes.
    public var hiResTargetPixelCount: Int? {
        switch self {
        case .hiRes36: return HiResAccumulator.targetPixels
        case .hiRes48: return HiResAccumulator.targetPixels48
        default: return nil
        }
    }

    /// The nominal output size of a tripod super-resolution mode, in megapixels.
    public var hiResMegapixels: Int {
        switch self {
        case .hiRes48: return 48
        default: return 36
        }
    }
}

public enum FocusModeSetting: Int, CaseIterable {
    case afc = 0
    case afs = 1
    case manual = 2

    public var label: String {
        switch self {
        case .afc: return "AF-C"
        case .afs: return "AF-S"
        case .manual: return "MANUAL"
        }
    }
}

public enum AfAreaSetting: Int, CaseIterable {
    case single = 0
    case zone = 1
    case center = 2
    case face = 3

    public var label: String {
        switch self {
        case .single: return "SINGLE"
        case .zone: return "ZONE"
        case .center: return "CENTRE"
        case .face: return "FACE"
        }
    }
}

public enum MagnifierSetting: Int, CaseIterable {
    case off = 0
    case hold4x = 1
    case hold8x = 2
    case always4x = 3

    public var label: String {
        switch self {
        case .off: return "OFF"
        case .hold4x: return "4× HOLD"
        case .hold8x: return "8× HOLD"
        case .always4x: return "4× ON"
        }
    }

    public var factor: Int {
        switch self {
        case .off: return 1
        case .hold8x: return 8
        default: return 4
        }
    }
}

@MainActor
public final class GrainCaptureController: NSObject, ObservableObject {
    public let session = AVCaptureSession()

    // Camera state & telemetry
    @Published public var permissionGranted: Bool = false
    @Published public var isSessionRunning: Bool = false
    @Published public var supportsHardwareRaw: Bool = false
    @Published public var lensDescription: String = "24mm f/1.8"
    @Published public var apertureValue: Double = 1.8

    @Published public var filmIndex: Int = 4 // Reggie's Portra default
    @Published public var shutterIndex: Int = 0 // A
    @Published public var isoIndex: Int = 0 // A
    @Published public var evIndex: Int = 6 // 0 EV
    @Published public var fineEv: Float = 0.0
    @Published public var customEvMode: Bool = false
    @Published public var isoDialActive: Bool = false

    @Published public var aspectRatio: String = "3:4"
    @Published public var zoomFactor: CGFloat = 1.0
    @Published public var flashSetting: FlashSetting = .off
    @Published public var driveSetting: DriveSetting = .single
    @Published public var focusMode: FocusModeSetting = .afc
    @Published public var afArea: AfAreaSetting = .single
    @Published public var magnifierSetting: MagnifierSetting = .hold4x
    @Published public var magnifierHeld: Bool = false
    @Published public var manualFocusStep: Int = 15 // 0..30
    @Published public var focusPeaking: Bool = false
    @Published public var showGrid: Bool = false
    @Published public var showHistogram: Bool = true
    @Published public var showLevel: Bool = true
    @Published public var infoHidden: Bool = false
    @Published public var keepRawDng: Bool = true
    @Published public var bodyFinish: Int = 0 // 0 Silver, 1 Black, 2 Graphite
    @Published public var powerOn: Bool = true
    @Published public var blackoutEvf: Bool = false
    @Published public var aeLocked: Bool = false
    @Published public var afLocked: Bool = false
    @Published public var rollNote: String = ""

    // Live viewfinder OSD readouts
    @Published public var afPoint: CGPoint = CGPoint(x: 0.5, y: 0.5)
    @Published public var afState: Int = 0 // 0 idle, 1 scanning, 2 locked, 3 fail
    @Published public var trackedFaceRect: CGRect?
    @Published public var liveShutterText: String = "1/125"
    @Published public var liveIsoText: String = "100"
    @Published public var liveModeBadge: String = "P"
    @Published public var rollDegrees: Double = 0.0
    @Published public var histogram: [Float] = Array(repeating: 0, count: 32)
    @Published public var magnifierPatch: UIImage?
    @Published public var peakingOverlay: UIImage?
    @Published public var isCapturing: Bool = false
    @Published public var countdownText: String = ""
    @Published public var toastTitle: String?
    @Published public var toastSubtitle: String?

    private var tunings: [String: FilmTuning] = [:]
    private let sessionQueue = DispatchQueue(label: "dev.grain.photo.session", qos: .userInitiated)
    private let videoQueue = DispatchQueue(label: "dev.grain.photo.video", qos: .utility)
    private var videoDevice: AVCaptureDevice?
    private let photoOutput = AVCapturePhotoOutput()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let metadataOutput = AVCaptureMetadataOutput()
    private let motionManager = CMMotionManager()
    private var inFlightDelegates: [Int64: PhotoCaptureHandler] = [:]
    private var meterTimer: Timer?
    private var countdownTimer: Timer?
    private var toastWork: DispatchWorkItem?
    private var lastSceneProduct: Double = 0.008 * 100.0
    private var volumeObservation: NSKeyValueObservation?
    private var hiddenVolumeView: MPVolumeView?
    private var lastVolumeFireAt: TimeInterval = 0
    private var frameCounter: Int = 0
    // Private flag owned by sessionQueue so zoom updates never mutate videoZoomFactor mid-RAW capture
    private var bayerCaptureInFlight: Bool = false

    public override init() {
        super.init()
        loadPreferences()
    }

    public var currentFilm: GrainFilm {
        FilmCatalog.all[max(0, min(FilmCatalog.all.count - 1, filmIndex))]
    }

    public var currentTuning: FilmTuning {
        let film = currentFilm
        return tunings[film.id] ?? FilmTuning.defaults(for: film)
    }

    public func updateTuning(_ mutate: (inout FilmTuning) -> Void) {
        let film = currentFilm
        var t = currentTuning
        mutate(&t)
        tunings[film.id] = t
        savePreferences()
    }

    public func resetCurrentTuning() {
        tunings.removeValue(forKey: currentFilm.id)
        savePreferences()
        showToast("RECIPE RESET", subtitle: currentFilm.name)
    }

    public var effectiveEv: Float {
        customEvMode ? fineEv : ExposureLadder.evStops[max(0, min(ExposureLadder.evStops.count - 1, evIndex))]
    }

    // MARK: - Lifecycle & Permissions

    public func start() {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        switch status {
        case .authorized:
            permissionGranted = true
            configureAndStartSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                Task { @MainActor in
                    self?.permissionGranted = granted
                    if granted {
                        self?.configureAndStartSession()
                    }
                }
            }
        default:
            permissionGranted = false
        }
        startMotionUpdates()
        startTelemetryTimer()
    }

    public func stop() {
        motionManager.stopDeviceMotionUpdates()
        meterTimer?.invalidate()
        meterTimer = nil
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            if self.session.isRunning {
                self.session.stopRunning()
            }
        }
    }

    private func configureAndStartSession() {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            if !self.session.inputs.isEmpty {
                if !self.session.isRunning && self.powerOn {
                    self.session.startRunning()
                }
                Task { @MainActor in self.isSessionRunning = self.session.isRunning }
                return
            }

            self.session.beginConfiguration()
            self.session.sessionPreset = .photo

            let discovery = AVCaptureDevice.DiscoverySession(
                deviceTypes: [.builtInWideAngleCamera],
                mediaType: .video,
                position: .back
            )
            guard let device = discovery.devices.first ?? AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device),
                  self.session.canAddInput(input) else {
                self.session.commitConfiguration()
                return
            }

            self.session.addInput(input)
            self.videoDevice = device

            if self.session.canAddOutput(self.photoOutput) {
                self.session.addOutput(self.photoOutput)
                self.photoOutput.isHighResolutionCaptureEnabled = true
                if #available(iOS 16.0, *) {
                    self.photoOutput.maxPhotoQualityPrioritization = .quality
                }
            }

            self.videoOutput.alwaysDiscardsLateVideoFrames = true
            self.videoOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
            ]
            self.videoOutput.setSampleBufferDelegate(self, queue: self.videoQueue)
            if self.session.canAddOutput(self.videoOutput) {
                self.session.addOutput(self.videoOutput)
            }

            if self.session.canAddOutput(self.metadataOutput) {
                self.session.addOutput(self.metadataOutput)
                if self.metadataOutput.availableMetadataObjectTypes.contains(.face) {
                    self.metadataOutput.metadataObjectTypes = [.face]
                    self.metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
                }
            }

            let rawSupported = self.photoOutput.availableRawPhotoPixelFormatTypes.contains {
                AVCapturePhotoOutput.isBayerRAWPixelFormat($0)
            }
            let fNum = Double(device.lensAperture)
            self.session.commitConfiguration()

            if self.powerOn {
                self.session.startRunning()
            }

            Task { @MainActor in
                self.supportsHardwareRaw = rawSupported
                self.apertureValue = fNum > 0 ? fNum : 1.8
                self.lensDescription = String(format: "24mm f/%.1f%@", self.apertureValue, rawSupported ? " · RAW" : "")
                self.isSessionRunning = self.session.isRunning
                self.applyExposureAndFocusState()
            }
        }
    }

    public func setPower(_ on: Bool) {
        powerOn = on
        if !on { blackoutEvf = false }
        savePreferences()
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            if on && !self.session.isRunning {
                self.session.startRunning()
            } else if !on && self.session.isRunning {
                self.session.stopRunning()
            }
            Task { @MainActor in
                self.isSessionRunning = self.session.isRunning
            }
        }
    }

    // MARK: - Volume Button Hardware Shutter (for Pure Blackout Optical Viewfinder)

    public func installVolumeShutterObserver(in hostView: UIView) {
        guard hiddenVolumeView == nil else { return }
        let vv = MPVolumeView(frame: CGRect(x: -2000, y: -2000, width: 1, height: 1))
        vv.alpha = 0.01
        hostView.addSubview(vv)
        hiddenVolumeView = vv

        let audio = AVAudioSession.sharedInstance()
        try? audio.setCategory(.ambient, options: [.mixWithOthers])
        try? audio.setActive(true)

        volumeObservation = audio.observe(\.outputVolume, options: [.old, .new]) { [weak self] _, change in
            guard let self = self,
                  let oldV = change.oldValue,
                  let newV = change.newValue,
                  abs(newV - oldV) > 0.001 else { return }
            Task { @MainActor in
                guard self.blackoutEvf && self.powerOn && !self.isCapturing else { return }
                let now = CFAbsoluteTimeGetCurrent()
                guard now - self.lastVolumeFireAt > 0.55 else { return }
                self.lastVolumeFireAt = now
                self.triggerShutter()
            }
        }
    }

    // MARK: - Exposure, Flash, Zoom & Focus Control

    public func applyExposureAndFocusState() {
        let sStop = ExposureLadder.shutterStops[max(0, min(ExposureLadder.shutterStops.count - 1, shutterIndex))]
        let isoVal = ExposureLadder.isoStops[max(0, min(ExposureLadder.isoStops.count - 1, isoIndex))]
        let ev = effectiveEv
        let flash = flashSetting
        let lockAe = aeLocked
        let fMode = focusMode
        let mfFrac = Float(manualFocusStep) / 30.0
        let zoom = zoomFactor
        let pt = afArea == .center ? CGPoint(x: 0.5, y: 0.5) : afPoint

        if sStop.seconds == 0 && isoVal == 0 {
            liveModeBadge = "P"
        } else if sStop.seconds > 0 && isoVal == 0 {
            liveModeBadge = "S"
        } else if sStop.seconds == 0 && isoVal > 0 {
            liveModeBadge = "ISO"
        } else {
            liveModeBadge = "M"
        }

        sessionQueue.async { [weak self] in
            guard let self = self, let dev = self.videoDevice else { return }
            guard (try? dev.lockForConfiguration()) != nil else { return }
            defer { dev.unlockForConfiguration() }

            // Zoom (do not mutate videoZoomFactor while a Bayer RAW capture is in flight: AVFoundation invariant)
            if !self.bayerCaptureInFlight {
                let maxZ = min(5.0, dev.activeFormat.videoMaxZoomFactor)
                dev.videoZoomFactor = max(1.0, min(maxZ, zoom))
            }

            // Torch
            if dev.hasTorch {
                if flash == .torch && self.powerOn {
                    try? dev.setTorchModeOn(level: 0.85)
                } else if dev.torchMode != .off {
                    dev.torchMode = .off
                }
            }

            // Exposure (P / S / ISO / M + AE-L)
            let minDur = CMTimeGetSeconds(dev.activeFormat.minExposureDuration)
            let maxDur = CMTimeGetSeconds(dev.activeFormat.maxExposureDuration)
            let minIso = Double(dev.activeFormat.minISO)
            let maxIso = Double(dev.activeFormat.maxISO)

            if lockAe {
                if dev.isExposureModeSupported(.locked) {
                    dev.exposureMode = .locked
                }
            } else if sStop.seconds == 0 && isoVal == 0 {
                if dev.isExposurePointOfInterestSupported {
                    dev.exposurePointOfInterest = CGPoint(x: pt.y, y: 1.0 - pt.x)
                }
                if dev.isExposureModeSupported(.continuousAutoExposure) {
                    dev.exposureMode = .continuousAutoExposure
                }
                let clampedBias = max(dev.minExposureTargetBias, min(dev.maxExposureTargetBias, ev))
                dev.setExposureTargetBias(clampedBias, completionHandler: nil)
            } else if dev.isExposureModeSupported(.custom) {
                let targetProduct = max(minDur * minIso, self.lastSceneProduct * pow(2.0, Double(ev)))
                let reqSec: Double
                let reqIso: Double
                if sStop.seconds > 0 && isoVal > 0 {
                    reqSec = max(minDur, min(maxDur, sStop.seconds))
                    reqIso = max(minIso, min(maxIso, Double(isoVal)))
                } else if sStop.seconds > 0 {
                    reqSec = max(minDur, min(maxDur, sStop.seconds))
                    reqIso = max(minIso, min(maxIso, targetProduct / max(1e-6, reqSec)))
                } else {
                    reqIso = max(minIso, min(maxIso, Double(isoVal)))
                    reqSec = max(minDur, min(maxDur, targetProduct / max(1.0, reqIso)))
                }
                // Preview frame-rate cap at 1/8s so viewfinder stays responsive while boosting ISO to match brightness
                let previewSec = min(0.125, reqSec)
                let previewIso = max(minIso, min(maxIso, reqIso * (reqSec / max(1e-6, previewSec))))
                let cmDur = CMTimeMakeWithSeconds(previewSec, preferredTimescale: 1_000_000_000)
                dev.setExposureTargetBias(0, completionHandler: nil)
                dev.setExposureModeCustom(duration: cmDur, iso: Float(previewIso), completionHandler: nil)
            }

            // Focus (AF-C / AF-S / 30-step Manual)
            if fMode == .manual {
                if dev.isLockingFocusWithCustomLensPositionSupported {
                    let lensPos = max(0.0, min(1.0, 1.0 - mfFrac))
                    dev.setFocusModeLocked(lensPosition: lensPos, completionHandler: nil)
                }
            } else if self.afLocked {
                if dev.isFocusModeSupported(.locked) {
                    dev.focusMode = .locked
                }
            } else {
                if dev.isFocusPointOfInterestSupported {
                    dev.focusPointOfInterest = CGPoint(x: pt.y, y: 1.0 - pt.x)
                }
                let desired: AVCaptureDevice.FocusMode = (fMode == .afc) ? .continuousAutoFocus : .autoFocus
                if dev.isFocusModeSupported(desired) {
                    dev.focusMode = desired
                }
            }
        }
    }

    public func focusAtNormalizedPoint(_ pt: CGPoint, unlockCenterAndFace: Bool = true) {
        if unlockCenterAndFace && (afArea == .center || afArea == .face) {
            afArea = .single
        }
        afPoint = CGPoint(x: max(0.06, min(0.94, pt.x)), y: max(0.06, min(0.94, pt.y)))
        afState = 1
        applyExposureAndFocusState()
    }

    public func glideFocusBy(vx: CGFloat, vy: CGFloat, dt: CGFloat, finished: Bool) {
        guard powerOn && !isCapturing else { return }
        if dt > 0 && (vx != 0 || vy != 0) {
            if afArea == .center {
                afPoint = CGPoint(x: 0.5, y: 0.5)
                afArea = .single
            } else if afArea == .face, let face = trackedFaceRect {
                afPoint = CGPoint(x: face.midX, y: face.midY)
                afArea = .single
            }
            afPoint = FocusLeverMath.glideStep(point: afPoint, vx: vx, vy: vy, dt: dt, maxPerSec: 0.90)
        }
        if finished {
            afState = 1
            applyExposureAndFocusState()
        }
    }

    // MARK: - Capture & Multi-Frame Bracketing

    public func triggerShutter() {
        guard powerOn else { return }
        if let activeTimer = countdownTimer {
            activeTimer.invalidate()
            countdownTimer = nil
            countdownText = ""
            isCapturing = false
            showToast("TIMER CANCELLED")
            return
        }
        guard !isCapturing else { return }
        switch driveSetting {
        case .single:
            captureSingleFrame(filmOverride: nil, lensPosOverride: nil)
        case .timer3:
            runSelfTimer(seconds: 3) { [weak self] in
                self?.captureSingleFrame(filmOverride: nil, lensPosOverride: nil)
            }
        case .timer5:
            runSelfTimer(seconds: 5) { [weak self] in
                self?.captureSingleFrame(filmOverride: nil, lensPosOverride: nil)
            }
        case .filmBracket:
            let count = FilmCatalog.all.count
            let f0 = currentFilm
            let f1 = FilmCatalog.all[(filmIndex + 1) % count]
            let f2 = FilmCatalog.all[(filmIndex + 2) % count]
            showToast("FS BRACKET", subtitle: "\(f0.code) · \(f1.code) · \(f2.code)")
            captureSingleFrame(filmOverride: nil, lensPosOverride: nil, extraFilms: [f1, f2])
        case .focusBracket:
            showToast("FOCUS BRACKET", subtitle: "3 focus planes")
            captureFocusBracket(planes: [0.25, 0.52, 0.80])
        case .hiRes36, .hiRes48:
            showToast("\(label) · 4s SETTLE", subtitle: "Keep tripod steady · tap shutter to cancel")
            runSelfTimer(seconds: 4) { [weak self] in
                self?.captureHiResSequence()
            }
        }
    }

    private func runSelfTimer(seconds: Int, onFire: @escaping () -> Void) {
        isCapturing = true
        var rem = seconds
        countdownText = "\(rem)"
        countdownTimer?.invalidate()
        countdownTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] t in
            Task { @MainActor in
                guard let self = self else { t.invalidate(); return }
                rem -= 1
                if rem > 0 {
                    self.countdownText = "\(rem)"
                } else {
                    t.invalidate()
                    self.countdownTimer = nil
                    self.countdownText = ""
                    self.isCapturing = false
                    onFire()
                }
            }
        }
    }

    private func captureHiResSequence() {
        isCapturing = true
        let primaryFilm = currentFilm
        let primaryTuning = tunings[primaryFilm.id] ?? FilmTuning.defaults(for: primaryFilm)
        let ratio = aspectRatio
        let ev = effectiveEv
        let keepRaw = keepRawDng
        let flash = flashSetting
        let reqZoom = Float(max(1.0, min(5.0, zoomFactor)))
        let targetPixels = driveSetting.hiResTargetPixelCount ?? HiResAccumulator.targetPixels
        let hiResMp = driveSetting.hiResMegapixels
        let accumulator = HiResAccumulator(aspectRatio: ratio, digitalZoom: reqZoom, targetPixelCount: targetPixels)
        var anchorRawData: Data? = nil
        var savedCaptureMeta: [String: Any] = [:]
        var savedPreviewZoom: CGFloat? = nil

        countdownText = "1/4"

        // Lock AE, AF & unzoomed 1.0x sensor readout prior to Frame 1
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            self.bayerCaptureInFlight = true
            if let dev = self.videoDevice, (try? dev.lockForConfiguration()) != nil {
                if dev.videoZoomFactor != 1.0 {
                    savedPreviewZoom = dev.videoZoomFactor
                    dev.videoZoomFactor = 1.0
                }
                if dev.isExposureModeSupported(.locked) {
                    dev.exposureMode = .locked
                }
                if dev.isFocusModeSupported(.locked) {
                    dev.focusMode = .locked
                }
                dev.unlockForConfiguration()
            }
            if let conn = self.photoOutput.connection(with: .video), conn.videoScaleAndCropFactor != 1.0 {
                conn.videoScaleAndCropFactor = 1.0
            }

            func finishAndCleanup(failedMessage: String?) {
                self.sessionQueue.async {
                    self.bayerCaptureInFlight = false
                    if let prevZ = savedPreviewZoom, let dev = self.videoDevice,
                       (try? dev.lockForConfiguration()) != nil {
                        let maxZ = min(5.0, dev.activeFormat.videoMaxZoomFactor)
                        dev.videoZoomFactor = max(1.0, min(maxZ, prevZ))
                        dev.unlockForConfiguration()
                    }
                }
                Task { @MainActor in
                    self.countdownText = ""
                    self.isCapturing = false
                    self.applyExposureAndFocusState()
                    if let msg = failedMessage {
                        self.showToast("HI-RES FAILED", subtitle: msg)
                    } else {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        self.showToast("\(hiResMp)MP HI-RES SAVED", subtitle: "\(primaryFilm.name) · 4 frames merged")
                    }
                }
            }

            func fireStep(_ step: Int) {
                Task { @MainActor in
                    self.countdownText = "\(step + 1)/4"
                    if step > 0 {
                        // Taptic micro-pulse excites the spring-mounted OIS lens by ~0.35-0.75 px on a locked tripod
                        let intensity: CGFloat = step == 1 ? 0.58 : (step == 2 ? 0.82 : 0.68)
                        let gen = UIImpactFeedbackGenerator(style: .rigid)
                        gen.prepare()
                        gen.impactOccurred(intensity: intensity)
                    }
                }

                let delay: TimeInterval = step == 0 ? 0.02 : 0.038
                self.sessionQueue.asyncAfter(deadline: .now() + delay) { [weak self] in
                    guard let self = self else { return }
                    let rawTypes = self.photoOutput.availableRawPhotoPixelFormatTypes
                    let bayerRaw = rawTypes.first(where: { AVCapturePhotoOutput.isBayerRAWPixelFormat($0) })
                    let settings: AVCapturePhotoSettings
                    if primaryFilm.isRawPipeline, let rawFormat = bayerRaw {
                        settings = AVCapturePhotoSettings(
                            rawPixelFormatType: rawFormat,
                            processedFormat: [AVVideoCodecKey: AVVideoCodecType.jpeg]
                        )
                        settings.photoQualityPrioritization = .speed
                        settings.isHighResolutionPhotoEnabled = true
                    } else {
                        settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
                        settings.isHighResolutionPhotoEnabled = true
                        if #available(iOS 16.0, *) {
                            settings.photoQualityPrioritization = .quality
                        }
                    }

                    if step == 0 {
                        if self.photoOutput.supportedFlashModes.contains(.on) && flash == .on {
                            settings.flashMode = .on
                        } else if self.photoOutput.supportedFlashModes.contains(.auto) && flash == .auto {
                            settings.flashMode = .auto
                        } else if self.photoOutput.supportedFlashModes.contains(.off) {
                            settings.flashMode = .off
                        }
                        let devIso = Int((self.videoDevice?.iso ?? 100).rounded())
                        let devSec = CMTimeGetSeconds(self.videoDevice?.exposureDuration ?? CMTimeMake(value: 1, timescale: 125))
                        savedCaptureMeta = [
                            "iso": devIso,
                            "exposureSeconds": devSec,
                            "aperture": self.apertureValue,
                            "flash": flash.label
                        ]
                    } else if self.photoOutput.supportedFlashModes.contains(.off) {
                        settings.flashMode = .off
                    }

                    let uid = settings.uniqueID
                    let handler = PhotoCaptureHandler(uid: uid) { [weak self] rawData, processedData in
                        guard let self = self else { return }
                        Task { @MainActor in
                            self.inFlightDelegates.removeValue(forKey: uid)
                        }
                        if step == 0 && keepRaw {
                            anchorRawData = rawData
                        }
                        DispatchQueue.global(qos: .userInitiated).async {
                            let ok = accumulator.addCapture(
                                rawDngData: rawData,
                                fallbackImageData: processedData,
                                preferRaw: primaryFilm.isRawPipeline
                            )
                            if !ok {
                                finishAndCleanup(failedMessage: "Frame \(step + 1) alignment failed")
                                return
                            }
                            if step < 3 {
                                fireStep(step + 1)
                            } else {
                                Task { @MainActor in
                                    self.countdownText = "\(hiResMp)M"
                                    self.showToast("MERGING \(hiResMp)MP…", subtitle: primaryFilm.name)
                                }
                                if let out = GrainDeveloper.developHiRes(
                                    accumulator: accumulator,
                                    film: primaryFilm,
                                    tuning: primaryTuning,
                                    aspectRatio: ratio,
                                    userEv: ev,
                                    captureMeta: savedCaptureMeta
                                ) {
                                    PhotoStore.shared.saveCapture(
                                        jpegData: out.jpegData,
                                        rawDngData: anchorRawData,
                                        keepRaw: keepRaw && anchorRawData != nil,
                                        receipt: out.receipt
                                    )
                                    anchorRawData = nil
                                    finishAndCleanup(failedMessage: nil)
                                } else {
                                    anchorRawData = nil
                                    finishAndCleanup(failedMessage: "Could not encode \(hiResMp)MP image")
                                }
                            }
                        }
                    }
                    Task { @MainActor in
                        self.inFlightDelegates[uid] = handler
                    }
                    self.photoOutput.capturePhoto(with: settings, delegate: handler)
                }
            }

            fireStep(0)
        }
    }

    private func captureFocusBracket(planes: [Float]) {
        guard let first = planes.first else { return }
        let rest = Array(planes.dropFirst())
        captureSingleFrame(filmOverride: nil, lensPosOverride: first) { [weak self] in
            if !rest.isEmpty {
                self?.captureFocusBracket(planes: rest)
            }
        }
    }

    private func captureSingleFrame(
        filmOverride: GrainFilm?,
        lensPosOverride: Float?,
        extraFilms: [GrainFilm] = [],
        onDone: (() -> Void)? = nil
    ) {
        isCapturing = true
        let primaryFilm = filmOverride ?? currentFilm
        let primaryTuning = tunings[primaryFilm.id] ?? FilmTuning.defaults(for: primaryFilm)
        let allFilmsToDevelop: [(GrainFilm, FilmTuning)] =
            [(primaryFilm, primaryTuning)] + extraFilms.map { ($0, tunings[$0.id] ?? FilmTuning.defaults(for: $0)) }
        let ratio = aspectRatio
        let ev = effectiveEv
        let keepRaw = keepRawDng
        let flash = flashSetting
        let reqZoom = Float(max(1.0, min(5.0, zoomFactor)))

        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            if let lensPos = lensPosOverride, let dev = self.videoDevice,
               dev.isLockingFocusWithCustomLensPositionSupported,
               (try? dev.lockForConfiguration()) != nil {
                dev.setFocusModeLocked(lensPosition: lensPos, completionHandler: nil)
                dev.unlockForConfiguration()
                Thread.sleep(forTimeInterval: 0.09)
            }

            let rawTypes = self.photoOutput.availableRawPhotoPixelFormatTypes
            let bayerRaw = rawTypes.first(where: { AVCapturePhotoOutput.isBayerRAWPixelFormat($0) })
            let settings: AVCapturePhotoSettings
            var restoreZoomAfterRaw: CGFloat? = nil

            if primaryFilm.isRawPipeline, let rawFormat = bayerRaw {
                // AVFoundation Bayer RAW rules (iPhone XR / XS through iPhone 16):
                // 1. videoZoomFactor & videoScaleAndCropFactor MUST both be 1.0 during Bayer RAW capture.
                // 2. photoQualityPrioritization MUST be .speed (single-exposure sensor readout, no ISP fusion).
                self.bayerCaptureInFlight = true
                if let dev = self.videoDevice, dev.videoZoomFactor != 1.0,
                   (try? dev.lockForConfiguration()) != nil {
                    restoreZoomAfterRaw = dev.videoZoomFactor
                    dev.videoZoomFactor = 1.0
                    dev.unlockForConfiguration()
                }
                if let conn = self.photoOutput.connection(with: .video), conn.videoScaleAndCropFactor != 1.0 {
                    conn.videoScaleAndCropFactor = 1.0
                }
                settings = AVCapturePhotoSettings(
                    rawPixelFormatType: rawFormat,
                    processedFormat: [AVVideoCodecKey: AVVideoCodecType.jpeg]
                )
                settings.photoQualityPrioritization = .speed
                settings.isHighResolutionPhotoEnabled = true
            } else {
                settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
                settings.isHighResolutionPhotoEnabled = true
                if #available(iOS 16.0, *) {
                    settings.photoQualityPrioritization = .quality
                }
            }

            if self.photoOutput.supportedFlashModes.contains(.on) && flash == .on {
                settings.flashMode = .on
            } else if self.photoOutput.supportedFlashModes.contains(.auto) && flash == .auto {
                settings.flashMode = .auto
            } else if self.photoOutput.supportedFlashModes.contains(.off) {
                settings.flashMode = .off
            }

            let devIso = Int((self.videoDevice?.iso ?? 100).rounded())
            let devSec = CMTimeGetSeconds(self.videoDevice?.exposureDuration ?? CMTimeMake(value: 1, timescale: 125))
            let meta: [String: Any] = [
                "iso": devIso,
                "exposureSeconds": devSec,
                "aperture": self.apertureValue,
                "flash": flash.label
            ]

            let uid = settings.uniqueID
            let capturedRestoreZoom = restoreZoomAfterRaw
            let handler = PhotoCaptureHandler(uid: uid) { [weak self] rawData, processedData in
                if let self = self {
                    self.sessionQueue.async {
                        self.bayerCaptureInFlight = false
                        if let prevZ = capturedRestoreZoom, let dev = self.videoDevice,
                           (try? dev.lockForConfiguration()) != nil {
                            let maxZ = min(5.0, dev.activeFormat.videoMaxZoomFactor)
                            dev.videoZoomFactor = max(1.0, min(maxZ, prevZ))
                            dev.unlockForConfiguration()
                        }
                    }
                }
                DispatchQueue.global(qos: .userInitiated).async {
                    for (idx, pair) in allFilmsToDevelop.enumerated() {
                        if let out = GrainDeveloper.develop(
                            rawDngData: rawData,
                            fallbackImageData: processedData,
                            film: pair.0,
                            tuning: pair.1,
                            aspectRatio: ratio,
                            userEv: ev,
                            captureMeta: meta,
                            digitalZoom: reqZoom
                        ) {
                            PhotoStore.shared.saveCapture(
                                jpegData: out.jpegData,
                                rawDngData: idx == 0 ? rawData : nil,
                                keepRaw: keepRaw && idx == 0,
                                receipt: out.receipt
                            )
                        }
                    }
                    Task { @MainActor in
                        self?.isCapturing = false
                        self?.inFlightDelegates.removeValue(forKey: uid)
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        onDone?()
                    }
                }
            }
            Task { @MainActor in
                self.inFlightDelegates[uid] = handler
            }
            self.photoOutput.capturePhoto(with: settings, delegate: handler)
        }
    }

    // MARK: - Telemetry, Horizon Level & Preferences

    private func startMotionUpdates() {
        guard motionManager.isDeviceMotionAvailable else { return }
        motionManager.deviceMotionUpdateInterval = 1.0 / 24.0
        motionManager.startDeviceMotionUpdates(to: .main) { [weak self] motion, _ in
            guard let self = self, let g = motion?.gravity else { return }
            let angle = atan2(g.x, -g.y) * 180.0 / .pi
            self.rollDegrees = angle
        }
    }

    private func startTelemetryTimer() {
        meterTimer?.invalidate()
        meterTimer = Timer.scheduledTimer(withTimeInterval: 0.22, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self = self, let dev = self.videoDevice else { return }
                let sec = CMTimeGetSeconds(dev.exposureDuration)
                let iso = Int(dev.iso.rounded())
                if sec > 0 && iso > 0 {
                    if self.shutterIndex == 0 && self.isoIndex == 0 && !self.aeLocked {
                        self.lastSceneProduct = sec * Double(iso) / pow(2.0, Double(self.effectiveEv))
                    }
                    let shownSec = self.shutterIndex > 0 ? ExposureLadder.shutterStops[self.shutterIndex].seconds : sec
                    let shownIso = self.isoIndex > 0 ? ExposureLadder.isoStops[self.isoIndex] : iso
                    self.liveShutterText = ExposureLadder.formatShutter(shownSec)
                    self.liveIsoText = "\(shownIso)"
                }
                if dev.isAdjustingFocus {
                    self.afState = 1
                } else if self.afState == 1 {
                    self.afState = 2
                }
            }
        }
    }

    public func showToast(_ title: String, subtitle: String? = nil) {
        toastWork?.cancel()
        toastTitle = title
        toastSubtitle = subtitle
        let work = DispatchWorkItem { [weak self] in
            self?.toastTitle = nil
            self?.toastSubtitle = nil
        }
        toastWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8, execute: work)
    }

    public func savePreferences() {
        let d = UserDefaults.standard
        d.set(filmIndex, forKey: "grain.filmIndex")
        d.set(aspectRatio, forKey: "grain.aspectRatio")
        d.set(showGrid, forKey: "grain.showGrid")
        d.set(showHistogram, forKey: "grain.showHistogram")
        d.set(showLevel, forKey: "grain.showLevel")
        d.set(keepRawDng, forKey: "grain.keepRawDng")
        d.set(bodyFinish, forKey: "grain.bodyFinish")
        d.set(focusPeaking, forKey: "grain.focusPeaking")
        d.set(magnifierSetting.rawValue, forKey: "grain.magnifier")
        d.set(afArea.rawValue, forKey: "grain.afArea")
        d.set(focusMode.rawValue, forKey: "grain.focusMode")
        d.set(driveSetting.rawValue, forKey: "grain.driveSetting")
        d.set(rollNote, forKey: "grain.rollNote")
        if let data = try? JSONEncoder().encode(tunings) {
            d.set(data, forKey: "grain.tunings")
        }
    }

    private func loadPreferences() {
        let d = UserDefaults.standard
        if d.object(forKey: "grain.filmIndex") != nil {
            filmIndex = max(0, min(FilmCatalog.all.count - 1, d.integer(forKey: "grain.filmIndex")))
        }
        aspectRatio = d.string(forKey: "grain.aspectRatio") ?? "3:4"
        showGrid = d.bool(forKey: "grain.showGrid")
        showHistogram = d.object(forKey: "grain.showHistogram") == nil ? true : d.bool(forKey: "grain.showHistogram")
        showLevel = d.object(forKey: "grain.showLevel") == nil ? true : d.bool(forKey: "grain.showLevel")
        keepRawDng = d.object(forKey: "grain.keepRawDng") == nil ? true : d.bool(forKey: "grain.keepRawDng")
        bodyFinish = d.integer(forKey: "grain.bodyFinish")
        focusPeaking = d.bool(forKey: "grain.focusPeaking")
        magnifierSetting = MagnifierSetting(rawValue: d.integer(forKey: "grain.magnifier")) ?? .hold4x
        afArea = AfAreaSetting(rawValue: d.integer(forKey: "grain.afArea")) ?? .single
        focusMode = FocusModeSetting(rawValue: d.integer(forKey: "grain.focusMode")) ?? .afc
        driveSetting = DriveSetting(rawValue: d.integer(forKey: "grain.driveSetting")) ?? .single
        rollNote = d.string(forKey: "grain.rollNote") ?? ""
        if let data = d.data(forKey: "grain.tunings"),
           let decoded = try? JSONDecoder().decode([String: FilmTuning].self, from: data) {
            tunings = decoded
        }
    }
}

// MARK: - Live Video Sample Buffer (Histogram, 4x/8x Magnifier Loupe & Focus Peaking) & Face Metadata

extension GrainCaptureController: AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureMetadataOutputObjectsDelegate {
    nonisolated public func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }

        let w = CVPixelBufferGetWidth(pb)
        let h = CVPixelBufferGetHeight(pb)
        let bpr = CVPixelBufferGetBytesPerRow(pb)
        guard w > 32, h > 32, let base = CVPixelBufferGetBaseAddress(pb) else { return }
        let ptr = base.assumingMemoryBound(to: UInt8.self)

        // Subsampled 32-bin luma histogram every ~4th frame
        var bins = [Int](repeating: 0, count: 32)
        var samples = 0
        for y in stride(from: 0, to: h, by: 16) {
            let row = y * bpr
            for x in stride(from: 0, to: w, by: 16) {
                let p = row + x * 4
                let b = Int(ptr[p]), g = Int(ptr[p + 1]), r = Int(ptr[p + 2])
                let luma = (r * 77 + g * 150 + b * 29) >> 8
                bins[min(31, luma >> 3)] += 1
                samples += 1
            }
        }
        let maxBin = max(1, bins.max() ?? 1)
        let normHist = bins.map { Float($0) / Float(maxBin) }

        Task { @MainActor in
            self.frameCounter &+= 1
            if self.frameCounter % 3 == 0 {
                self.histogram = normHist
            }
            let wantMag = self.magnifierSetting == .always4x
                || (self.magnifierHeld && self.magnifierSetting != .off)
            if wantMag && self.frameCounter % 2 == 0 {
                let factor = CGFloat(self.magnifierSetting.factor)
                let pt = self.afArea == .center ? CGPoint(x: 0.5, y: 0.5) : self.afPoint
                let ci = CIImage(cvPixelBuffer: pb).oriented(.right)
                let ext = ci.extent
                let cropW = max(32, ext.width / factor * 0.42)
                let cropH = cropW
                let cx = ext.minX + pt.x * ext.width
                let cy = ext.minY + (1.0 - pt.y) * ext.height
                let rect = CGRect(
                    x: max(ext.minX, min(ext.maxX - cropW, cx - cropW * 0.5)),
                    y: max(ext.minY, min(ext.maxY - cropH, cy - cropH * 0.5)),
                    width: cropW,
                    height: cropH
                )
                let cropped = ci.cropped(to: rect)
                let ctx = CIContext(options: [.useSoftwareRenderer: false])
                if let cg = ctx.createCGImage(cropped, from: rect) {
                    self.magnifierPatch = UIImage(cgImage: cg)
                }
            } else if !wantMag && self.magnifierPatch != nil {
                self.magnifierPatch = nil
            }
        }
    }

    nonisolated public func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        let faces = metadataObjects.compactMap { $0 as? AVMetadataFaceObject }
        Task { @MainActor in
            guard let f = faces.first else {
                self.trackedFaceRect = nil
                return
            }
            let b = f.bounds
            // Sensor landscape bounds -> upright normalized portrait bounds
            let upright = CGRect(
                x: max(0, min(1, 1.0 - b.maxY)),
                y: max(0, min(1, b.minX)),
                width: max(0.05, min(1, b.height)),
                height: max(0.05, min(1, b.width))
            )
            self.trackedFaceRect = upright
        }
    }
}

private final class PhotoCaptureHandler: NSObject, AVCapturePhotoCaptureDelegate {
    private let uid: Int64
    private let completion: (Data?, Data?) -> Void
    private var rawData: Data?
    private var processedData: Data?

    init(uid: Int64, completion: @escaping (Data?, Data?) -> Void) {
        self.uid = uid
        self.completion = completion
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        guard error == nil, let data = photo.fileDataRepresentation() else { return }
        if photo.isRawPhoto {
            rawData = data
        } else {
            processedData = data
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        completion(rawData, processedData)
    }
}
