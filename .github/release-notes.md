## Grain Photo vVERSION — Bright 3D Portrait Faces & Silk-Clean Photonic Detail (`i16`, `iXS`, `iRS`, `iRC`, `iAM`)

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, and photos).
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** Download **`Grain-iOS-vVERSION.ipa`** below and install onto any iPhone (iOS 16+, including **iPhone XR / XS** and newer) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)**.

### What's new in v0.9.2

#### 1. Luminous 3D Portrait Faces, Bright Indoor Tones & Glossy Black Hair
- **Apple Portrait Face Exposure (`AppleRender.FACE_KEY = 0.368`) + 3-Layer Face Metering:** Fixes dark indoor portraits against bright walls/windows by calibrating Apple portrait metering (`+0.8 EV` brighter than Fujifilm's low-key target), forwarding recent live-preview hardware face boxes when Camera2 omits `STATISTICS_FACES` on single-frame `RAW_SENSOR` captures, and adding a **Display P3 human-skin chromaticity fallback** for 3/4-turned or profile faces missed by frontal detectors (rejecting warm walls, shirts, and wood).
- **Uncompressed Facial Highlights & Scene-Adaptive White Point:** Moves Smart HDR `highlightPull` above `+1.30 EV` so lit foreheads, cheekbones, and shirts keep full 3D tonal modeling (`+28` sRGB codes between lit and shadow cheeks instead of flattened midtones), tapers `shadowLift` below `-2.65 EV` so black hair stays rich and glossy rather than lifted grey, and adapts `whiteLin` to the scene's 99.6th-percentile peak so unclipped indoor walls render clean and bright instead of dull grey-cream.

#### 2. SNR-Gated Photonic Detail (Silk-Clean High-ISO Walls/Skin + Razor-Sharp Hair, Eyes & Text)
- **Guided-Filter SNR Structure Gating (`meanA`):** Replaces unconditional raw-noise retention (`NR -4`) with per-pixel He–Sun–Tang guided-filter structure confidence gating (`bb[o + x] = meanA`), ISO-adaptive fine/quarter-block noise thresholds, and SNR-gated Deep-Fusion quarter-block micro-contrast.
- **Best of Both Worlds:** Eliminates `>85%` of high-ISO sandy shot/read noise and `4×4` blotches on flat indoor walls, smooth skin, and dark hair while applying full halo-cored crisp acutance (`Sharpness +2..+3`) and fine edge retention (`0.46`) selectively to real hair strands, eyelashes, pores, textiles, wood grain, and lettering — paired with `-1/3 EV` capture bias (`+26%` more low-light photons).
