## Grain Photo vVERSION — Two Tripod High-Resolution Modes & a Focus Peaking Fix (`Android` + `iOS`)

- **Android (`Grain-vVERSION.apk`):** Install directly inside Grain (**MENU → SET UP → APP UPDATE**) or download **`Grain-vVERSION.apk`** below.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** Download **`Grain-iOS-vVERSION.ipa`** below and install onto any iPhone (iOS 16+, including **iPhone XR / XS** and newer) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)**.

### What's new in v0.8.1

#### 1. Focus Peaking No Longer Crashes
- **Fixed:** Turning on `PEAKING` could take the app down. The preview read-back now waits for the viewfinder to have a real size, and a failed read-back can never take the camera down with it.
- **Never fatal:** If a device cannot read the preview back at all, Grain switches peaking off and tells you, instead of crashing. Everything else in the camera keeps working.

#### 2. Two Tripod High-Resolution Modes (`HI-RES 1 36MP` / `HI-RES 2 48MP`)
- **Select via `DRIVE` button or `MENU → SHOOTING → DRIVE`:** Cycles through `SINGLE → 3s → 5s → FS BKT → FOCUS BKT → HI-RES 1 36MP → HI-RES 2 48MP` (`HR 36MP` / `HR 48MP` badge in the viewfinder OSD).
- **`HI-RES 1 36MP` (unchanged):** The 4-shot tripod merge you already know, at `~6928 × 5196` (`36.00 MP`).
- **`HI-RES 2 48MP` (new):** The same 4-frame sub-pixel merge, now resolving `~8000 × 6000` (`48.00 MP`) from the same 12 MP sensor frame.
- **Same 4-second tripod settle, same locked AE/AF, same haptic/Taptic OIS micro-shift** — only the merge size changes.
- **Registers all 4 linear sensor frames on a `4×4` sub-pixel tile grid** (`0.05 px` parabolic refinement) with automatic anti-ghosting fallback on moving foliage, clouds, or water, then develops the merged negative through Grain's full F-Log2 / S-Log3, 3D LUT, `OKLab` colour calibration, halation, and film grain pipeline.
- **Honest limits:** `48 MP` needs near-perfect sub-pixel registration, so it is more sensitive to movement than `36 MP` — use a tripod. The merge never upscales past `2×` linear (the most four frames can resolve), so a smaller sensor than `12 MP` will land below `48 MP`; the receipt always records the real output size.

#### 3. Receipts & Gallery Say Which Mode You Shot
- **Saved files** still carry the `_HiRes` filename tag, and the receipt now records the nominal merge size (`36 MP` or `48 MP`) alongside the sub-pixel shift diagnostics.
- **Darkroom gallery** shows the merge size on each Hi-Res frame, on both Android and iOS.
