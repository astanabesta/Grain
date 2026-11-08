## Grain Photo vVERSION — Dark-Hair Smudge Repair, Speckle-Free Shadows & 3D Portraits (`i16`, `iXS`, `iRS`, `iRC`, `iAM`)

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, and photos).
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** Download **`Grain-iOS-vVERSION.ipa`** below and install onto any iPhone (iOS 16+, including **iPhone XR / XS** and newer) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)**.

### What's new in v0.9.3

#### 1. Dark-Hair Smudge Repair (Watercolour-Mush Fix)
- **Tone-Gated Texture Floor:** Fine low-contrast strands in dark areas (black hair, brows, shaded fur, dark fabric) sit below the `v0.9.2` shot-noise SNR gate, so they were erased into a flat watercolour wash. A new floor restores real strand detail + sharpening only where the tone is dark (`<= ~sRGB 95`) **and** the local 3×3 high-pass proves multi-pixel texture above the measured noise sigma — dark walls/clothing stay silk-clean while individual hairs return with ~60% of their raw contrast.
- **Seamless Micro-Contrast:** The Smart HDR texture re-add (quarter-resolution, per 4×4 cell) could flip its SNR gate per cell in noisy texture, quilting dark hair into visible smudge patches; the per-cell texture field is now 3×3-box smoothed before interpolation — measured 4×4 block jumpiness drops **7×** (`0.49` vs `3.55` codes).

#### 2. Isolated Speckle Elimination
- **Structural Impulse Suppression:** Hot/dead single-pixel specks (sensor outliers visible as sand-dots in hair and shadows) carry a large residual but no 3×3 high-pass energy; they are now crushed to ≤15% amplitude against the measured per-frame noise curve, while strands 2+ px wide and hard edges keep full amplitude (measured speck deviation `32.4 → 4.1` codes).

#### 3. Everything from v0.9.2 (kept)
- Luminous 3D portrait faces (`FACE_KEY 0.368` + preview-face replay + 3/4-pose skin-locus fallback), scene-adaptive white point (unclipped indoor walls render bright, `227–238` codes), glossy-black-hair blackGuard taper, SNR-gated photonic detail (`>85%` less high-ISO sand on flats, crisp edges/hair/text), `-1/3 EV` capture bias (`+26%` more low-light photons), Universal Vibrant sRGB + Android 14+ Ultra HDR `Gainmap`.
