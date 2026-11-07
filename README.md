<div align="center">

# Grain Photo

**A native Android camera that develops film from RAW.**

Four capture-time ISP looks and Fujifilm / Sony films developed from the phone's sensor RAW,
wrapped in the back of a Fujifilm camera.

[▶ Watch Grain on the phone](https://github.com/user-attachments/assets/8a401c48-bb28-4abb-abd6-3a3d80443ecc)

[![Build and release](https://github.com/astanabesta/Grain/actions/workflows/build-apk.yml/badge.svg?branch=main)](https://github.com/astanabesta/Grain/actions/workflows/build-apk.yml)
[![Latest release](https://img.shields.io/github/v/release/astanabesta/Grain?include_prereleases&label=release&color=b5532a)](https://github.com/astanabesta/Grain/releases)
[![Android](https://img.shields.io/badge/Android-10%2B-3ddc84?logo=android&logoColor=white)](#compatibility-and-test-status)


</div>

> **Beta notice.** Grain is a personal build for one phone, shared as-is. It is not on the Play
> Store and it is not yet validated across devices or lighting. Keep irreplaceable photos out of the
> private gallery until you have tested it: uninstalling deletes them.

## Contents

**Getting started**
- [What is Grain?](#what-is-grain)
- [Highlights](#highlights)
- [Download and install](#download-and-install)
- [One-time setup: LUT packs](#one-time-setup-lut-packs)
- [Using the camera](#using-the-camera)

**Reference**
- [Films and recipes](#films-and-recipes)
- [How RAW development works](#how-raw-development-works)
- [Privacy and data](#privacy-and-data)
- [Compatibility and test status](#compatibility-and-test-status)

**Project**
- [Building from source](#building-from-source)
- [Project layout](#project-layout)
- [Documentation](#documentation)
- [Known limitations](#known-limitations)
- [Troubleshooting](#troubleshooting)
- [Credits and licensing](#credits-and-licensing)
- [Version history](#version-history)

---

## What is Grain?

Grain is a separate, installable camera app (`dev.grain.photo`) built around two ideas:

1. **A look should be requested at capture, not painted on afterwards.** The four ISP looks send
   supported white balance, tone curves, noise reduction and edge settings to Camera2 *before* the
   shutter fires. The framing crop is geometry only — no second colour filter is applied to the
   finished JPEG.
2. **Film belongs in RAW.** The RAW films (◆ on the dial) take the sensor's `RAW_SENSOR` frame and
   develop it on the phone with the manufacturers' own LUTs and published formulas, so a photo can be tuned and
   re-developed rather than being stuck as a baked JPEG.

On top of that sits a full screen that behaves like the back of a Fujifilm camera: a film dial, a
shutter-speed dial, a command dial, a five-way lever, a memo holder and a knurled shutter button.

All capture and RAW development run 100% on-device with no account, analytics or backend; network
access is used only to check GitHub Releases for app updates and download verified APKs when you
choose to update.

**What it is not:** not a replacement for the stock camera, not a 200 MP mode, not a video recorder,
and not an optical-zoom camera. Zoom and framing are digital.

---

## Highlights

| Area | What you get |
| --- | --- |
| **Camera back** | Fujifilm-style body in portrait and landscape, EVF view, three finishes, engraved on-screen display, working dials, lever and memo holder |
| **Films** | 4 experimental ISP looks, 8 published Fujifilm recipes, every film simulation in the imported official pack, and Sony s709 |
| **RAW pipeline** | Camera2 RAW → DNG → demosaic → DNG colour → F-Log2 / F-Gamut → official LUT → fitted tone model → recipe controls → JPEG |
| **Focus** | Tap / zone / centre / face AF area, AF lock, real manual focus in 30 steps, focus peaking |
| **Exposure** | EV −2…+2 in thirds plus a 0.1 EV custom position, AE-L, face-aware metering, manual shutter and ISO |
| **Capture** | Phone's public JPEG sizes (balanced ≤16 MP, larger ≤24 MP), 3:4 / 1:1 / 9:16, digital 1×–5× zoom, optional full-frame copy, FINE or FINE + DNG |
| **Drive** | Single frame, 3 s / 5 s self-timer, film-simulation bracketing |
| **Gallery** | Private on-device storage, per-photo capture receipt, export to `Pictures/Grain`, share, delete with Undo |
| **Verification** | Unit tests, lint and a signed release APK in GitHub Actions; every photo stores what was requested and what the camera returned |
| **Privacy** | No accounts, no cloud, no network access; photos leave the app only when you export or share them |

---

## Download and install

**Latest build:** [Grain v0.5.3 — device-test beta](https://github.com/astanabesta/Grain/releases/tag/v0.5.3)
(`Grain-v0.5.3.apk` and `SHA256SUMS.txt`). All releases are pre-releases; the
[releases page](https://github.com/astanabesta/Grain/releases) lists every build.

1. Open the newest **Release** and download the APK.
2. Install it. Allow installation from your browser or file manager when Android asks.
3. Grant **Camera** access on first launch — that is the only permission the app requests.

The package is `dev.grain.photo`, so Grain installs alongside any other camera app.

### Requirements

- **Android 10 (API 29) or newer**; compiled and targeted at API 36.
- A **Camera2** camera. The ◆ RAW films additionally need a rear camera that advertises
  `RAW_SENSOR`; the ISP looks work without it.
- Tested only on the **Redmi Note 13 Pro Plus**. Other phones are untested.

### Updating an installed APK

| Coming from | What to do |
| --- | --- |
| v0.6.0 or newer | Updates directly inside the app on launch (or via **MENU → SET UP → APP UPDATE**). |
| v0.2.1 – v0.5.3 | Install v0.6.0 over it once. Photos, imported LUTs and tuning are kept, and future updates happen in-app. |
| v0.2.0 or older | Export the photos you want (**photo → Save to device gallery**), uninstall, install this APK, then re-import the LUT pack. |

Every release from v0.2.1 is signed with the same personal key, certificate SHA-256:

```text
6e073ef68730c0022f8359b668bbfc087e8ec348ad334a1afb962ad06e206553
```

An APK signed with any other certificate will not install over this one. CI keeps the key in the
Actions cache (refreshed by every build); GitHub evicts caches unused for 7 days, after which one
more uninstall would be needed. For an unchanging key, store its base64 encoding as the repository
secret `GRAIN_DEBUG_KEYSTORE_BASE64` and the workflow signs with that instead — note that switching
to it changes the key once.

This is personal test signing, not production signing. Never commit a keystore.

### Building from GitHub Actions

No local Android SDK is needed for the CI route:

1. Open **Actions → Build and Release Grain APK**.
2. Push to `main` starts a build; **Run workflow** starts one by hand.
3. Download the APK from the newest **GitHub Release**, or the retained Actions artifact
   `grain-photo-N` (extract its ZIP first).

A failed run publishes nothing. No GitHub secrets are required for a first build.

---

## One-time setup: LUT packs

| Film family | Needs |
| --- | --- |
| **ISP looks** — Daylight, Coast, Golden, Afterhours | Nothing |
| **◆ Film simulations and recipes** | Fujifilm's official F-Log2 LUT pack |
| **Sony s709** | Sony's `s709Cubes.zip` |

Grain never bundles, commits or uploads third-party LUTs. You download them from the manufacturer
and import them once; they are stored privately inside the app.

### Fujifilm LUT pack

1. Download the free pack from Fujifilm's [LUT download page](https://www.fujifilm-x.com/en-in/support/download/lut/)
   — *GFX ETERNA 55* (v1.10) is the reference pack. In Grain, **MENU → I.Q. → DOWNLOAD LUTS** opens
   the page for you.
2. In Grain: **MENU → I.Q. → IMPORT LUT ZIP** (or *Import LUT pack* in a film's sheet) and choose
   the downloaded zip. No manual unzipping.

That unlocks the 10 film simulations in the official pack plus the published recipes. The pack's two
Rec.709 video transforms (`FLog2-709`, `WDR-709`) are not offered as films.

### Sony s709

1. Download `s709Cubes.zip` (about 0.5 MB) from Sony's
   [S-Log grading guide](https://support.d-imaging.sony.co.jp/support/ilc/movie/en/grading/02.html).
2. In Grain: open the **Sony s709** film's sheet and tap **Import s709 zip**, or use
   **MENU → I.Q. → IMPORT LUT ZIP**.

When a zip contains both `SL3SG3Ctos709.cube` and `SL3SG3tos709.cube`, Grain uses the S-Gamut3.Cine
version.

> Imported LUTs live in private app storage. Re-importing is required after uninstalling Grain or
> moving to a new phone.

---

## Using the camera

The screen is the camera back. Turn the phone sideways and the whole back re-lays out; the preview
follows the sensor.

### The camera back

| Part | What it does |
| --- | --- |
| **Preview** (film window) | Live Camera2 preview cropped to the photo ratio, pinch zoom 1×–5× |
| **Film dial** | The four ISP looks (DAY / CST / GLN / AFT), then every imported simulation and recipe; a published recipe is accented ◆ |
| **Shutter dial** | **A** (auto), then every speed the camera supports from 1/4000 s to 1 s |
| **ISO chip** | Switches the top dial from shutter speed to ISO (A, then 50–12800 as supported) |
| **Command dial** | Zoom 1×–5×, or exposure compensation in 0.1 EV steps when the EV dial is on **C** |
| **Five-way lever** | Centre confirms; up AE-L, right steps the film, down opens the menu, left moves the focus point |
| **Shutter button** | Half-press focuses, full press develops; in FS BKT it takes the bracket |
| **Q** | Quick menu |
| **MENU / OK** | Full menu, or confirms inside a menu |
| **DISP / BACK** | Cycles the OSD information, or goes back |
| **▶** | Playback |
| **DRIVE** | Single / timer / FS BKT, and delete in playback |
| **EYE** | Switches to the EVF view (◀ SCREEN returns) |
| **Memo holder** | Tap: write a note. Long-press: the phone's neutral view, preview only |
| **Power switch** | Off hides the preview, closes the camera and ignores every key except power |

### The quick menu (Q)

Sixteen tiles for the settings that matter while shooting: **Film, WB, WB R, WB B, DR, H TONE,
S TONE, COLOR, SHARP, NR, CLARITY, GRAIN, CCE, FX BLUE, DEV, FACE**. Turn the command dial to change
the selected tile; tiles that do not apply to the current film are shown as not available.

### The full menu

| Tab | Items |
| --- | --- |
| **I.Q.** | Films & tuning, aspect ratio, file format (FINE / FINE + DNG), keep full frame, larger capture, import LUT zip, download LUTs |
| **AF / FACE** | Face exposure, AF area, AF lock, focus mode, MF distance, peaking, focus point, AE lock |
| **SHOOT** | Drive, shutter / ISO, exposure, rear / front camera |
| **SET UP** | Sounds, view mode, screen rotation, histogram, electronic level, framing grid, body finish, all preferences, diagnostics, about |

`SET UP → ALL PREFERENCES` holds the rest: composition grid, full-frame and larger-capture switches,
**Restart camera**, and the RAW film options (**Face exposure**, **Face landmarks (eye AF)**, and LUT
import). `SET UP → DIAGNOSTICS` lists what the phone's Camera2 HAL actually supports; **Copy** puts
the whole report on the clipboard — that is what to send with a bug report.

### Focusing

- **Tap the preview** to place the focus point. The lever's left key moves it too;
  **FOCUS POINT → MOVED · RESET** returns it to the centre.
- **AF AREA** — *SINGLE* (the tapped point), *ZONE* (a larger box around it), *CENTER* or *FACE*.
  *MULTI* appears only if the HAL exposes at least nine AF regions. The chosen region is put on
  every request and drawn on the preview.
- **AF LOCK** holds both the region and the lens, like AE-L for focus.
- **FOCUS MODE → MANUAL** takes autofocus out of the loop and drives the lens directly.
  **MF DISTANCE** moves it in 30 steps from **STEP 0 = INFINITY** to **STEP 30 = the lens' closest
  focus**, interpolated linearly in distance.
- **PEAKING** overlays the preview's edges in white, brighter where contrast is sharper. It reads a
  small 160 × 120 copy of the preview about seven times a second and marks mid-tones only.

Eye AF is **not available** on this phone: its HAL exposes only `OFF` and `SIMPLE` face detection,
which carry no eye or landmark data. *FACE* therefore aims at the detected face rectangle.

### Exposure and capture

- **EV** from −2 to +2 in thirds; the **C** position hands the command dial a 0.1 EV range.
- **AE-L** (lever up, or the AEL key) locks exposure and focus point.
- **Face exposure** (on by default) meters the detected face instead of the whole frame, placing it
  half a stop above the scene key, limited to +3 / −1.5 EV before your own EV is added.
- **Manual shutter and ISO** come from the shutter and ISO dials; set them back to the auto position
  to return to auto exposure. Values the phone refuses are reported in the receipt.
- **FS BKT** takes the current film plus the next two RAW films — three frames per press. With an
  ISP look it takes a single frame, since there is no RAW to develop again.
- **Keep full frame** saves the unzoomed, uncropped capture as well. It still has the recipe baked
  in — it is not a clean original.
- **File format → FINE + DNG** keeps the RAW test files for a photo.
- The **volume keys** fire the shutter while the camera screen is up.

### Playback and gallery

**▶** opens the newest photos, private on this device. Swipe to browse, tap to open, **DISP** shows
the capture receipt, **DRIVE** deletes with a short Undo window. A photo can be saved to
`Pictures/Grain` or shared; nothing is uploaded.

---

## Films and recipes

Two families share one film dial.

**ISP looks** — baked into the JPEG at capture, no LUT, no RAW:

| Look | Character |
| --- | --- |
| **Daylight** | Sun-warmed colour, a gentle shoulder, an ordinary afternoon kept |
| **Coast** | Airy blues, restrained warmth, a little salt in the air |
| **Golden** | A honeyed response without turning every shadow orange |
| **Afterhours** | A deeper response for the other side of the day |

These are original experimental 1D RGB responses, not reproductions of named film stocks.

**◆ RAW films** — developed from the sensor RAW with the manufacturer's own LUT. Eight published
recipes ship as defaults:

| Recipe | Film simulation | White balance | DR | H / S | Color | Grain | CCE / FX Blue | Sharp / NR / Clarity | Typical EV |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Reggie's Portra ¹ | Classic Chrome | Auto R+2 B−4 | DR-Auto | −1 / −1 | +2 | Weak, Small | Strong / Weak | −2 / −4 / 0 | — |
| Reggie's Superia ¹ | Classic Neg. | Auto R+1 B−3 | DR-Auto | −2 / −1 | +1 | Strong, Large | Strong / Strong | −2 / −4 / 0 | — |
| Kodak Gold 200 | Classic Chrome | Daylight R+4 B−5 | DR400 | −1.5 / +0.5 | +3 | Strong, Small | Weak / Off | −2 / −4 / −2 | +⅔ to +1 |
| Kodachrome 64 | Classic Chrome | Daylight R+2 B−5 | DR200 | 0 / +0.5 | +2 | Weak, Small | Strong / Off | +1 / −4 / +3 | 0 to +⅔ |
| Pacific Blues | Classic Neg. | 5800 K R+1 B−3 | DR400 | −2 / +3 | +4 | Strong, Large | Strong / Weak | −2 / −4 / −3 | +⅔ to +1 |
| Vibrant Arizona | Classic Chrome | 4350 K R+6 B−8 | DR-P Strong | set by DR-P | +4 | Weak, Small | Off / Weak | −2 / −4 / −3 | +⅔ to +1⅓ |
| CineStill 800T | Eterna | Fluorescent 3 R−6 B−4 | DR400 | 0 / +2 | +4 | Strong, Large | Strong / Weak | −3 / −4 / −5 | −⅓ to +⅔ |
| Kodak Tri-X 400 | Acros | Daylight R+9 B−9 | DR200 | 0 / +3 | n/a | Strong, Large | Strong / Off (not applied) | +1 / −4 / +4 | +⅓ to +1 |

¹ Developed as *First version* by default; every other film starts on *Clean* (see
[Development](docs/RAW_FUJI_RECIPES.md#development-first-version-and-clean-v031)).

Reggie's Portra and Reggie's Superia are by **Reggie Ballesteros**. Gold 200, Kodachrome 64, Pacific
Blues, Vibrant Arizona and CineStill 800T are by **Ritchie Roesch**; Tri-X 400 is **Anders
Lindborg's** recipe in Ritchie Roesch's X100V version. Typical exposure compensation is shown as
guidance only — Grain never applies it for you.

Every film simulation in the imported pack is also available on its own at standard settings:
Provia, Velvia, Astia, Classic Chrome, Reala Ace, Pro Neg. Std, Classic Neg., Eterna, Eterna Bleach
Bypass and Acros (Pro Neg. Hi and Nostalgic Neg. appear if your pack includes them), plus **Sony
s709**.

### Tuning any RAW film

Every ◆ film can be adjusted in its sheet; the defaults are Fujifilm's standard settings for a
simulation, or the published settings for a recipe. *Reset this look* returns to that default and
each film's tuning is remembered separately.

| Setting | Values |
| --- | --- |
| Dynamic range | DR-Auto, DR100, DR200, DR400, DR-P Weak, DR-P Strong |
| White balance | Auto, Daylight, Shade, Fluorescent 1–3, Incandescent, Kelvin (2500–10000 K in 50 K steps) |
| WB shift | R and B, −9…+9, in every white-balance mode |
| Highlight / Shadow | −2…+4 in 0.5 steps (locked under D-Range Priority) |
| Color | −4…+4 (hidden for black and white) |
| Sharpness / High ISO NR | −4…+4 |
| Clarity | −5…+5 |
| Grain | Off, Weak / Strong × Small / Large |
| Color Chrome Effect / FX Blue | Off, Weak, Strong (hidden for black and white) |
| Filter (Acros) | None, +Ye, +R, +G |
| Development (Grain extra) | Clean, First version |
| Halation / Bloom (Grain extras) | Off, 1–10 |

Halation and Bloom are Grain's own extras, not Fujifilm settings, and are off by default. Full
details, measured behaviour and evidence: **[docs/RAW_FUJI_RECIPES.md](docs/RAW_FUJI_RECIPES.md)**.

---

## How RAW development works

A ◆ photo is one exposure that produces three things: the developed JPEG, a DNG for re-developing,
and a JSON receipt recording every value used.

1. **Capture** — Camera2 returns `RAW_SENSOR` and the phone's JPEG from the same exposure, paired by
   sensor timestamp. AE runs normally with highlight headroom for the chosen DR, the lens-shading
   map is requested, and the capture records black/white levels, the as-shot neutral, colour and
   forward matrices, illuminants, ISO, exposure and noise profile. A DNG is written with
   `DngCreator`.
2. **Sensor to scene** — black subtraction, lens shading, white balance (as-shot for Auto, the
   mode's measured white point for a fixed WB, plus the shift), Malvar–He–Cutler demosaic with a
   soft highlight roll-off, DNG colour processing, and scene-linear BT.2020.
3. **Exposure** — a centre-weighted log-average is placed at the key fitted on Fujifilm JPEGs
   (0.148). A detected face moves it so the face sits half a stop above the key. Your EV is applied
   here.
4. **Tone** — F-Log2 encoding, plus stills offsets fitted on public Fujifilm RAF/JPEG pairs, a
   highlight shoulder stretched where the phone's RAW clips, and the Highlight / Shadow / DR
   modifiers.
5. **Film simulation** — Fujifilm's official 33-point `FLog2_to_<SIM>` LUT, trilinear. (Sony s709
   instead converts every sample to S-Log3 / S-Gamut3.Cine and looks it up in Sony's table.)
6. **Noise** — a noise curve measured per frame, a quarter-resolution pass for blotchy luma and
   chroma noise, a pixel-scale guided filter, and recipe sharpening.
7. **Recipe controls** — Color, Color Chrome, FX Blue, grain, WB shift, Clarity, the Acros filters,
   and the optional Halation / Bloom.
8. **Output** — JPEG at quality 95 with EXIF copied from the phone JPEG, saved upright, plus the
   receipt's `development` block with per-stage timings.

The engine is pure Java under `develop/` and is covered by JUnit tests, including golden hashes that
pin older behaviours bit-for-bit.

---

## Privacy and data

- **No accounts, analytics or telemetry.** Your photos, LUTs and settings never leave the device;
  network access (`INTERNET`) is used solely to query `api.github.com/repos/astanabesta/Grain/releases`
  for new Grain releases and download SHA-256-verified update APKs when requested.
- **Photos are private to the app** until you export or share one. Export writes to
  `Pictures/Grain` through MediaStore.
- **One capture at a time**, and a photo is committed atomically — nothing appears in the gallery
  until it is complete.
- **Location is never requested or added.** The camera request carries no GPS; cropped output does
  not copy EXIF GPS.
- **Delete is a rename with a short Undo window**, after which the file is removed.
- **Uninstalling removes the private gallery and imported LUTs.** Export anything you want to keep
  first. There is no cloud backup by design.

---

## Compatibility and test status

**Verified in this repository**

- All Java sources parse; 1,230 pure-Java crop / curve / preview cases pass without an Android SDK.
- JUnit covers the RAW engine: F-Log2 round trip, tone table, LUT identity, neutral preservation,
  demosaic, noise reduction, recipe defaults and every tuning clamp.
- Android unit tests, lint and `assembleRelease` run on GitHub's ephemeral runner; a release is
  published only after they pass.

**Verified on the Redmi Note 13 Pro+**

- The v0.1.2 viewfinder is upright.
- From the v0.5.0 diagnostics: `maxRegionsAf` = 1, `faceDetectModes` = `[OFF, SIMPLE]`,
  `manualSensor` = true, `minFocusDistance` = 10, and `LENS_FOCUS_DISTANCE` is accepted.
- Reggie's Portra on *First version* reproduces the v0.2.0 engine bit-for-bit on the phone test DNG.

**Not yet verified**

- Colour, tone and white balance against reference cameras across lighting conditions.
- Development time on-device under load (`timingMs` in the receipt; target about 2 s).
- Memory, thermal, storage-full and lifecycle behaviour on long shoots.
- Anything on a phone other than this one.

Detailed checklist: **[docs/DEVICE_TEST_PLAN.md](docs/DEVICE_TEST_PLAN.md)**.

---

## Building from source

**Prerequisites:** JDK 17, Android SDK platform 36 and build-tools 36.0.0, Gradle 8.13. There is no
Gradle wrapper in the repository — CI installs Gradle itself.

```bash
git clone https://github.com/astanabesta/Grain.git
cd Grain

# Point Gradle at your SDK
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties

# Unit tests, lint and a signed release APK
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`. The `release` build type is not
debuggable on purpose: ART then compiles the RAW developer properly, which is much faster on the
phone.

Signing is read from `GRAIN_KEYSTORE` (default: `~/.android/debug.keystore`, alias
`androiddebugkey`, password `android`). CI replaces it with the cached personal key described above.

### SDK-free checks

With JDK 17+ and Bash 4+, no Android SDK needed:

```bash
bash tools/check-source.sh
```

This parses every Java source and runs the crop, curve and preview smoke cases. It does **not**
compile the Android app; the CI build does that.

### Camera-back screenshots without a phone

```bash
GRAIN_SCREENS=1 gradle :app:testDebugUnitTest
```

`CameraBackScreensTest` (Robolectric) renders the camera back to `app/build/screens`. On `dev/**`
pushes, CI publishes those to the `ci-screens` branch.

### Desktop RAW harness

`tools/DevelopRaw.java` develops a raw Bayer dump with any `.cube` file and writes a PPM, with no
Android involved. It is how the engine was compared against LibRaw and how recipes are checked.

```bash
javac -d /tmp/grain $(find app/src/main/java/dev/grain/photo/develop -name '*.java') tools/DevelopRaw.java
java -cp /tmp/grain DevelopRaw raw.u16le meta.txt lut.cube out.ppm
```

`-Drecipe=reggies-superia`, `-Dwb=DAYLIGHT`, `-Ddr=DR400`, `-Dev=+0.5` and the other properties
documented at the top of the file override single settings.

---

## Project layout

```text
.github/
  workflows/build-apk.yml     GitHub-only APK build, test, lint and release
  release-notes.md            Template for the published release notes
app/src/main/java/dev/grain/photo/
  MainActivity.java           Camera UI, menus, preferences, gallery, focus and exposure logic
  CameraBack.java             The drawn Fujifilm body: dials, lever, memo, EVF layout, finishes
  GrainCamera.java            Camera2 session, capability negotiation, still capture, receipts
  GrainStyle.java             Line icons, shutter artwork, ratio diagrams, print thumbnail
  Recipe.java                 The film dial: ISP looks, simulations and published recipes
  RecipeTuning.java           Per-film saved tuning
  LutLibrary.java             Imports Fujifilm / Sony LUT zips into private app storage
  RawSupport.java             RAW_SENSOR probe, calibration copy, DNG writing
  RawDevelopment.java         Runs the RAW engine and writes the EXIF-tagged JPEG
  Framing.java                Shared crop / focus / preview geometry
  PreviewMath.java            Preview orientation math
  CurveMath.java              Monotonic ISP tone curves
  PhotoStore.java             EXIF, atomic private storage, export and trash
  develop/                    Pure-Java RAW engine
    RawDeveloper.java         Demosaic, colour, tone, LUT, noise, recipe controls
    ColorMath.java            White points, matrices, chromatic adaptation
    FujiRecipe.java           Published recipes, simulations, tuning ranges
    ToneModel.java            Fitted stills tone curve and shoulder
    SLog3.java / FLog2.java   Sony and Fujifilm log encodings
    CubeLut.java              .cube parsing and trilinear lookup
    Faces.java                Face rectangles in RAW coordinates
    TuningControl.java        The controls the menus expose
app/src/test/java/dev/grain/photo/
  RawEngineTest.java          Engine goldens and recipe behaviour
  CameraBackScreensTest.java  Robolectric screenshots (opt-in)
  ...                         Crop, curve, preview and capability tests
design/grain-prototype.html   Original interactive design reference
docs/                         Architecture, recipes, research, test plans, audit
tools/                        SDK-free syntax/math checks and the desktop RAW harness
CHANGELOG.md                  Full version history
NOTICE.md                     Provenance and third-party credits
```

---

## Documentation

| Document | What is in it |
| --- | --- |
| [RAW_FUJI_RECIPES.md](docs/RAW_FUJI_RECIPES.md) | The recipes, every setting, the full development pipeline, white-balance measurements, face exposure, FX Blue, s709 and the camera back |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Product decisions, the capture path, safety and storage rules, module responsibilities |
| [DESIGN_PARITY.md](docs/DESIGN_PARITY.md) | How the native UI follows the HTML design, and where it intentionally differs |
| [DEVICE_TEST_PLAN.md](docs/DEVICE_TEST_PLAN.md) | The acceptance checklist for the phone |
| [VALIDATION.md](docs/VALIDATION.md) | What has actually been run and what has not been claimed |
| [TONE_SAMPLING.md](docs/TONE_SAMPLING.md) | The v0.1.3 shadow-sampling diagnosis |
| [CHROMA_RESEARCH.md](docs/CHROMA_RESEARCH.md) | What Chroma's Fuji-style LUTs are, and what they are not |
| [REPO_AUDIT.md](docs/REPO_AUDIT.md) | What was reused from Zircon Cinema, and its limits |
| [WORKSPACE_CLEANUP.md](docs/WORKSPACE_CLEANUP.md) | Workspace and artifact footprint |
| [CHANGELOG.md](CHANGELOG.md) | Every release, newest first |

---

## Known limitations

**By design**

- **ISP looks are JPEG.** The look is applied by the camera pipeline at capture; it cannot be undone.
  "Keep full frame" is a recipe-baked copy, not a clean original.
- **Zoom is digital.** Pinch or the command dial crops a 1× source; output resolution falls as you
  zoom. There is no claim of access to the stock camera's 200 MP mode, and no fake optical controls.
- **No burst, zero-shutter-lag or flash capture.** One photo is captured and saved at a time.
- **HEIF is not implemented.** Video is not implemented; this is a stills camera.

**Approximations**

- The four ISP looks are original experimental RGB responses, not film-stock reproductions.
- Fujifilm's LUTs, Sony's s709 table and their published formulas are exact. Grain's stills tone
  model, grain, Color Chrome, FX Blue, WB-shift step, DR-Auto rule, D-Range Priority curve and Acros
  filters are Grain's own approximations and are still being tested on the phone.
- Fixed white-balance presets are measured from Fujifilm RAW files with roughly ±150 K uncertainty.
  The preview uses the nearest phone preset; the developed photo uses the exact white point.
- A returned Camera2 mode or accepted request is not proof of matching pixels. Check the receipt.

**Not available on this phone**

- **Eye AF** — the HAL exposes only `SIMPLE` face detection, with no landmarks.
- **Multi-point AF grid** — the HAL accepts a single AF region.
- Still planned: magnifier, AF-C face tracking, focus bracketing.

**Not implemented at all:** gallery re-editing, cloud backup, Play Store signing, calibrated
Kelvin/tint gains, and importing any LUT outside the Fujifilm pack and Sony s709.

---

## Troubleshooting

| Symptom | What to do |
| --- | --- |
| A film asks for its LUT when you pick it | Import the Fujifilm pack (or Sony's s709 zip for Sony s709). See [One-time setup](#one-time-setup-lut-packs). |
| "RAW unavailable" when shooting | The camera does not advertise `RAW_SENSOR`, or another app holds the camera. Use an ISP look, close other camera apps, or restart the camera (`SET UP → ALL PREFERENCES → Restart camera`). |
| The APK will not install over the old one | The signing certificate differs — save your photos, uninstall, then install. |
| Manual focus does nothing | Check `SET UP → DIAGNOSTICS`; if the HAL does not accept `LENS_FOCUS_DISTANCE`, the receipt says so in `warnings` instead of guessing a distance. |
| Photos disappeared | Uninstalling or clearing app data deletes the private gallery. They can only be recovered from an earlier export. |
| Photos look too dark or too bright | Start at 0 EV with Face exposure on; then use the EV dial or Highlight / Shadow. The receipt shows the metering decisions. |
| A build failed | Open the failed run, download `grain-build-reports-N`; the workflow also annotates the first lint errors inline. |
| Something else | `SET UP → DIAGNOSTICS → Copy` and attach it, plus the photo's capture receipt. |

---

## Credits and licensing

**Recipes.** Reggie's Portra and Reggie's Superia by Reggie Ballesteros. Kodak Gold 200, Kodachrome
64, Pacific Blues, Vibrant Arizona and CineStill 800T by Ritchie Roesch (Fuji X Weekly); Kodak
Tri-X 400 is Anders Lindborg's recipe in Ritchie Roesch's X100V version. Sources are linked in
`FujiRecipe` and in every photo's receipt.

**LUTs and formulas.** Fujifilm's film-simulation LUTs and Sony's s709 table are downloaded and
imported by you under their own terms; Grain never bundles or redistributes them. Fujifilm's F-Log2
and Sony's S-Log3 / S-Gamut3.Cine formulas follow the manufacturers' published documents.

**Design.** `design/grain-prototype.html` is the original interactive reference. Its sample
photography is AI-generated and demonstrates interface behaviour only; the app uses the real device
camera.

**Licensing.** Android, Java, Gradle, the Android Gradle Plugin and AndroidX ExifInterface are used
under their respective licenses. No MotionCam source or third-party `.cube` assets are included.
This repository does not yet carry a project license; choose one before public redistribution. See
[NOTICE.md](NOTICE.md) for provenance.

---

## Version history

| Version | Headline |
| --- | --- |
| **v0.6.0** | Held-to-magnify loupe (`4×`/`8×`), AF-C face tracking, focus bracketing & in-app updates |
| v0.5.3 | Real manual focus in 30 steps + focus peaking |
| v0.5.2 | AF menu matched to the phone's real capabilities (no multi-point AF, no eye AF) |
| v0.5.1 | AF area (single / zone / centre / face) and AF lock |
| v0.5.0 | Focus probe: full Camera2 capability diagnostics |
| v0.4.0 | The Fujifilm camera back: dials, lever, EVF, AE-L, bracketing, manual shutter / ISO |
| v0.3.5 | Sony s709 |
| v0.3.4 | Face exposure |
| v0.3.3 | Color Chrome FX Blue that deepens skies |
| v0.3.2 | Reggie's Superia |
| v0.3.1 | Development: First version / Clean |
| v0.3.0 | Six more Fujifilm recipes, fixed white balance, D-Range Priority, Acros filters |
| v0.2.3 | Per-film tuning, Clarity, Halation, Bloom |
| v0.2.2 | Every Fujifilm film simulation |
| v0.2.1 | Faster, cleaner RAW development |
| v0.2.0 | First Fujifilm recipe from RAW |
| v0.1.x | UI revision, upright viewfinder, shadow tone sampling |

Full notes: **[CHANGELOG.md](CHANGELOG.md)**.
