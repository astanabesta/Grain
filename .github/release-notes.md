## Grain Photo vVERSION — Portrait Mode: Natural Lens Bokeh + 50/85mm Portrait Focal

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, and photos). v0.9.4+ targets **arm64 phones** (all modern devices incl. the Redmi Note 13 Pro+) — the on-device person-segmentation engine no longer ships x86 builds.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** Download **`Grain-iOS-vVERSION.ipa`** below and install onto any iPhone (iOS 16+, including **iPhone XR / XS** and newer) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)**. *(Portrait mode ships on Android first; the iOS port follows.)*

### What's new in v0.9.4 — Portrait Mode (Android)

#### 1. The P Key
- **Portrait orientation:** new **`P`** key right beside the `Q` key (the zoom command dial is slightly narrower to make room). **Landscape:** under the `▶` playback key.
- **Tap `P`** to cycle the simulated aperture: **`f/0.95 → f/1.4 → f/2 → f/2.8 → f/4 → f/8 → off`** — the key lights up warm gold (`P 1.4`) while active.
- **Hold `P`** to cycle the portrait focal length: **23 mm → 50 mm → 85 mm** (a RAW-quality crop of the 200 MP sensor; because blur is applied before the crop, defocus grows with focal length exactly like real optics). The current state survives restarts.

#### 2. Natural Lens Bokeh (Vivo-Style Rendering, Real-Optics Math)
- **On-device person segmentation** (bundled 256² model — fully offline, nothing ever uploaded) separates the subject, then blurs the background **in linear light** with a disc radius that **grows with distance from your subject** (chamfer depth ramp), like a real fast lens.
- **Foreground-exclusion blur** — person pixels never leak into the background, so halos around the silhouette are suppressed and the subject stays pixel-sharp.
- **Soft optical transition** — the focus falloff at the silhouette is continuous (no cut-out look); hair edges keep full RAW acutance.
- Falls back to the camera's face silhouette when the ML model is unavailable; records `portrait: f/… · disc Npx` in the photo receipt. Skipped on HI-RES bursts (memory safety).

#### 3. Kept from v0.9.3
- Dark-hair smudge repair (tone-gated texture floor), speckle elimination, 4×4 quilt removal, 3D portrait faces, silk-clean walls, `-1/3 EV` bias, Universal Vibrant sRGB + Ultra HDR Gainmap.
