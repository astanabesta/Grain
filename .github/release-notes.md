## Grain Photo vVERSION — iPhone 16 Pro Smart HDR 5 + Photographic Styles, Display P3 & Ultra HDR (`Android`)

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, and photos).
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** Download **`Grain-iOS-vVERSION.ipa`** below and install onto any iPhone (iOS 16+, including **iPhone XR / XS** and newer) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)**.

### What's new in v0.9.0

#### 1. Built-in iPhone 16 Pro RAW Pipeline (Smart HDR 5 + Photographic Styles)
Four ready-to-shoot RAW profiles sit right on the Film dial (`i16`, `iRS`, `iRC`, `iAM`) — **no external `.cube` LUT download required**, fully compatible with `FS BKT`, `HI-RES 36MP / 48MP / 112MP`, and recipe tuning:
- **iPhone 16 Pro · Standard (`i16`):** Balanced Smart HDR 5 local shadow lift, natural halo-free silhouette edges, protected true-warm skin, tamed CMOS greens, and cerulean skies.
- **iPhone 16 Pro · Natural Rose (`iRS`):** Luminous portrait skin undertone that tames indoor warm/yellow cast while keeping neutral backgrounds neutral.
- **iPhone 16 Pro · Rich Contrast (`iRC`):** Deeper black-toe shadow anchor, crisp separation, and richer Display P3 pigment density while keeping skin chroma protected.
- **iPhone 16 Pro · Amber Cozy (`iAM`):** Golden-hour warmth across midtones and creamy sunlit highlights without turning faces neon orange.

#### 2. Scene-Linear Local Tone Mapping + Hue-Preserving `RefBaselineRGBTone`
- **Edge-aware quarter-resolution `ProfileGainTableMap` operator:** extracts a self-guided log-EV illumination envelope (`0.68 · Y + 0.32 · max(R,G,B)`) that lifts foreground shadows and recovers bright skies/windows while keeping local micro-contrast intact and silhouette edges halo-free; sensor noise variance scales locally with shadow gain so lifted shadows stay clean.
- **DNG `RefBaselineRGBTone` global S-curve:** sorted `(max, mid, min)` tone operator keeps HSV hue angle invariant across bright highlights and deep shadows.

#### 3. Wide-Gamut Display P3 + Android 14+ Ultra HDR `Gainmap`
- **Native Display P3 output:** develops directly from camera RAW into linear Display P3 and embeds the Display P3 ICC colour space in the saved JPEG.
- **ISO 21496-1 Ultra HDR `Gainmap` (Android 14+ / API 34+):** encodes true pre-shoulder linear RAW highlight headroom (up to `3.0×` OLED luminance above diffuse white) into a quarter-resolution single-channel gainmap, preserves gainmaps across aspect crops (`3:2`, `16:9`, `1:1`, `XPAN`) and EXIF saves (in-place CIPA DC-007 `MPF` repair), and dynamically switches Grain's `PLAY` / Gallery viewer into `COLOR_MODE_HDR`.
