## Grain Photo vVERSION — Portrait Fix: Faces NO Longer Blur (v0.9.5 letterbox regression fixed)

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, and photos). Arm64 phones only. The APK grows to ~53 MB because the portrait brain is now **three bundled neural networks** — everything still runs **fully offline on your phone**; no shot ever leaves the device.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (portrait mode is still Android-first; sideload instructions as before).

### What's new in v0.9.6 — Hotfix Required

#### Your v0.9.5 Shot Was Broken Here — Now Fixed
- **Root cause (my bug):** the matting and depth models letterboxed against the small 480×640 *preview* bitmap while the quarter-grid indexes *full-sensor* pixels — so the alpha matte read nearly-empty everywhere and the safety fallback blurred your face. Portrait mode behaved as random fog on ~v0.9.5 for most portrait-oriented photos.
- **Fixes:** all three models now share one full-res-stable letterbox math; and a **hard guard** (person-core coverage sanity) skips bokeh entirely if any model map looks wrong — a future regression can dim the portrait effect, but **never** blur the subject.
- **Regression tests:** letterbox is now pinned in JVM tests (portrait & landscape) plus the depth-field and sigma tests — the same math that broke is unit-tested against the real coordinates from your receipt.

#### From v0.9.5 (all kept)


#### 1. Hair-Level Edges (Alpha Matting)
- Replaced the coarse 256² segmentation with a bundled **Robust Video Matting** model (7.6 MB): the matte follows flyaway hairs, ear/shoulder silhouettes stay continuous, and the "ghost column" artifacts of v0.9.4 are gone. Falls back to the old segmenter → face silhouette if the model is missing.

#### 2. Real Depth = Real Optics Defocus
- A bundled int8 **MiDaS depth model** (18 MB) maps how far away everything is. The focus plane locks to the subject, and blur grows **optically with distance** — so a bed/table/clothes beside you at your focal plane **stays sharp** (v0.9.4 blurred it), far walls melt completely, and the falloff is continuous, not a cut-out ramp.
- **Highlight discs:** defocused points of light now keep their punch — visible cream circles at f/0.95–f/1.4 like a fast lens, instead of washed-out smudges.

#### 3. Controls (same as v0.9.4)
- **`P` key** beside `Q` (portrait) / under `▶` (landscape); tap cycles **f/0.95→f/8**, hold cycles **23/50/85 mm** portrait focal.
- Costs: portrait shot processing is ~1-3 s longer (matting + depth + bokeh on CPU); HI-RES bursts still skip it. Receipt shows `alpha matte + depth · disc Npx` plus per-model timings.

#### 4. Kept from v0.9.3/0.9.4
- Dark-hair smudge repair, speckle elimination, 3D portrait faces, silk-clean walls, `-1/3 EV` bias, Universal Vibrant sRGB + Ultra HDR Gainmap, and the full v0.9.4 portrait plumbing (P key, focal dial, face fallback, hi-res skip).
