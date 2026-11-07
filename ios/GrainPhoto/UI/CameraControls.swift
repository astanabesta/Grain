import SwiftUI
import AVFoundation
import UIKit

public enum GrainPalette {
    public static let orange = Color(red: 0.886, green: 0.384, blue: 0.184)
    public static let green = Color(red: 0.388, green: 0.827, blue: 0.431)
    public static let bodyDark = Color(red: 0.110, green: 0.114, blue: 0.102)
    public static let warmIvory = Color(red: 0.914, green: 0.898, blue: 0.855)
    public static let mutedEngrave = Color(red: 0.659, green: 0.647, blue: 0.608)

    public static func plateGradient(finish: Int) -> LinearGradient {
        switch finish {
        case 1: // Black
            return LinearGradient(
                colors: [
                    Color(red: 0.235, green: 0.239, blue: 0.227),
                    Color(red: 0.145, green: 0.149, blue: 0.137),
                    Color(red: 0.184, green: 0.188, blue: 0.176)
                ],
                startPoint: .top, endPoint: .bottom
            )
        case 2: // Graphite
            return LinearGradient(
                colors: [
                    Color(red: 0.576, green: 0.580, blue: 0.565),
                    Color(red: 0.396, green: 0.400, blue: 0.384),
                    Color(red: 0.486, green: 0.490, blue: 0.475)
                ],
                startPoint: .top, endPoint: .bottom
            )
        default: // Silver
            return LinearGradient(
                colors: [
                    Color(red: 0.957, green: 0.953, blue: 0.933),
                    Color(red: 0.808, green: 0.804, blue: 0.776),
                    Color(red: 0.886, green: 0.882, blue: 0.859)
                ],
                startPoint: .top, endPoint: .bottom
            )
        }
    }

    public static func plateInk(finish: Int) -> Color {
        finish == 0 ? Color(red: 0.227, green: 0.235, blue: 0.216) : Color(red: 0.894, green: 0.878, blue: 0.839)
    }

    public static func hexColor(_ hex: UInt32) -> Color {
        let r = Double((hex >> 16) & 0xff) / 255.0
        let g = Double((hex >> 8) & 0xff) / 255.0
        let b = Double(hex & 0xff) / 255.0
        return Color(red: r, green: g, blue: b)
    }
}

// MARK: - Live AVCaptureVideoPreviewLayer UIViewRepresentable

public struct CameraPreviewSurface: UIViewRepresentable {
    @ObservedObject var camera: GrainCaptureController

    public init(camera: GrainCaptureController) {
        self.camera = camera
    }

    public func makeUIView(context: Context) -> PreviewHostUIView {
        let view = PreviewHostUIView()
        view.previewLayer.session = camera.session
        view.previewLayer.videoGravity = .resizeAspectFill
        camera.installVolumeShutterObserver(in: view)
        return view
    }

    public func updateUIView(_ uiView: PreviewHostUIView, context: Context) {
        if uiView.previewLayer.session !== camera.session {
            uiView.previewLayer.session = camera.session
        }
    }

    public final class PreviewHostUIView: UIView {
        public override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        public var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}

// MARK: - Hardware Knurled Drum Dial

public struct DrumDialView: View {
    let items: [String]
    @Binding var selectedIndex: Int
    let finish: Int
    let accentFirstItem: Bool
    let accentFromIndex: Int?
    let onChanged: (Int) -> Void

    @State private var dragStartIdx: Int?

    public init(
        items: [String],
        selectedIndex: Binding<Int>,
        finish: Int,
        accentFirstItem: Bool = false,
        accentFromIndex: Int? = nil,
        onChanged: @escaping (Int) -> Void
    ) {
        self.items = items
        self._selectedIndex = selectedIndex
        self.finish = finish
        self.accentFirstItem = accentFirstItem
        self.accentFromIndex = accentFromIndex
        self.onChanged = onChanged
    }

    public var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height
            let stepPx: CGFloat = max(28, min(42, w / 5.2))

            ZStack(alignment: .top) {
                // Orange index triangle above drum
                TrianglePointer()
                    .fill(GrainPalette.orange)
                    .frame(width: 7, height: 4.5)
                    .offset(y: -6)

                ZStack {
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: finish == 0
                                    ? [Color(white: 0.44), Color(white: 0.72), Color(white: 0.46)]
                                    : [Color(white: 0.10), Color(white: 0.28), Color(white: 0.11)],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .stroke(Color.black.opacity(0.65), lineWidth: 1)
                        )

                    // Knurled vertical teeth along top & bottom edges
                    Canvas { ctx, size in
                        var x: CGFloat = 5
                        while x < size.width - 4 {
                            let rectTop = CGRect(x: x, y: 1.5, width: 1.1, height: 4.5)
                            let rectBot = CGRect(x: x, y: size.height - 6.0, width: 1.1, height: 4.5)
                            ctx.fill(Path(rectTop), with: .color(Color.black.opacity(0.32)))
                            ctx.fill(Path(rectBot), with: .color(Color.black.opacity(0.32)))
                            x += 3.6
                        }
                    }

                    // Visible neighboring stops around selectedIndex
                    ForEach(-3...3, id: \.self) { offset in
                        let idx = (selectedIndex + offset + items.count * 10) % max(1, items.count)
                        let isSelected = offset == 0
                        let isAccent = (accentFirstItem && idx == 0)
                            || (accentFromIndex != nil && idx >= accentFromIndex!)
                        Text(items[idx])
                            .font(.custom("AvenirNextCondensed-Bold", size: isSelected ? 13.5 : 11.0))
                            .foregroundColor(
                                isAccent
                                    ? GrainPalette.orange
                                    : (finish == 0 ? Color(white: 0.14) : GrainPalette.warmIvory)
                            )
                            .opacity(isSelected ? 1.0 : max(0.18, 0.85 - Double(abs(offset)) * 0.24))
                            .scaleEffect(isSelected ? 1.0 : 0.90)
                            .offset(x: CGFloat(offset) * stepPx)
                    }
                }
                .frame(width: w, height: h)
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 2)
                        .onChanged { value in
                            if dragStartIdx == nil {
                                dragStartIdx = selectedIndex
                            }
                            let deltaSteps = Int((-value.translation.width / 22.0).rounded())
                            let base = dragStartIdx ?? selectedIndex
                            let count = max(1, items.count)
                            let next = ((base + deltaSteps) % count + count) % count
                            if next != selectedIndex {
                                selectedIndex = next
                                UISelectionFeedbackGenerator().selectionChanged()
                                onChanged(next)
                            }
                        }
                        .onEnded { _ in
                            dragStartIdx = nil
                        }
                )
                .onTapGesture {
                    let count = max(1, items.count)
                    let next = (selectedIndex + 1) % count
                    selectedIndex = next
                    UISelectionFeedbackGenerator().selectionChanged()
                    onChanged(next)
                }
            }
        }
    }
}

private struct TrianglePointer: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.minX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
        p.closeSubpath()
        return p
    }
}

// MARK: - Rear Knurled Command Wheel

public struct CommandWheelView: View {
    let isContinuous: () -> Bool
    let onSlide: (CGFloat) -> Void
    let onStep: (Int) -> Void
    @State private var accum: CGFloat = 0
    @State private var prevX: CGFloat = 0

    public init(
        isContinuous: @escaping () -> Bool = { false },
        onSlide: @escaping (CGFloat) -> Void = { _ in },
        onStep: @escaping (Int) -> Void
    ) {
        self.isContinuous = isContinuous
        self.onSlide = onSlide
        self.onStep = onStep
    }

    public var body: some View {
        GeometryReader { geo in
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Color(white: 0.045))
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [Color(white: 0.08), Color(white: 0.24), Color(white: 0.08)],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .padding(3)
                Canvas { ctx, size in
                    var x: CGFloat = 8
                    while x < size.width - 7 {
                        let r = CGRect(x: x, y: 5, width: 1.5, height: size.height - 10)
                        ctx.fill(Path(r), with: .color(Color.white.opacity(0.18)))
                        x += 4.5
                    }
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { val in
                        let dx = val.translation.width - prevX
                        prevX = val.translation.width
                        if isContinuous() {
                            if abs(dx) > 0.01 {
                                onSlide(dx)
                            }
                            let delta = val.translation.width - accum
                            if abs(delta) >= 20 {
                                let steps = Int((delta / 20).rounded(.towardZero))
                                accum += CGFloat(steps) * 20
                                UISelectionFeedbackGenerator().selectionChanged()
                            }
                        } else {
                            let delta = val.translation.width - accum
                            if abs(delta) >= 16 {
                                let steps = Int((delta / 16).rounded(.towardZero))
                                if steps != 0 {
                                    accum += CGFloat(steps) * 16
                                    UISelectionFeedbackGenerator().selectionChanged()
                                    onStep(steps)
                                }
                            }
                        }
                    }
                    .onEnded { val in
                        let totalMove = abs(val.translation.width) + abs(val.translation.height)
                        if totalMove < 4 && geo.size.width > 0 {
                            UISelectionFeedbackGenerator().selectionChanged()
                            onStep(val.location.x < geo.size.width * 0.5 ? -1 : 1)
                        }
                        accum = 0
                        prevX = 0
                    }
            )
        }
    }
}

// MARK: - Enlarged 360-Degree VSync Analog Focus Lever (Joystick)

public struct AnalogFocusLeverView: UIViewRepresentable {
    let isAnalogMode: () -> Bool
    let onAnalogGlide: (CGFloat, CGFloat, CGFloat, Bool) -> Void
    let onDiscreteKey: (Int) -> Void // 0 center, 1 up, 2 right, 3 down, 4 left

    public init(
        isAnalogMode: @escaping () -> Bool,
        onAnalogGlide: @escaping (CGFloat, CGFloat, CGFloat, Bool) -> Void,
        onDiscreteKey: @escaping (Int) -> Void
    ) {
        self.isAnalogMode = isAnalogMode
        self.onAnalogGlide = onAnalogGlide
        self.onDiscreteKey = onDiscreteKey
    }

    public func makeUIView(context: Context) -> AnalogLeverUIView {
        let v = AnalogLeverUIView()
        v.isAnalogMode = isAnalogMode
        v.onAnalogGlide = onAnalogGlide
        v.onDiscreteKey = onDiscreteKey
        return v
    }

    public func updateUIView(_ uiView: AnalogLeverUIView, context: Context) {
        uiView.isAnalogMode = isAnalogMode
        uiView.onAnalogGlide = onAnalogGlide
        uiView.onDiscreteKey = onDiscreteKey
    }
}

public final class AnalogLeverUIView: UIView {
    var isAnalogMode: (() -> Bool)?
    var onAnalogGlide: ((CGFloat, CGFloat, CGFloat, Bool) -> Void)?
    var onDiscreteKey: ((Int) -> Void)?

    private var origin: CGPoint = .zero
    private var vis: CGPoint = .zero
    private var vel: CGPoint = .zero
    private var peakVel: CGPoint = .zero
    private var touching = false
    private var centerHeld = false
    private var glided = false
    private var engagedHaptic = false
    private var cardinalDir = -1
    private var repeatCount = 0
    private var glideFrames = 0
    private var downTime: TimeInterval = 0
    private var lastDisplayTimestamp: TimeInterval = 0

    private var displayLink: CADisplayLink?
    private var repeatTimer: Timer?
    private let tickGen = UISelectionFeedbackGenerator()
    private let clickGen = UIImpactFeedbackGenerator(style: .light)

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        isMultipleTouchEnabled = false
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    public override func willMove(toWindow newWindow: UIWindow?) {
        super.willMove(toWindow: newWindow)
        if newWindow == nil {
            stopLoops()
        }
    }

    private func stopLoops() {
        displayLink?.invalidate()
        displayLink = nil
        lastDisplayTimestamp = 0
        repeatTimer?.invalidate()
        repeatTimer = nil
    }

    private func ensureDisplayLink() {
        guard displayLink == nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(onDisplayFrame(_:)))
        if #available(iOS 15.0, *) {
            link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        }
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    @objc private func onDisplayFrame(_ link: CADisplayLink) {
        guard touching, isAnalogMode?() == true else {
            stopLoops()
            return
        }
        let dt: CGFloat
        if lastDisplayTimestamp == 0 {
            dt = 1.0 / 60.0
        } else {
            dt = CGFloat(max(0.001, min(0.05, link.timestamp - lastDisplayTimestamp)))
        }
        lastDisplayTimestamp = link.timestamp

        if hypot(vel.x, vel.y) > 1e-4 {
            glideFrames += 1
            onAnalogGlide?(vel.x, vel.y, dt, false)
        } else {
            displayLink?.invalidate()
            displayLink = nil
            lastDisplayTimestamp = 0
        }
    }

    private func scheduleRepeat() {
        repeatTimer?.invalidate()
        repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.26, repeats: false) { [weak self] _ in
            self?.fireRepeatTick()
        }
    }

    private func fireRepeatTick() {
        guard touching, cardinalDir > 0, isAnalogMode?() != true else { return }
        repeatCount += 1
        tickGen.selectionChanged()
        onDiscreteKey?(cardinalDir)
        let nextDelay = repeatCount >= 6 ? 0.075 : 0.108
        repeatTimer = Timer.scheduledTimer(withTimeInterval: nextDelay, repeats: false) { [weak self] _ in
            self?.fireRepeatTick()
        }
    }

    private func updateStick(touch: CGPoint) {
        let r = min(bounds.width, bounds.height) * 0.5
        let maxTravel = max(8.0, r * 0.45)
        origin = FocusLeverMath.floatingOrigin(origin: origin, touch: touch, leashRadius: maxTravel * 1.30)
        let resp = FocusLeverMath.response(
            dx: touch.x - origin.x,
            dy: touch.y - origin.y,
            maxRadius: maxTravel,
            deadzone: 0.13
        )
        vel = CGPoint(x: resp.vx, y: resp.vy)
        vis = CGPoint(x: resp.visX, y: resp.visY)
        if resp.speed > hypot(peakVel.x, peakVel.y) {
            peakVel = vel
        }
        if resp.speed > 0 {
            centerHeld = false
        }

        if isAnalogMode?() == true {
            repeatTimer?.invalidate()
            cardinalDir = -1
            if resp.speed > 0 {
                if !engagedHaptic {
                    engagedHaptic = true
                    tickGen.selectionChanged()
                }
                glided = true
                ensureDisplayLink()
            }
        } else {
            let dir = FocusLeverMath.cardinal(nx: vis.x, ny: vis.y, threshold: 0.32, current: cardinalDir)
            if dir != cardinalDir {
                repeatTimer?.invalidate()
                cardinalDir = dir
                if dir > 0 {
                    glided = true
                    repeatCount = 0
                    tickGen.selectionChanged()
                    onDiscreteKey?(dir)
                    scheduleRepeat()
                }
            }
        }
        setNeedsDisplay()
    }

    public override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touches.first else { return }
        stopLoops()
        let pt = t.location(in: self)
        touching = true
        downTime = CACurrentMediaTime()
        glided = false
        engagedHaptic = false
        cardinalDir = -1
        repeatCount = 0
        glideFrames = 0
        vel = .zero
        peakVel = .zero

        let cx = bounds.midX, cy = bounds.midY
        let r = min(bounds.width, bounds.height) * 0.5
        let d0 = hypot(pt.x - cx, pt.y - cy)
        if d0 <= r * 0.57 {
            origin = pt
            centerHeld = true
            vis = CGPoint(x: (pt.x - cx) / max(1, r) * 0.14, y: (pt.y - cy) / max(1, r) * 0.14)
            clickGen.impactOccurred()
            setNeedsDisplay()
        } else {
            origin = CGPoint(x: cx, y: cy)
            centerHeld = false
            updateStick(touch: pt)
        }
    }

    public override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard touching, let t = touches.first else { return }
        updateStick(touch: t.location(in: self))
    }

    public override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        let wasTouching = touching
        touching = false
        stopLoops()
        let heldSec = CACurrentMediaTime() - downTime
        if wasTouching {
            if isAnalogMode?() == true {
                if glided {
                    let pk = hypot(peakVel.x, peakVel.y)
                    if heldSec < 0.20 && glideFrames <= 2 && pk > 1e-4 {
                        onAnalogGlide?(peakVel.x / pk, peakVel.y / pk, 0.068, true)
                    } else {
                        onAnalogGlide?(0, 0, 0, true)
                    }
                } else {
                    clickGen.impactOccurred()
                    onDiscreteKey?(0)
                }
            } else if !glided {
                clickGen.impactOccurred()
                onDiscreteKey?(0)
            }
        }
        vel = .zero
        peakVel = .zero
        centerHeld = false
        cardinalDir = -1
        springReturn()
    }

    public override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        if touching && glided && isAnalogMode?() == true {
            onAnalogGlide?(0, 0, 0, true)
        }
        touching = false
        stopLoops()
        vel = .zero
        peakVel = .zero
        centerHeld = false
        cardinalDir = -1
        springReturn()
    }

    private func springReturn() {
        let startVis = vis
        guard hypot(startVis.x, startVis.y) > 0.01 else {
            vis = .zero
            setNeedsDisplay()
            return
        }
        let begin = CACurrentMediaTime()
        let dur = 0.15
        Timer.scheduledTimer(withTimeInterval: 1.0 / 60.0, repeats: true) { [weak self] timer in
            guard let self = self, !self.touching else { timer.invalidate(); return }
            let t = min(1.0, (CACurrentMediaTime() - begin) / dur)
            // Damped spring curve with subtle mechanical overshoot
            let inv = 1.0 - t
            let k = CGFloat(inv * inv * cos(t * .pi * 0.85))
            self.vis = CGPoint(x: startVis.x * k, y: startVis.y * k)
            self.setNeedsDisplay()
            if t >= 1.0 {
                self.vis = .zero
                self.setNeedsDisplay()
                timer.invalidate()
            }
        }
    }

    public override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let w = bounds.width, h = bounds.height
        let r = min(w, h) * 0.5
        let cx = w * 0.5, cy = h * 0.5
        guard r > 4 else { return }
        let cs = CGColorSpaceCreateDeviceRGB()

        // 1. Machined outer anodized collar
        ctx.saveGState()
        ctx.addArc(center: CGPoint(x: cx, y: cy), radius: r, startAngle: 0, endAngle: .pi * 2, clockwise: false)
        ctx.clip()
        if let grad = CGGradient(
            colorsSpace: cs,
            colors: [
                UIColor(red: 0.24, green: 0.245, blue: 0.225, alpha: 1).cgColor,
                UIColor(red: 0.045, green: 0.048, blue: 0.042, alpha: 1).cgColor
            ] as CFArray,
            locations: [0, 1]
        ) {
            ctx.drawRadialGradient(
                grad,
                startCenter: CGPoint(x: cx - r * 0.26, y: cy - r * 0.30),
                startRadius: 0,
                endCenter: CGPoint(x: cx, y: cy),
                endRadius: r * 1.25,
                options: [.drawsAfterEndLocation]
            )
        }
        ctx.restoreGState()

        ctx.setStrokeColor(UIColor(white: 1.0, alpha: 0.16).cgColor)
        ctx.setLineWidth(1.0)
        ctx.strokeEllipse(in: CGRect(x: cx - r + 0.5, y: cy - r + 0.5, width: (r - 0.5) * 2, height: (r - 0.5) * 2))

        // 2. Recessed gimbal socket well
        let wellR = r * 0.68
        ctx.setFillColor(UIColor(white: 0.022, alpha: 1).cgColor)
        ctx.fillEllipse(in: CGRect(x: cx - wellR, y: cy - wellR, width: wellR * 2, height: wellR * 2))

        // 3. Directional chevrons with warm active illumination
        let tr = r * 0.83
        let s = max(2.8, r * 0.082)
        let aligns: [CGFloat] = [-vis.y, vis.x, vis.y, -vis.x]
        for d in 0..<4 {
            let t = max(0, min(1, (aligns[d] - 0.18) / 0.65))
            let cr = 0.596 + (0.886 - 0.596) * t
            let cg = 0.580 + (0.384 - 0.580) * t
            let cb = 0.541 + (0.184 - 0.541) * t
            ctx.saveGState()
            ctx.translateBy(x: cx, y: cy)
            ctx.rotate(by: CGFloat(d) * (.pi / 2))
            ctx.beginPath()
            ctx.move(to: CGPoint(x: 0, y: -tr - s * 0.95))
            ctx.addLine(to: CGPoint(x: -s, y: -tr + s * 0.65))
            ctx.addLine(to: CGPoint(x: s, y: -tr + s * 0.65))
            ctx.closePath()
            ctx.setFillColor(UIColor(red: cr, green: cg, blue: cb, alpha: 1).cgColor)
            ctx.fillPath()
            ctx.restoreGState()
        }

        // 4. Analog stem + floating drop shadow
        let maxShift = r * 0.28
        let nx = cx + vis.x * maxShift
        let ny = cy + vis.y * maxShift
        let nr = r * (centerHeld ? 0.49 : 0.54)

        ctx.setStrokeColor(UIColor(red: 0.07, green: 0.075, blue: 0.068, alpha: 1).cgColor)
        ctx.setLineCap(.round)
        ctx.setLineWidth(nr * 0.68)
        ctx.move(to: CGPoint(x: cx, y: cy))
        ctx.addLine(to: CGPoint(x: nx, y: ny))
        ctx.strokePath()

        ctx.setFillColor(UIColor(white: 0, alpha: 0.42).cgColor)
        ctx.fillEllipse(in: CGRect(x: nx - nr * 1.03 + 1.2, y: ny - nr * 1.03 + 2.2, width: nr * 2.06, height: nr * 2.06))

        // 5. Concave micro-knurled Fujifilm thumb cap
        ctx.saveGState()
        ctx.addArc(center: CGPoint(x: nx, y: ny), radius: nr, startAngle: 0, endAngle: .pi * 2, clockwise: false)
        ctx.clip()
        if let capGrad = CGGradient(
            colorsSpace: cs,
            colors: [
                UIColor(red: 0.41, green: 0.42, blue: 0.39, alpha: 1).cgColor,
                UIColor(red: 0.086, green: 0.090, blue: 0.082, alpha: 1).cgColor
            ] as CFArray,
            locations: [0, 1]
        ) {
            ctx.drawRadialGradient(
                capGrad,
                startCenter: CGPoint(x: nx - nr * 0.32, y: ny - nr * 0.38),
                startRadius: 0,
                endCenter: CGPoint(x: nx, y: ny),
                endRadius: nr * 1.45,
                options: [.drawsAfterEndLocation]
            )
        }
        ctx.restoreGState()

        ctx.setStrokeColor(UIColor(white: 1.0, alpha: 0.21).cgColor)
        ctx.setLineWidth(0.9)
        ctx.strokeEllipse(in: CGRect(x: nx - nr + 0.5, y: ny - nr + 0.5, width: (nr - 0.5) * 2, height: (nr - 0.5) * 2))

        // Micro-knurling grip studs (inner 6 + outer 12)
        for ring in 0..<2 {
            let count = ring == 0 ? 6 : 12
            let kr = nr * (ring == 0 ? 0.34 : 0.62)
            let dotR = max(0.9, r * (ring == 0 ? 0.026 : 0.022))
            let phase = ring == 0 ? 0.0 : (.pi / 12.0)
            for i in 0..<count {
                let a = phase + Double(i) * (2.0 * .pi / Double(count))
                let kx = nx + CGFloat(cos(a)) * kr
                let ky = ny + CGFloat(sin(a)) * kr
                ctx.setFillColor(UIColor(white: 0, alpha: 0.45).cgColor)
                ctx.fillEllipse(in: CGRect(x: kx - dotR + 0.4, y: ky - dotR + 0.5, width: dotR * 2, height: dotR * 2))
                ctx.setFillColor(UIColor(white: 1, alpha: 0.16).cgColor)
                ctx.fillEllipse(in: CGRect(x: kx - dotR * 0.75 - 0.25, y: ky - dotR * 0.75 - 0.25, width: dotR * 1.5, height: dotR * 1.5))
            }
        }
    }
}

// MARK: - Shutter Release, Film Memo Holder & Hardware Keys

public struct ShutterReleaseButton: View {
    let countdown: String
    let busy: Bool
    let action: () -> Void

    public init(countdown: String, busy: Bool, action: @escaping () -> Void) {
        self.countdown = countdown
        self.busy = busy
        self.action = action
    }

    public var body: some View {
        Button(action: {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            action()
        }) {
            GeometryReader { geo in
                let d = min(geo.size.width, geo.size.height)
                ZStack {
                    Circle()
                        .fill(
                            RadialGradient(
                                colors: [Color(white: 0.28), Color(white: 0.12)],
                                center: .topLeading,
                                startRadius: 2,
                                endRadius: d
                            )
                        )
                    Circle()
                        .fill(Color(white: 0.05))
                        .frame(width: d * 0.78, height: d * 0.78)
                    Circle()
                        .fill(
                            RadialGradient(
                                colors: [
                                    Color(red: 0.96, green: 0.48, blue: 0.26),
                                    Color(red: 0.76, green: 0.27, blue: 0.11)
                                ],
                                center: UnitPoint(x: 0.35, y: 0.32),
                                startRadius: 2,
                                endRadius: d * 0.52
                            )
                        )
                        .frame(width: d * 0.68, height: d * 0.68)
                        .shadow(color: .black.opacity(0.45), radius: 3, y: 2)

                    if !countdown.isEmpty {
                        Text(countdown)
                            .font(.custom("AvenirNextCondensed-Bold", size: d * 0.26))
                            .foregroundColor(.white)
                    } else if busy {
                        Circle()
                            .fill(Color.white.opacity(0.85))
                            .frame(width: 6, height: 6)
                    }
                }
                .frame(width: d, height: d)
            }
        }
        .buttonStyle(.plain)
    }
}

public struct FilmMemoBoxView: View {
    let film: GrainFilm
    let action: () -> Void

    public init(film: GrainFilm, action: @escaping () -> Void) {
        self.film = film
        self.action = action
    }

    public var body: some View {
        Button(action: {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        }) {
            ZStack {
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .fill(Color(white: 0.06))
                    .overlay(
                        RoundedRectangle(cornerRadius: 7, style: .continuous)
                            .stroke(Color.white.opacity(0.14), lineWidth: 1)
                    )

                VStack(spacing: 4) {
                    Text("GRAIN FILM")
                        .font(.custom("AvenirNextCondensed-Bold", size: 7.5))
                        .tracking(0.8)
                        .foregroundColor(.white.opacity(0.82))

                    Spacer(minLength: 2)

                    Text(film.name)
                        .font(.custom("Georgia-Bold", size: 11))
                        .foregroundColor(.white)
                        .lineLimit(2)
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.72)

                    Spacer(minLength: 2)

                    Rectangle()
                        .fill(Color.white.opacity(0.85))
                        .frame(height: 3)
                        .padding(.horizontal, 5)

                    Text("\(film.code) · \(film.isRawPipeline ? "RAW" : "ISP")")
                        .font(.custom("AvenirNextCondensed-Bold", size: 8.5))
                        .foregroundColor(.white.opacity(0.92))
                }
                .padding(6)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(
                    RoundedRectangle(cornerRadius: 4.5, style: .continuous)
                        .fill(GrainPalette.hexColor(film.boxHex))
                )
                .padding(4)
            }
        }
        .buttonStyle(.plain)
    }
}

public struct HardwareKeyButton: View {
    let label: String
    let round: Bool
    let active: Bool
    let fontSize: CGFloat
    let action: () -> Void

    public init(
        _ label: String,
        round: Bool = false,
        active: Bool = false,
        fontSize: CGFloat = 10.5,
        action: @escaping () -> Void
    ) {
        self.label = label
        self.round = round
        self.active = active
        self.fontSize = fontSize
        self.action = action
    }

    public var body: some View {
        Button(action: {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        }) {
            Text(label)
                .font(.custom("AvenirNextCondensed-Bold", size: fontSize))
                .tracking(0.5)
                .multilineTextAlignment(.center)
                .foregroundColor(active ? .white : GrainPalette.warmIvory)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(
                    Group {
                        if round {
                            Circle().fill(buttonGradient)
                                .overlay(Circle().stroke(Color.black.opacity(0.85), lineWidth: 1))
                        } else {
                            Capsule().fill(buttonGradient)
                                .overlay(Capsule().stroke(Color.black.opacity(0.85), lineWidth: 1))
                        }
                    }
                )
        }
        .buttonStyle(.plain)
    }

    private var buttonGradient: LinearGradient {
        if active {
            return LinearGradient(colors: [GrainPalette.orange, GrainPalette.orange.opacity(0.82)], startPoint: .top, endPoint: .bottom)
        }
        return LinearGradient(
            colors: [Color(red: 0.29, green: 0.295, blue: 0.278), Color(red: 0.118, green: 0.122, blue: 0.110)],
            startPoint: .top,
            endPoint: .bottom
        )
    }
}
