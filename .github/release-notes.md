## Grain Photo vVERSION — Creamy Bokeh: Continuous Depth Gradient + Milky Blend

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, and photos). Arm64 phones only. The APK grows to ~53 MB because the portrait brain is now **three bundled neural networks** — everything still runs **fully offline on your phone**; no shot ever leaves the device.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (portrait mode is still Android-first; sideload instructions as before).

### What's new in v0.9.8 — The "Cream" Render

Built from your Mi Gallery comparison: the buttery look isn't "more blur", it's two specific behaviours our renderer lacked:

#### 1. Continuous Blur Gradient (no more flat frosted wall)
- Our fallback ramp reached max radius within ~3 px of your silhouette, so the entire background became one uniform smear (measured d1 flattened to constant 0.12).
- The ramp is now **asymptotic** — blur keeps growing with distance forever (`1 − e^−d/τ`), so near-mid content keeps a visible gradient, exactly like glass. The depth-driven path (when the depth model engages) gets the same eased curve (`pow(δ, 0.7)`).

#### 2. Milky Gamma-Domain Blend (~30%)
- Blur remains computed in physically-neutral linear light (that's why highlights render as true discs), then a second identical cascade run **in sRGB space** is mixed in at 30% with the same exclusion weights and pick radius. Blurred bright+dark tones churn toward middle values — the "whipped cream" of the Mi/Samsung editors — without touching subject sharpness or disc highlights.
- Cost: ~+25% bokeh time (a portrait shot develops ≈ 1 s longer).

#### 3. Kept from 0.9.7
- Hair-band anchor (flyaways stay sharp), depth telemetry (`portraitDepthNull`), full-res letterbox fix, coverage guard, v0.9.3 texture fixes.


#### 1. Hair Stays Pixel-Sharp (was blurred 4.7×)
- On your shot our hair registered `d1=1.36` vs `6.37` in the reference. RVM at 480 px can't resolve flyaways 1500 px tall, so strands got alpha ≈0.3 and we half-blurred them.
- New **hair-band anchor**: the camera's face box is expanded into a soft elliptical floor (hair volume + temples + chin) and the matte alpha can never drop below it there — same trick Xiaomi/OnePlus use. Your next shot's strands keep full RAW acutance (shirt and face were never harmed — they stay sharp too).

#### 2. Depth Model Guard Relaxed + Self-Reporting
- Receipt showed the int8 MiDaS run (148 ms) but silently rejected output and fell back to the distance-from-edge ramp — so objects at your exact focal plane could over-blur.
- The model now records **why** it refused (`portraitDepthNull` in the receipt: load/flat-range/tight-spread/exception) and the flat-scene threshold went from 2 % to 0.8 % — real portraits are depth-tight by nature. If it still refuses on your next shot, the receipt will tell me in one screenshot which guard is too strict and I fix it remotely.

#### From v0.9.6 (all kept): letterbox fix & guard

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
