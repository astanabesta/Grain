## Grain Photo vVERSION — Native iOS `.ipa` Release + Android `.apk`

- **Android (`Grain-vVERSION.apk`):** Install directly inside Grain (**MENU → SET UP → APP UPDATE**) or download **`Grain-vVERSION.apk`** below.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** Download **`Grain-iOS-vVERSION.ipa`** below and install onto any iPhone (iOS 16+) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)** with your free Apple ID.

### What's new in v0.7.7

#### 1. Native iOS Edition (`Grain-iOS-vVERSION.ipa`) — Pro Bayer RAW + All-iPhone Scene-Linear Engine
- Full native Swift / SwiftUI / `AVFoundation` / `CoreImage` / `OKLab` port of Grain in `ios/`:
  - **Works on ALL iPhones (iOS 16+):** Captures hardware **Bayer RAW DNG** on RAW-capable Pro iPhones *and* runs a Display-P3 / sRGB inverse-shoulder **scene-linear F-Log2 pipeline** on standard non-Pro iPhones so every film simulation and recipe works across the entire iPhone lineup.
  - **All 28 Films & Calibrated Recipes:** Includes all 4 instant looks, all 11 published `◆` recipes (**Reggie's Portra**, **Reggie's Superia**, **Kodak Gold 200**, **Bright Kodak**, **Kodak 800 v3 `K800`**, **Leica X `LCX`**, **Kodachrome 64**, **Pacific Blues**, **Vibrant Arizona**, **CineStill 800T `800T`** with red-orange halation, **Kodak Tri-X 400**), 12 Fujifilm simulations, and **Sony `s709`** — ready out of the box *and* upgradable with official `.zip` / `.cube` LUT packs in `MENU → I.Q.`.
  - **Full Rangefinder Hardware UI:** Tactile Shutter / ISO / EV / Film drum dials, **72pt 360° ProMotion 120Hz analog Focus Lever**, **Pure Blackout Optical Viewfinder** (top-left eyepiece window + hardware volume-button shutter), **`4× / 8×` Focus Check loupe**, **FS & Focus Bracketing**, and **Darkroom Gallery with Multi-Select Batch Delete (7s Undo) & Save**.

#### 2. Unified Android + iOS Release Pipeline
- Every release now publishes **`Grain-vVERSION.apk`**, **`Grain-iOS-vVERSION.ipa`**, and a unified **`SHA256SUMS.txt`**.
