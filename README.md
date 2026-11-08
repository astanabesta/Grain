<div align="center">

# Grain Photo

**A tactile Android & iOS camera that develops film from sensor RAW.**

Rangefinder-inspired controls, capture-time ISP looks, and Fujifilm & Sony film pipelines developed on-device from linear RAW / scene-linear light.

[▶ Watch Grain on the phone](https://github.com/user-attachments/assets/8a401c48-bb28-4abb-abd6-3a3d80443ecc)

[![Build and release](https://github.com/astanabesta/Grain/actions/workflows/build-apk.yml/badge.svg?branch=main)](https://github.com/astanabesta/Grain/actions/workflows/build-apk.yml)
[![Latest release](https://img.shields.io/github/v/release/astanabesta/Grain?include_prereleases&label=release&color=b5532a)](https://github.com/astanabesta/Grain/releases/latest)
[![Android](https://img.shields.io/badge/Android-10%2B-3ddc84?logo=android&logoColor=white)](#download--install)
[![iOS](https://img.shields.io/badge/iOS-16%2B%20(.ipa)-ffffff?logo=apple&logoColor=black)](#download--install)

</div>

> **Device-test beta.** Grain (`dev.grain.photo`) is an independent personal camera build calibrated primarily on the **Redmi Note 13 Pro+**. It is distributed directly via GitHub Releases rather than Google Play. Export photos you want to keep permanently (`Save to device gallery` → `Pictures/Grain`) before uninstalling, as private in-app storage is removed on uninstall.

---

## Contents

- [Overview](#overview)
- [Highlights](#highlights)
- [Download & Install](#download--install)
- [One-Time Setup: Official LUT Packs](#one-time-setup-official-lut-packs)
- [Films & Published Recipes](#films--published-recipes)
- [Using the Camera](#using-the-camera)
- [How the RAW Pipeline Works](#how-the-raw-pipeline-works)
- [Privacy & Storage](#privacy--storage)
- [Compatibility & Verification](#compatibility--verification)
- [Building from Source](#building-from-source)
- [Project Layout](#project-layout)
- [Documentation](#documentation)
- [Known Limitations & Troubleshooting](#known-limitations--troubleshooting)
- [Credits & Version History](#credits--version-history)

---

## Overview

Smartphone cameras flatten highlights, lift shadows, and bake aggressive local tone-mapping before you ever see the frame. Grain takes the opposite path:

1. **Develop from linear sensor RAW (`◆`).** When shooting a RAW film or recipe, Grain captures the sensor's `RAW_SENSOR` Bayer frame and develops it on-device through DNG colour matrices, F-Log2 / S-Log3 log curves, and **Fujifilm's or Sony's official 33³ 3D LUTs** — paired with phone-sensor calibration so small-pixel CMOS captures deliver genuine film highlight roll-off, shadow response, halation, and colour density.
2. **Request ISP looks at capture, never as a post-filter.** The four lightweight ISP looks (`DAY`, `CST`, `GLN`, `AFT`) configure Camera2 white balance, monotonic tone curves, and edge/noise modes *before* the shutter fires.
3. **Behave like a physical camera.** The interface is the back of a classic rangefinder camera — tactile dials for film, shutter speed, ISO, and exposure compensation, a five-way focus/menu joystick, a film-box memo holder, and a **pure blackout optical viewfinder** that turns off every pixel on the screen except the top-left eyepiece window.

Every exposure saves a machine-readable **JSON receipt** documenting what was requested, what the Camera2 HAL actually delivered, and every stage of RAW development.

---

## Highlights

| Area | Capabilities |
| --- | --- |
| **Camera Body** | Portrait & landscape rangefinder back in three finishes, tactile film / shutter / ISO / EV / command dials, five-way lever, film-box memo holder, and **Pure Blackout Viewfinder** (`#000000` body blackout leaving only the top-left optical window active) |
| **Films & Recipes** | **11 published Fujifilm recipes**, **12 official Fujifilm film simulations**, **Sony `s709`**, and **4 capture-time ISP looks** |
| **Phone-Sensor Calibration** | Dedicated CMOS-to-emulsion calibration on **Kodak 800 v3 (`K800`)**, **Leica X (`LCX`)**, and **CineStill 800T (`800T`)**: RAF-matched Kelvin white points, soft highlight shoulders, open Portra / grounded Leica & Vision3 shadow toes, scene-linear remjet-free red-orange halation, perceptual `OKLab` colour & `Color Chrome` pigment density, skin protection, foliage hue calibration, and hue-preserving gamut compression |
| **Exposure & Flash** | Closed-loop **P / S / ISO / M** exposure solver (`1/4000 s` – `1 s`, `ISO 50–12800`), EV `−2…+2` (plus `0.1 EV` fine command-dial mode), AE-L, face-weighted metering, and hardware flash (`⚡ OFF / ON / AUTO / TORCH`) |
| **Focus & Loupe** | Single / Zone / Centre / Face AF, sticky **AF-C face tracking**, AF lock, **30-step manual focus** with a **scale under the screen engraved with the real distances the lens reports**, real-time **focus peaking**, and **`4× / 8×` Focus Check magnifier loupe** |
| **Drive & Bracketing** | Single shot, `3 s` / `5 s` self-timer, **3-film simulation bracketing (`FS BKT`)**, **3 / 5 / 7-frame focus bracketing (`FOCUS BKT`)**, and **two Tripod 4-Shot High-Resolution Modes (`HI-RES 1 36MP` / `HI-RES 2 48MP`)** |
| **Darkroom Gallery** | Private on-device film roll, **multi-select batch delete** (with 7-second Undo) & **batch export** to `Pictures/Grain`, share sheet, and full per-photo technical receipts |
| **Updates & Privacy** | Zero accounts, analytics, telemetry, or cloud uploads; optional SHA-256-verified in-app updates from GitHub Releases (with Xiaomi HyperOS / MIUI system-installer support) |

---

## Download & Install

**Latest release:** [![Latest release](https://img.shields.io/github/v/release/astanabesta/Grain?include_prereleases&label=Grain%20APK%20%26%20IPA&color=b5532a)](https://github.com/astanabesta/Grain/releases/latest) — [**Download Android `.apk`, iOS `.ipa` & `SHA256SUMS.txt`**](https://github.com/astanabesta/Grain/releases/latest) · [All releases](https://github.com/astanabesta/Grain/releases)

### Android (`Grain-v*.apk`)
1. Open the [**latest release**](https://github.com/astanabesta/Grain/releases/latest), download **`Grain-v*.apk`**, and install it.
2. Grant **Camera** permission on first launch (the only runtime permission Grain requests).
3. Follow the 4-step **Intro Guide** (replayable anytime via `MENU → SET UP → INTRO GUIDE`).

### iPhone / iOS (`Grain-iOS-v*.ipa` · Free Sideload)
1. Download **`Grain-iOS-v*.ipa`** from the [**latest release**](https://github.com/astanabesta/Grain/releases/latest).
2. Sideload onto any iPhone (**iOS 16.0+**) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)** with a regular free Apple ID (trust your Apple ID developer profile once under `Settings → General → VPN & Device Management`).
3. **True hardware Bayer RAW on all iPhones (iPhone XR / XS through iPhone 16):** Captures pure single-exposure **12MP Bayer RAW (`.dng`)** via `AVCapturePhotoOutput` (`isBayerRAWPixelFormat`) on every iOS 16+ iPhone rear wide camera (including standard non-Pro models like **iPhone XR, 11, 12, 13, 14, 15, 16**, plus Pro models), with an automatic scene-linear Display-P3/sRGB F-Log2 fallback if a lens/mode doesn't expose RAW — all 28 films work out of the box and can load official `.zip` / `.cube` LUT packs in `MENU → I.Q.`.

### Updating an Existing Install

| Installed version | How to update |
| --- | --- |
| **v0.6.0 or newer** | Open Grain (or go to **`MENU → SET UP → APP UPDATE`**) to download, verify (`SHA-256`), and install directly inside the app. |
| **v0.2.1 – v0.5.3** | Install the latest APK once over your current app. Photos, imported LUTs, and per-film tuning are preserved. |

All builds since `v0.2.1` share the same personal signing certificate (`SHA-256`):

```text
6e073ef68730c0022f8359b668bbfc087e8ec348ad334a1afb962ad06e206553
```

---

## One-Time Setup: Official LUT Packs

Grain never bundles or redistributes proprietary manufacturer LUTs. You download them free from Fujifilm and Sony once and import the `.zip` directly into Grain — no manual unzipping needed.

| Film family | Required zip | Where to import |
| --- | --- | --- |
| **ISP looks** (`DAY`, `CST`, `GLN`, `AFT`) | None — works immediately | Ready out of the box |
| **Fujifilm simulations & recipes (`◆`)** | Fujifilm F-Log2 3D-LUT zip (*GFX ETERNA 55* v1.10 or X-series F-Log2 pack) | `MENU → I.Q. → DOWNLOAD LUTS`, then `IMPORT LUT ZIP` |
| **Sony `s709`** | Sony [`s709Cubes.zip`](https://support.d-imaging.sony.co.jp/support/ilc/movie/en/grading/02.html) (~0.5 MB) | `MENU → I.Q. → IMPORT LUT ZIP` (or from the `s709` film sheet) |

---

## Films & Published Recipes

### Published RAW Recipes (`◆`)

Developed from sensor `RAW_SENSOR` through Fujifilm's official F-Log2 LUTs. Every setting can be customized per film in the **Film Sheet** or **Q** menu and restored anytime via *Reset this look*.

| Dial | Recipe | Base LUT | White Balance | DR | H / S | Color | Grain | CCE / FX Blue | Sharp / NR / Clarity | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **`PTR`** | **Reggie's Portra** ¹ | Classic Chrome | Auto `R+2 B−4` | DR-Auto | `−1 / −1` | `+2` | Weak, Small | Strong / Weak | `−2 / −4 / 0` | Reggie Ballesteros |
| **`SUP`** | **Reggie's Superia** ¹ | Classic Neg. | Auto `R+1 B−3` | DR-Auto | `−2 / −1` | `+1` | Strong, Large | Strong / Strong | `−2 / −4 / 0` | Reggie Ballesteros |
| **`GLD`** | **Kodak Gold 200** | Classic Chrome | Daylight `R+4 B−5` | DR400 | `−1.5 / +0.5` | `+3` | Strong, Small | Weak / Off | `−2 / −4 / −2` | Ritchie Roesch |
| **`BKD`** | **Bright Kodak** | Classic Chrome | Daylight `R+3 B−7` | DR400 | `−2 / −2` | `+4` | Strong, Large | Off / Off | `−2 / −4 / −3` | Ritchie Roesch |
| **`K800`** | **Kodak 800 v3** ² | Classic Chrome | `6600K` `R−1 B−3` | DR400 | `−2 / −0.5` | `+3` | Strong, Large | Strong / Weak | `−2 / −4 / −3` | Ritchie Roesch · Sensor-calibrated Portra 800 |
| **`LCX`** | **Leica X** ² | Classic Chrome | Auto `R+1 B−2` | DR-Auto | `−1 / 0` | `+4` | Off | Strong / Strong | `0 / −4 / 0` | Carl Cruz · Sensor-calibrated Leica Color |
| **`K64`** | **Kodachrome 64** | Classic Chrome | Daylight `R+2 B−5` | DR200 | `0 / +0.5` | `+2` | Weak, Small | Strong / Off | `+1 / −4 / +3` | Ritchie Roesch |
| **`PAC`** | **Pacific Blues** | Classic Neg. | `5800K` `R+1 B−3` | DR400 | `−2 / +3` | `+4` | Strong, Large | Strong / Weak | `−2 / −4 / −3` | Ritchie Roesch |
| **`ARZ`** | **Vibrant Arizona** | Classic Chrome | `4350K` `R+6 B−8` | DR-P Strong | DR-P | `+4` | Weak, Small | Off / Weak | `−2 / −4 / −3` | Ritchie Roesch |
| **`800T`** | **CineStill 800T** ² | Eterna | Fluor. 3 `R−6 B−4` | DR400 | `0 / +2` | `+4` | Strong, Large | Strong / Weak | `−3 / −4 / −5` | Ritchie Roesch · Vision3 tungsten + red halation |
| **`TX`** | **Kodak Tri-X 400** | Acros | Daylight `R+9 B−9` | DR200 | `0 / +3` | — | Strong, Large | — | `+1 / −4 / +4` | Anders Lindborg / Ritchie Roesch |

- ¹ Defaults to *First version* (`v0.2.0` OKLab/grain path); all other recipes default to *Clean* guided-filter development.
- ² **Phone-Sensor-to-Emulsion / Summilux Calibration:**
  - **`K800` (Kodak 800 v3):** RAF-calibrated Kelvin curve (`6600K` Fuji → `5693K` DNG Planckian so whites stay creamy rather than orange), soft highlight shoulder, open Portra shadow toe, gentle emulsion highlight micro-bloom, peach-gold skin protection, and pine-sage foliage calibration.
  - **`LCX` (Leica X):** Summilux midtone micro-contrast and dehazed optical black toe, `-2/3 EV` specular protection, perceptual `OKLab` `Color +4` + `Color Chrome Strong` vermilion-crimson reds, polarized cobalt skies, grounded emerald-forest greens, and portrait skin restraint.
  - **`800T` (CineStill 800T):** True `3450K` tungsten Vision3 white point, scene-linear **remjet-free red-orange pressure-plate halation** around bright night lamps and neon, `-1.0 EV` low-light photon headroom, cyan-teal night shadows × warm-amber highlights, and `OKLab` chroma rescue over flat Eterna.

### Standard Film Simulations & Cinema Looks (`◆`)

- **Fujifilm F-Log2 simulations** (unlocked as present in your imported LUT zip): `Provia` (`P`), `Velvia` (`V`), `Astia` (`S`), `Classic Chrome` (`CC`), `Reala Ace` (`RA`), `Pro Neg. Hi` (`NH`), `Pro Neg. Std` (`NS`), `Classic Neg.` (`NC`), `Nostalgic Neg.` (`NN`), `Eterna` (`E`), `Eterna Bleach Bypass` (`EB`), and `Acros` (`A`, with `+Ye`, `+R`, `+G` monochrome contrast filters).
- **Sony Cinema Look:** **`Sony s709`** (`s709`) — developed from sensor RAW through exact per-pixel S-Gamut3.Cine / S-Log3 encoding into Sony's VENICE / FX9 `s709` 3D LUT.

### Capture-Time ISP Looks

- **`DAY` (Daylight)** · **`CST` (Coast)** · **`GLN` (Golden)** · **`AFT` (Afterhours)** — experimental 1D RGB Camera2 ISP curves applied at capture with zero RAW processing overhead.

---

## Using the Camera

### Camera Back Controls

| Control | Function |
| --- | --- |
| **Top-Left Eyepiece (`EYE`)** | Enters **Pure Blackout Viewfinder**: turns the entire screen, dials, LCD, and shutter ring pure `#000000` black, leaving **only** the top-left optical viewfinder window live. Tap inside the window to focus/meter, press **Volume + / −** to shoot, and tap anywhere on the black body (or Back) to return. |
| **LCD Preview** | Live Camera2 viewfinder cropped to `3:4`, `1:1`, or `9:16`. Tap to place focus; press-and-hold to open the **`4× / 8×` Focus Check loupe**; pinch to zoom `1×–5×`. |
| **Film Dial** | Scrolls ISP looks, published `◆` RAW recipes, imported Fujifilm simulations, and Sony `s709`. Tap the active film or memo box to open the **Film Lab & Tuning Sheet**. |
| **Shutter / ISO Dial** | Sets **Program (`A`)**, **Shutter Priority** (`1/4000 s` – `1 s`), **ISO Priority** (`50` – `12800` via the `ISO` toggle chip), or **Manual (`M`)** when both dials are off `A`. |
| **EV Dial & Command Wheel** | `−2` to `+2 EV` in `1/3 EV` clicks; set EV to **`C`** to drive fine `0.1 EV` steps on the rear command wheel (controls camera zoom in live view, steps manual focus in the loupe, and **smoothly zooms `1×–8×` on photos in `PLAY` gallery**). |
| **Flash (`⚡`)** | Cycles **`OFF`**, **`ON`**, **`AUTO`**, and continuous **`TORCH`** fill light. |
| **Focus Lever (Joystick)** | **360° analog thumb stick:** drag in any direction to glide the AF point smoothly at 60–120 Hz (press centre to re-centre); tilt & hold to navigate / auto-repeat **`Q`**, **`MENU`**, and **`PLAY`**. |
| **`Q` Menu** | 16-tile quick grid (`Film`, `WB`, `WB R`, `WB B`, `DR`, `H Tone`, `S Tone`, `Color`, `Sharp`, `NR`, `Clarity`, `Grain`, `CCE`, `FX Blue`, `Dev`, `Face`). |
| **`DRIVE`** | `SINGLE`, `3 s` / `5 s` self-timer, **`FS BKT`** (3-film RAW bracket), **`FOCUS BKT`** (`3 / 5 / 7` focus planes), and **`HI-RES 1 36MP` / `HI-RES 2 48MP`** (4-shot tripod super-resolution). |
| **Playback (`▶`)** | Opens the private gallery: swipe frames, inspect JSON/settings receipts (`DISP`), or tap **Select** to batch-mark multiple photos for **Delete (N)** (with 7 s Undo) or **Save (N)** to `Pictures/Grain`. |
| **Memo Holder** | Tap to write a roll note; press-and-hold on the camera screen for an unfiltered reference preview. |

---

## How the RAW Pipeline Works

Each `◆` exposure pairs the linear `RAW_SENSOR` buffer with Camera2 metadata and runs an 8-stage pure-Java pipeline (`dev.grain.photo.develop`):

1. **Capture & DNG Optional Archival** — Captures `RAW_SENSOR` with DR-appropriate highlight headroom (`-1/3` to `-2 EV`, sensor-tuned per recipe), lens-shading map, black/white levels, DNG `ColorMatrix` / `ForwardMatrix` / `CameraCalibration` tags, and per-frame noise profile. When `FINE + RAW` is enabled, writes an archival `.dng` via `DngCreator`.
2. **Sensor Linearisation & Demosaic** — Black-level subtraction, 2D lens-shading gain, RAF-calibrated or as-shot white balance + WB shifts, and Malvar–He–Cutler gradient-corrected demosaicing with green-channel highlight neutrality recovery.
3. **Colorimetry & Scene-Linear Light Transport** — Dual-illuminant DNG forward/colour matrix interpolation with Bradford chromatic adaptation into linear Rec.2020 / F-Gamut, followed by energy-conserving scene-linear **Halation** (including CineStill 800T remjet-free red-orange scatter) and **Bloom**.
4. **Scene & Face Metering** — Centre-weighted log-average luminance metered to Fujifilm's fitted `0.148` stills key (or `0.18` for Sony `s709`), with optional face-interior luminance metering (`+0.5 EV` over scene key) and user EV compensation.
5. **Log Encoding & Stills Tone Curve** — Exact Fujifilm `F-Log2` encoding plus fitted stills tone curves, RAW-clip highlight shoulder remapping, `Highlight` / `Shadow` / `DR` / `DR-P` modifiers, and dedicated phone-sensor tone curves (`ToneModel.calibratedCurve`).
6. **Multi-Scale Noise Reduction, Sharpening & Clarity** — Frame-measured shot/read noise curve driving a quarter-resolution guided luma/chroma denoiser + Wiener chroma step, pixel-scale He guided filter (`High ISO NR`), denoised-base unsharp mask (`Sharpness`), and mid-band local contrast (`Clarity`).
7. **Official 3D LUT & Perceptual Colour Engine** — Trilinear lookup in Fujifilm's `FLog2_to_<SIM>_33grid` `.cube` (or per-pixel `S-Gamut3.Cine / S-Log3` into Sony `s709`), X100V-fitted `Color Chrome FX Blue`, perceptual `OKLab` `Color` / `Color Chrome` / hue calibration with hue-preserving soft gamut compression, Acros optical filter transmission, and calibrated dual-texture film grain.
8. **JPEG & Technical Receipt** — Encodes quality-95 sRGB JPEG with upright EXIF metadata and saves a complete JSON receipt (`requested`, `rawFrame`, `development`, per-stage timings).

Full mathematical derivations and calibration measurements are documented in **[docs/RAW_FUJI_RECIPES.md](docs/RAW_FUJI_RECIPES.md)**.

---

## Privacy & Storage

- **100% on-device imaging.** Photos, DNGs, LUTs, and tuning preferences never leave your phone.
- **Minimal permissions.** Only `CAMERA` (runtime) and `INTERNET` / `REQUEST_INSTALL_PACKAGES` (used exclusively to check `api.github.com/repos/astanabesta/Grain/releases` for app updates and install SHA-256-verified release APKs when you choose).
- **No GPS metadata.** Grain never requests location permissions and strips GPS tags from cropped outputs.
- **Atomic private gallery.** Captures write atomically to app-private storage and copy to `Pictures/Grain` via `MediaStore` only when you tap **Save**.

---

## Compatibility & Verification

- **Requirements:** Android 10+ (API 29+, targeting API 36). `◆` RAW films require Camera2 `RAW_SENSOR` support on the active rear lens.
- **Automated suite:**
  - **1,278** SDK-free pure-Java crop, tone-curve, preview, focus, and exposure smoke tests (`tools/check-source.sh`).
  - **105** JUnit & Robolectric tests covering F-Log2 / S-Log3 round-trips, DNG Planckian/Bradford colour math, noise measurement, bit-identical golden hashes, sensor-calibrated tone/colour/halation invariants, analog focus-lever physics, blackout viewfinder geometry, exposure planning, Hi-Res merge sizing, preview read-back safety, manual-focus scale geometry, and OTA update verification.
  - GitHub Actions runs `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleRelease` before publishing every release.

---

## Building from Source

**Prerequisites:** JDK 17, Android SDK Platform 36 & Build-Tools `36.0.0`, and Gradle `8.13`.

```bash
git clone https://github.com/astanabesta/Grain.git
cd Grain

# Point Gradle at your local Android SDK
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties

# Run syntax/math smoke tests (no Android SDK needed)
bash tools/check-source.sh

# Type-check the iOS sources without a Mac (catches unresolved names)
python3 tools/ios-typecheck.py

# Run full unit test suite, Android Lint, and build release APK
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

The release APK is written to `app/build/outputs/apk/release/app-release.apk` (`release` is non-debuggable so ART AOT/JIT-compiles `RawDeveloper` at full native speed on-device).

### Desktop RAW CLI Harness

Develop raw Bayer dumps directly against any `.cube` LUT on desktop without Android:

```bash
javac -d /tmp/grain $(find app/src/main/java/dev/grain/photo/develop -name '*.java') tools/DevelopRaw.java
java -cp /tmp/grain -Drecipe=kodak-800-v3 DevelopRaw raw.u16le meta.txt CLASSIC-CHROME.cube out.ppm
```

---

## Project Layout

```text
.github/
  workflows/build-apk.yml       CI test, lint, personal-key signing & GitHub Release publisher
  release-notes.md              Release notes template
app/src/main/java/dev/grain/photo/
  MainActivity.java             Camera UI, Q/Menu overlays, Film Lab, onboarding & gallery
  CameraBack.java               Custom-drawn rangefinder body, dials, OSD & blackout EVF
  GrainCamera.java              Camera2 session, P/S/ISO/M control, AF-C, flash & bracketing
  ExposureMath.java             Closed-loop P / S / ISO / M exposure & preview-cap solver
  FocusMath.java                Manual focus steps, AF regions & focus-bracketing math
  FocusScale.java               Dioptre-to-distance scale behind the manual-focus engraving
  AppUpdater.java               GitHub Releases checker, SHA-256 verifier & installer handoff
  Recipe.java                   Registry of ISP looks, simulations, recipes & capture headroom
  RecipeTuning.java             Persistent per-film tuning state
  LutLibrary.java               Fujifilm & Sony LUT zip importer
  RawSupport.java               RAW_SENSOR calibration extraction & DNG writer
  RawDevelopment.java           RAW development orchestrator & EXIF writer
  PhotoStore.java               Atomic private storage, trash/Undo & MediaStore export
  develop/                      Pure-Java RAW & colour science engine
    RawDeveloper.java           Demosaic, DNG colour, halation/bloom, NR, LUT & OKLab calibration
    ToneModel.java              Fitted F-Log2 stills curves & sensor-calibrated tone profiles
    ColorMath.java              Planckian locus, Duv, Bradford adaptation & DNG matrices
    FujiRecipe.java             Published recipes, simulations & camera menu ranges
    LutPreview.java             Calibrated Film Sheet thumbnail renderer
    CubeLut.java                33³ / 65³ .cube parser & trilinear interpolator
    FLog2.java / SLog3.java     Fujifilm F-Log2 & Sony S-Log3 / S-Gamut3.Cine encodings
    Faces.java                  RAW-coordinate face detection & interior metering
    TuningControl.java          Declarative UI bindings for recipe tuning
app/src/test/java/dev/grain/photo/
  RawEngineTest.java            Golden hashes, colorimetry, noise, halation & calibration tests
  ExposureAndRecipeTest.java    Exposure solver & published recipe invariants
  BlackoutViewfinderTest.java   Robolectric blackout eyepiece & touch-routing tests
  AppUpdaterTest.java           OTA release parsing & HyperOS/MIUI fallback tests
docs/                           Deep-dive technical & calibration documentation
tools/                          SDK-free syntax/math verifier, iOS type-check & desktop RAW CLI harness
```

---

## Documentation

- **[docs/RAW_FUJI_RECIPES.md](docs/RAW_FUJI_RECIPES.md)** — Complete RAW pipeline math, RAF white-point measurements, noise calibration, FX Blue fit, Sony `s709`, and phone-sensor calibration profiles.
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — Capture pipeline architecture, threading, and atomic storage invariants.
- **[docs/DEVICE_TEST_PLAN.md](docs/DEVICE_TEST_PLAN.md)** — On-device hardware verification checklist.
- **[CHANGELOG.md](CHANGELOG.md)** — Detailed release-by-release history.
- **[NOTICE.md](NOTICE.md)** — Third-party attributions and provenance.

---

## Known Limitations & Troubleshooting

- **Digital zoom (`1×–5×`):** Crops the active primary sensor rather than switching physical auxiliary lenses or proprietary 200 MP remosaic modes.
- **Stills only:** Grain is a dedicated still-photography camera (no video recording).
- **Camera2 HAL capabilities vary by phone:** Check `MENU → SET UP → DIAGNOSTICS` to inspect what your device's HAL advertises (`RAW_SENSOR`, manual sensor range, `LENS_FOCUS_DISTANCE`, AF regions, face detect modes).
- **Grain remembers its own crashes:** If the app ever crashes, the next launch shows the report on screen with a **Copy** button — exception, stack trace, Android version and device — so a crash is something you can send instead of guess at. The last report also stays in `MENU → SET UP → DIAGNOSTICS`.
- **The camera report is long:** `MENU → SET UP → DIAGNOSTICS` has **Copy summary** (just the high-resolution paths, short enough for any clipboard) and **Save as .txt** (the whole report written to `Download/Grain`, so nothing can cut it off).
- **Film asks for a LUT:** Import Fujifilm's F-Log2 `.zip` or Sony's `s709Cubes.zip` via `MENU → I.Q. → IMPORT LUT ZIP`.
- **Update blocked by Xiaomi HyperOS / MIUI:** Grain automatically falls back to Android's system package installer via `FileProvider`; allow installation from Grain once if prompted.

---

## Credits & Version History

### Credits

- **Published Recipes:** **Reggie Ballesteros** (*Reggie's Portra*, *Reggie's Superia*), **Ritchie Roesch / Fuji X Weekly** (*Kodak Gold 200*, *Bright Kodak*, *Kodak Portra 800 v3*, *Kodachrome 64*, *Pacific Blues*, *Vibrant Arizona*, *CineStill 800T*), **Carl Cruz (`@carlcruzsnaps`)** (*Leica X*), and **Anders Lindborg** (*Kodak Tri-X 400*). Every photo receipt links to the recipe's original source.
- **LUTs & Log Curves:** Official 3D LUTs are downloaded directly by the user from Fujifilm and Sony under their respective terms; F-Log2 and S-Log3 / S-Gamut3.Cine implementations follow the published technical specifications.

### Recent Releases

| Version | Highlights |
| --- | --- |
| **v0.8.4** | **The camera report can be saved, not just copied** (`Android`) — **Save as .txt** writes the whole report to `Download/Grain`, **Copy summary** copies only the high-resolution paths in a form that always survives the clipboard, and the report now opens with that summary and puts the high-resolution block first |
| **v0.8.3** | **A real manual-focus scale under the screen** (`Android`) — the stops are engraved with the actual distances the lens reports (`∞` · `1 m` · `0.50 m` · `0.20 m` · `0.10 m` on the Redmi Note 13 Pro+), it drags with a thumb and works while the focus loupe is up. **MENU → SET UP → DIAGNOSTICS** now also dumps the camera's vendor super-resolution and remosaic paths, so we can find out whether the phone has a 108 MP mode of its own before building one |
| **v0.8.2** | **The preview read-back can no longer crash Grain** — all three preview readers (histogram, focus loupe, peaking) now share one guarded read-back, so a frame that cannot be read is simply a frame with no overlay. Grain also now shows its own crash report on the next launch, with a **Copy** button |
| **v0.8.1** | **Two Tripod High-Resolution Modes (`HI-RES 1 36MP` / `HI-RES 2 48MP`)** on Android & iOS — the existing `~36 MP` merge plus a new `~48 MP` (`~8000 × 6000`) merge from the same 4-frame sub-pixel registration — and a **fix for the focus-peaking crash** (preview read-back can no longer take the app down) |
| **v0.8.0** | **Tripod 4-Shot `~36 MP` High-Resolution Mode (`HI-RES 4×` / `HI-RES 36MP`)** on Android & iOS: `4 s` tripod settle countdown, haptic/Taptic OIS sub-pixel micro-shift, 4-frame RAW super-resolution merge (~`6912 × 5184`), and strip-tiled low-RAM film development |
| **v0.7.10** | **Recipe-named photo & RAW filenames + EXIF descriptions** (`Grain_<Recipe_Name>_YYYYMMDD_HHMMSS.jpg` / `.dng`) in Apple Photos `ⓘ` File Info, Android Gallery, Share & Darkroom Roll |
| **v0.7.9** | **iOS Bayer RAW capture hardening** (`photoQualityPrioritization = .speed` + `videoZoomFactor = 1.0` sensor crop) ensuring rock-solid 12MP `.dng` capture on **iPhone XR / XS & all iOS 16+ iPhones** |
| **v0.7.8** | **Smooth `1×–8×` gallery photo zoom** on the rear Command slider (with drag-to-pan, pinch & double-tap) while the Focus Lever browses frames |
| **v0.7.7** | **Native iOS `.ipa` release** (`ios/` Swift + `AVFoundation` Bayer RAW & all-iPhone scene-linear engine) published alongside Android `.apk` |
| **v0.7.6** | Enlarged **360° analog Focus Lever** (`74×74`, `2.6×` thumb cap) with 60/120Hz VSync AF gliding, zero-lag reversal, damped spring return & menu hold-repeat |
| **v0.7.5** | Authentic **CineStill 800T (`800T`)** Vision3 tungsten + remjet-free red-orange halation & **Kodak 800 v3 (`K800`)** RAF-calibrated Kelvin fix |
| **v0.7.4** | Added **Kodak 800 v3 (`K800`)** & **Leica X (`LCX`)** with phone-sensor tone, highlight, shadow & perceptual `OKLab` colour calibration |
| **v0.7.3** | **Pure Blackout Optical Viewfinder** (top-left eyepiece window only, `#000000` body & hidden shutter ring) |
| **v0.7.2** | **Gallery multi-select batch Delete & Save** + minimalist 4-step hardware onboarding |
| **v0.7.1** | Xiaomi HyperOS / MIUI `FileProvider` in-app updater fix |
| **v0.7.0** | Closed-loop **P / S / ISO / M** shutter & ISO dial solver, **Bright Kodak (`BKD`)**, and hardware **Flash (`⚡`)** |
| **v0.6.0** | **`4× / 8×` Focus Check magnifier loupe**, **AF-C face tracking**, **focus bracketing**, and SHA-256 in-app updates |
| **v0.5.x** | 30-step manual focus, focus peaking, AF area modes, AF lock, and Camera2 diagnostics |
| **v0.4.0** | Full rangefinder camera back: dials, joystick lever, EVF, AE-L, and film bracketing |
| **v0.3.x** | Sony `s709`, Face exposure, fitted `FX Blue`, *Reggie's Superia*, Clean/First-version development, and published recipe suite |
| **v0.2.x** | On-device Bayer RAW engine, official Fujifilm F-Log2 LUT pipeline, per-film tuning, Clarity, Halation & Bloom |

Full history: **[CHANGELOG.md](CHANGELOG.md)**.
