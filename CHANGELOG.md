# Changelog

Every release of Grain Photo, newest first. Grain is a personal **device-test beta** for the
Redmi Note 13 Pro+; versions below are the builds installed on that phone.

Format: `vMAJOR.MINOR.PATCH` — the patch number also counts small feature releases, because each
one was a separate APK install on the device.

---

## v0.8.6 — A Startup Failure Should Show Itself, Not Just Die (`Android`)

**Android only for now**; the iOS build follows later.

- **v0.8.5 (the guided nine-shot 112 MP mode) was reported to crash on launch** on the Redmi Note
  13 Pro+. This release does not guess at the cause — it makes the failure visible and survivable,
  so the real reason can be found and fixed.
- **The app no longer dies silently at startup** — a failure while opening is shown in a dialog
  with a **Copy report** button, and Grain stays open instead of vanishing into "Grain keeps
  stopping". The crash report also appears half a second after launch now, so a crash in the very
  first second still reports itself.
- **Every crash now leaves a file on the phone** — `Grain-crash-<date>.txt` in `Download/Grain`.
  If the app cannot open at all, the reason is still a file that can be picked up and sent.
- **The update check starts almost immediately** after launch, so a build that dies a second after
  opening can still be replaced from inside the app.

## v0.8.5 — A Guided Nine-Shot 112 MP Mode (`Android`)

**Android only for now**; the iOS build follows later.

- **`HI-RES 3 112MP`** — a new drive mode that shoots **nine** RAW frames of the same scene and
  merges them into roughly **`12216 × 9164` (~112 MP)** from the **same field of view**. Nothing is
  cropped or panned: the phone held sideways gives a landscape picture, because the orientation is
  in the EXIF. Nine frames give nine samples of every original pixel, which is what a **3× linear**
  reconstruction needs.
- **The phase guide** — while the nine shots are taken, the screen shows a **3×3 grid**: the phase
  space of one original sensor pixel, where each cell is one of the nine sub-pixel positions a
  frame can land on. A cell lights up as its frame is measured, a **drift meter** under the grid
  shows how far the last frame moved from the first (green / amber / red), **corner ticks** mark
  the frame edges and the counter runs `1/9` … `9/9`. The first shot asks for a tripod.
- **The exposure is now hard-locked** for the whole sequence — nine frames averaged together cannot
  afford the brightness moving between them. This also fixes the four-shot modes, where the lock
  was never actually applied on a phone with focus bracketing.
- **The merge uses one working buffer instead of two**, so a 112 MP merge needs about half the
  memory the old design needed, and the accumulator can no longer overflow at nine frames.
- **It checks the phone's memory first** and merges smaller if 112 MP will not fit, rather than
  dying half way through; the receipt records the size that was really merged.

## v0.8.4 — The Camera Report Can Now Be Saved, Not Just Copied (`Android`)

- **The camera report can be saved as a text file** — the diagnostics dialog now has a
  **Save as .txt** button that writes the whole report to `Download/Grain`. A long report could come
  out cut off when copied, so it can now be handed over as a real file.
- **A short summary to copy** — a **Copy summary** button copies only the lines that decide how
  large a photo the phone can take: the biggest public output size for JPEG, RAW and YUV, the
  Android 13+ maximum-resolution map, the vendor super-resolution and remosaic switches, and the
  values behind them. It is short enough to survive any clipboard, so it always arrives intact.
- **The report opens with that summary**, and the high-resolution block now comes **first** in the
  JSON, so even a truncated copy still contains the part that matters.

## v0.8.3 — A Real Manual-Focus Scale, and a Probe for the 108 MP Path (`Android`)

Both changes are **Android only for now**; the iOS build follows later.

- **A manual-focus scale under the screen** — Pick `MANUAL` in `MENU → FOCUS MODE` and a focus scale
  appears directly under the display, in the band between the screen and the bottom deck. It shows
  only while manual focus is selected. The stops are **real distances read from the lens itself**:
  a lens focuses by dioptres and the distance is the reciprocal, so the scale is stretched at the
  far end and crowded at the near end, like the distance scale on a lens barrel. The Redmi Note 13
  Pro+ reports a closest focus of **10 cm**, so the scale runs from **∞** to **10 cm** and is
  engraved **∞ · 1 m · 0.50 m · 0.20 m · 0.10 m**, with the exact distance under the thumb while you
  drag. It works while the focus loupe is up. The `MF DISTANCE` menu item, the magnifier label and
  the focus toast now read the distance (`0.30 m`) instead of `STEP 7 / 30`.
- **The HAL probe** — `MENU → SET UP → DIAGNOSTICS` now also dumps what the camera's own hardware
  says about its high-resolution paths: the vendor super-resolution and remosaic characteristics,
  the vendor request keys that switch them on, and every output size the scaler advertises,
  including the Android 13+ maximum-resolution map. The public Camera2 sizes on this phone stop at
  **4080 × 3060 (12.5 MP)** for both JPEG and RAW, so any bigger mode has to come from the phone's
  own engine; the probe establishes whether one exists before any of it is built.

## v0.8.2 — The Preview Read-Back Can No Longer Crash Grain (`Android`)

- **The real cause of the focus peaking crash, found and fixed** — Turning on `PEAKING` and then
  leaving the menu could take Grain down. The culprit was not the peaking code but the **live
  histogram**, which reads the same preview pixels and had no guard at all. Reading a `TextureView`
  back needs its hardware layer, and Android throws instead of returning nothing when it cannot get
  one — a window that opens right after the view tree changes, when closing the menu re-lays out the
  camera back and rebuilds the preview surface. The histogram resumed its read-back in exactly that
  window. All three preview readers — the **histogram**, the **focus loupe** and the **peaking** —
  now go through one shared, guarded read-back: a frame that cannot be read is simply a frame with
  no histogram, no loupe image and no peaking overlay.
- **The read-back is also held off after the view tree changes**, and a bitmap that comes back the
  wrong size is rejected instead of being read past its end.
- **Grain now remembers its own crashes** — If Grain crashes, the next launch shows the crash
  report on screen with a **Copy** button, so a crash is something you can send rather than guess
  at. The report carries the exception, the stack trace, the Android version and the exact device,
  and the last one also stays available in `MENU → SET UP → DIAGNOSTICS`.

## v0.8.1 — Two Tripod High-Resolution Modes (`36 MP` / `48 MP`) & Focus Peaking Fix (`Android` + `iOS`)

- **Focus peaking no longer crashes** — Turning on `PEAKING` could take the app down. The preview
  read-back now waits for the viewfinder to have a real size, and a failed read-back can never take
  the camera down with it. On a device that cannot read the preview back at all, Grain switches
  peaking off and says so instead of crashing.
- **Two tripod high-resolution modes (`HI-RES 1 36MP` / `HI-RES 2 48MP`)** — The `DRIVE` dial now
  offers `HI-RES 1 36MP` (the existing `~36 MP` merge, unchanged at `~6928 × 5196`) and the new
  `HI-RES 2 48MP` (`~8000 × 6000`), resolving more detail from the same 12 MP sensor frame using
  the same 4-frame `4×4` sub-pixel tile registration, 4-second tripod settle, locked AE/AF and
  haptic/Taptic OIS micro-shift. The merge never upscales past `2×` linear, so a sensor below
  `12 MP` lands under `48 MP` and the receipt records the real output size.
- **Receipts and gallery record which merge you shot** — Hi-Res frames now carry their nominal merge
  size (`36 MP` or `48 MP`) alongside the sub-pixel shift diagnostics, and the Darkroom gallery shows
  it on each frame on both platforms.

## v0.8.0 — Tripod 4-Shot `~36 MP` High-Resolution Mode (`Android` + `iOS`)

- **Selectable `HI-RES 4×` / `HI-RES 36MP` Drive Mode (`DRIVE` button & `SHOOTING MENU`)** —
  stationary 4-shot super-resolution mode engineered specifically for tripod shooting across all
  28 films & published recipes.
- **4-second Tripod Settle Countdown + OIS Micro-Shift** — waits a full `4 s` after tapping the
  shutter (cancellable by tapping shutter again) so finger and tripod vibration dampen completely,
  locks AE & AF on Frame 1, and triggers calibrated micro-pulses (`Vibrator` / `UIImpactFeedbackGenerator`)
  before Frames 2–4 (`+38 ms` settle) to nudge the spring-mounted OIS lens by ~`0.35–0.75 px` for
  genuine sub-pixel phase diversity.
- **Sub-Pixel Multi-Frame RAW Super-Resolution (`~36 MP`, `~6912 × 5184`)** — computes `4×4` tile
  parabolic `0.05 px` sub-pixel registration, anti-ghost weights moving leaves/water back to the
  anchor frame, cuts shadow photon shot noise by `2×` (`+1 EV` cleaner shadows), and develops the
  merged `~36 MP` negative through Grain's full linear F-Log2 / S-Log3 + 3D LUT + `OKLab` pipeline
  with low-RAM streaming accumulation & row-strip tiling (`Grain_<Recipe>_HiRes_<Date>_<Time>.jpg`).

## v0.7.10 — Recipe Name in Photo Filenames & File Info (`Android` + `iOS`)

- **Recipe-named filenames (`Grain_<Recipe_Name>_<Date>_<Time>.jpg` / `.dng`)** — saved, exported,
  and shared photos (plus kept `.dng` RAW files) now include the exact film recipe name instead of
  bare numbers (e.g. `Grain_Kodak_Portra_800_v3_20261108_143512.jpg`, `Grain_Leica_X_...jpg`,
  `Grain_CineStill_800T_...jpg`, `Grain_Reggies_Portra_...jpg`).
- **Apple Photos `ⓘ` File Info & Android Gallery Info** — sets `PHAssetResourceCreationOptions.originalFilename`
  on iOS (so Photos `ⓘ` Info shows the recipe filename instead of `IMG_XXXX.JPG`) and `MediaStore`
  `DISPLAY_NAME` + EXIF `ImageDescription` / `UserComment` on both iOS and Android, including for
  older photos already on your roll when exported or shared.

## v0.7.9 — Hardware Bayer RAW Capture Hardening for iPhone XR / XS & All iPhones

- **Enforces AVFoundation Bayer RAW capture invariants on all iPhones (`iPhone XR`–`iPhone 16`)** —
  sets `photoQualityPrioritization = .speed` for single-exposure sensor Bayer RAW `.dng` captures and
  locks `videoZoomFactor = 1.0` during RAW readout while applying digital zoom crop cleanly in
  `GrainDeveloper`, preventing `AVCapturePhotoOutput` Objective-C exceptions on non-ProRAW iPhones.

## v0.7.8 — Smooth Gallery Photo Zoom on Command Slider (`1×–8×`)

- **Command slider zooms playback photos (`1.0× – 8.0×`)** — in `PLAY` mode (Android & iOS), sliding
  the rear `COMMAND` bar smoothly zooms in and out on the current photo with tactile haptic detents
  and `3200px` high-resolution decoding, while the Focus Lever continues browsing between frames.
- **Zoomed drag-to-pan, pinch & double-tap** — drag with one finger while zoomed in to inspect sharpness,
  grain, and halation anywhere in the frame, pinch to zoom, or double-tap to toggle `1.0× ↔ 2.5×`.

## v0.7.7 — Native iOS `.ipa` Port (Pro Bayer RAW + All-iPhone Scene-Linear Engine)

- **Native Swift iOS app (`ios/`) & `Grain-iOS-v0.7.7.ipa`** — full iOS 16+ port built on `AVFoundation`,
  `CoreImage` (`CIRAWFilter` Extended Linear Rec.2020), and `SwiftUI`, supporting both hardware **Bayer
  RAW DNG** on Pro iPhones and **scene-linear Display-P3/sRGB shoulder-expanded F-Log2 development** on
  standard non-Pro iPhones.
- **Feature parity across all 28 films & controls** — ships all 11 published recipes (including sensor-
  calibrated `Kodak 800 v3`, `Leica X`, and `CineStill 800T` red-orange halation), pure-Swift `.zip`/`.cube`
  LUT importer + out-of-the-box analytical F-Log2 LUTs, 120Hz `CADisplayLink` 360° analog Focus Lever,
  Pure Blackout Optical Viewfinder with hardware volume shutter, `4×/8×` magnifier loupe, and multi-select
  gallery batch Delete (7s Undo) & Save.
- **Unified CI release workflow** — `.github/workflows/build-apk.yml` builds Android (`ubuntu-24.04`) and
  iOS (`macos-15` + XcodeGen/Xcode) in parallel and publishes both `.apk` and `.ipa` with `SHA256SUMS.txt`.

## v0.7.6 — Enlarged 360° Analog Focus Lever (VSync AF Glide & Hold-Repeat)

- **Larger, tactile Fujifilm focus stick** — enlarged `CameraBack.Lever` from `56×56` to **`74×74`**
  design units (`72×72` in landscape) with a `2.6×` larger concave micro-knurled thumb cap, recessed
  gimbal socket, cast stem shadow, active amber directional chevrons, and damped `OvershootInterpolator`
  spring return on release.
- **Fluid 360° analog VSync AF gliding** — replaced discrete `ACTION_UP` 4-way jumps with continuous
  2D analog `ACTION_MOVE` tracking driven at native display VSync (`60 / 90 / 120 Hz`), dual-slope
  precision velocity curve, floating-origin zero-lag direction reversal, automatic `AF_CENTER` /
  `AF_FACE` steerable unlock, and paced live Camera2 focus requests.
- **Instant menu step & hold-to-repeat** — in `Q`, `MENU`, and `PLAY`, tilting engages immediately
  on threshold entry and auto-repeats smoothly while held with axis hysteresis against diagonal
  chatter.

## v0.7.5 — Authentic CineStill 800T (`800T`) & Kodak 800 v3 Kelvin Calibration

- **CineStill 800T (`800T`) phone-sensor Vision3 500T calibration** — upgrades `cinestill-800t`
  (`Eterna`) with true `3450K` tungsten-cinema white balance (replacing the greenish `Fluorescent 3`
  approximation), automatic scene-linear **remjet-free red-orange pressure-plate halation** around
  both clipped and bright unclipped night lamps/neon (`> +1.85 EV`), `-1.0 EV` low-light photon
  headroom, grounded `Shadow +2` night blacks, perceptual `OKLab` `Color +4` / `Color Chrome Strong`,
  emerald-mint fluorescent greens, and cyan-teal shadow × warm-amber highlight split-toning.
- **Kodak 800 v3 (`K800`) RAF Kelvin calibration** — maps Fujifilm's menu Kelvin (`6600K`) through
  Fujifilm's RAF-measured Planckian white-point curve (`5693K` DNG instead of overshooting `600K`
  past Shade), producing clean creamy Portra 800 whites and natural peach-warm skin tones.

## v0.7.4 — Kodak 800 v3 (`K800`) & Leica X (`LCX`) with Phone-Sensor Calibration

- **Kodak 800 v3 (`K800`)** — adds Ritchie Roesch's *Kodak Portra 800 v3* (`Classic Chrome`, `DR400`,
  `6600K R −1 B −3`, `Highlight −2`, `Shadow −0.5`, `Color +3`, `Clarity −3`, `Grain Strong / Large`,
  `Color Chrome Strong`, `FX Blue Weak`) with soft highlight shoulder roll-off, `-1.25 EV` capture
  headroom, open Portra shadow toe, gentle emulsion highlight micro-bloom, peach-gold skin
  protection, and pine-sage foliage hue calibration.
- **Leica X (`LCX`)** — adds Carl Cruz's *Leica X* (`Classic Chrome`, `DR-Auto`, `Auto WB R +1 B −2`,
  `Highlight −1`, `Shadow 0`, `Color +4`, `Grain Off`, `Color Chrome Strong`, `FX Blue Strong`) with
  Summilux midtone micro-contrast, dehazed optical black toe, `-2/3 EV` specular protection,
  vermilion-crimson Leica reds, polarized cobalt skies, grounded emerald-forest greens, skin-hue
  restraint, and hue-preserving soft gamut compression.

## v0.7.3 — Pure Blackout Optical Viewfinder

- **Top-left eyepiece blackout mode** — tapping `EYE` turns the entire camera body, dials, rear LCD,
  and shutter ring pure `#000000` black, leaving only the top-left optical viewfinder window live
  (tap inside to focus/meter, press Volume keys to shoot, tap anywhere outside or Back to return).

## v0.7.2 — Gallery Multi-Select Batch Delete & Minimalist Onboarding

- **Multi-select gallery** — select multiple frames together, toggle **All / None**, batch
  **Delete (N)** with a 7-second **Undo** window, or batch **Save (N)** to `Pictures/Grain`.
- **Minimalist hardware onboarding** — concise 4-step darkroom intro guide with custom vector
  visuals (replayable via `MENU → SET UP → INTRO GUIDE`).

## v0.7.1 — Xiaomi HyperOS / MIUI In-App Updater Fix

- **`FileProvider` system-installer fallback** — detects silent/system-blocked `PackageInstaller`
  aborts on Xiaomi HyperOS / MIUI and hands the verified APK directly to Android's system package
  installer.

## v0.7.0 — P / S / ISO / M Exposure Solver, Bright Kodak (`BKD`) & Hardware Flash (`⚡`)

- **Closed-loop P / S / ISO / M exposure solver** — fixes manual shutter speed and ISO dials across
  Program, Shutter-Priority, ISO-Priority, and Manual modes with `1/8 s` preview frame-duration
  capping and ISO preview boost.
- **Bright Kodak (`BKD`)** — adds Ritchie Roesch's *Bright Kodak* RAW recipe.
- **Hardware Flash (`⚡`)** — adds `OFF`, `ON`, `AUTO`, and `TORCH` flash modes on the camera back.

## v0.6.0 — Magnifier, AF-C face tracking, focus bracketing & in-app updates

Completes the focusing suite planned in v0.5.0 and adds verified in-app self-updating from GitHub
Releases so new builds no longer need manual APK downloads.

- **Magnifier (`MENU → AF / FACE → MAGNIFIER`)** — **`OFF`**, **`4× HOLD`** (default), **`8× HOLD`**,
  or **`4× ON`**. Press and hold on the preview to open a live `4×` or `8×` Focus Check loupe around
  the active focus point or tracked face; in **MANUAL** focus, half-pressing the shutter also opens
  the loupe and turning the **Command dial** steps `MF DISTANCE` live (`0..30`). When **PEAKING** is
  on, sharp mid-tone edges are highlighted inside the loupe.
- **AF-C face tracking (`FOCUS MODE: AF-C / AF-S / MANUAL` + `AF AREA: FACE`)** — activates Camera2
  face detection across both ISP and RAW previews, tracks the subject with sticky proximity/ID
  matching and exponential smoothing, updates the repeating preview `CONTROL_AF_REGIONS` as the
  subject moves, and renders live corner brackets on the LCD/EVF (turning green on `PASSIVE_FOCUSED`).
- **Focus bracketing (`DRIVE → FOCUS BKT`)** — captures **`3`**, **`5`**, or **`7`** frames
  (`MENU → SHOOT → BKT FRAMES`) across **`NARROW`**, **`MEDIUM`**, or **`WIDE`** focus-distance
  steps (`BKT STEP`), waiting for the lens to settle on each plane and holding AE across the stack.
  Works with both ISP looks and RAW film recipes; each frame records `focusBracket` in its receipt.
- **In-app updates & startup notification** — checks `astanabesta/Grain` GitHub Releases on launch
  (and on demand via `MENU → SET UP → APP UPDATE`), alerts immediately when a newer release is
  available, verifies the APK's SHA-256 digest before installing, and installs via Android's
  `PackageInstaller` session API (`USER_ACTION_NOT_REQUIRED` on Android 12+ after initial install).

## v0.5.3 — Real manual focus + focus peaking

**Focus plan, part three.** The v0.5.0 diagnostics reported `manualSensor: true` and that the HAL
accepts `android.lens.focusDistance`, so the lens can be driven directly instead of only aided.

- **FOCUS MODE** (`MENU → AF / FACE`) switches **AUTO** / **MANUAL**. In MANUAL the autofocus is
  taken out of the loop (`CONTROL_AF_MODE_OFF`) and the lens is positioned straight from
  `LENS_FOCUS_DISTANCE`.
- **MF DISTANCE** moves the lens in 30 steps: **STEP 0 = INFINITY** (lens retracted, far things
  sharp) to **STEP 30 / 30 = the lens' own closest focus** (`minFocusDistance: 10`), interpolated
  linearly in distance.
- **PEAKING** draws the preview's own edges back over the picture in white, brighter where contrast
  is sharper. It reads a 160 × 120 copy of the preview about seven times a second and marks
  mid-tones only, since pure white and black carry no focus information.
- The receipt reports `focusMode` and `focusDistance`, plus the distance the lens reports back.
- Still planned: magnifier, AF-C face tracking, focus bracketing.
- No changes to RAW development or the recipe pipeline.

## v0.5.2 — AF menu matched to what the phone can do

Two of the five planned AF area modes cannot work on this Redmi, so the menu no longer offers them
as controls that would quietly do nothing.

- `maxRegionsAf` is **1**, so a 3 × 3 grid is impossible: **MULTI is removed** from the menu.
- `faceDetectModes` is **[OFF, SIMPLE]** — no FULL mode, so no eye/mouth/id landmarks. **FACE** is
  therefore face-detected AF, not eye AF: it takes the camera's face rectangle nearest the frame
  centre, pads it and aims the region there. Eye AF is not reachable through Camera2 on this phone.
- `manualSensor` is **true** and `LENS_FOCUS_DISTANCE` is accepted, which opened the way to v0.5.3.

## v0.5.1 — AF area and AF lock

The first half of the focusing plan: *where* the lens looks for focus.

- **AF AREA** (`MENU → AF / FACE`) — **SINGLE** (tapped point), **MULTI** (a grid across the
  frame — later removed in v0.5.2), **ZONE** (larger box around the point), **CENTER** (middle of
  the frame) or **FACE** (the camera's face rect, padded, following the subject). The region goes
  on every request, so continuous AF searches inside it and a one-shot focus triggers on it. The
  grid or box is drawn on the preview.
- **AF LOCK** holds both the region and the lens — the focus version of AE-L.
- Mode, lock and focus point are remembered between launches.

## v0.5.0 — Focus probe (diagnostics build)

A diagnostic build for the focusing work, with no capture-pipeline changes.

- The Camera diagnostics panel reports what the phone's Camera2 HAL actually exposes: AF modes,
  `maxRegionsAf` / `maxRegionsAe`, face-detect modes, `maxFaces`, `minFocusDistance`, which of
  `CONTROL_AF_REGIONS` / `CONTROL_AE_REGIONS` / `LENS_FOCUS_DISTANCE` /
  `STATISTICS_FACE_DETECT_MODE` it accepts, `manualSensor`, and a live FULL-face-detection capture.
- **Face landmarks (eye AF)** switch (`SET UP → ALL PREFERENCES → FUJIFILM RECIPES (RAW)`)
  requests FULL instead of SIMPLE detection.
- Every receipt reports `faceEyes`, `faceIds` and `faceMouths`.

## v0.4.0 — The Fujifilm camera back

The screen becomes the back of a Fujifilm camera: milled top plate, viewfinder window with glass
glare, film dial, shutter-speed dial, command dial, five-way lever, memo holder, power switch and a
knurled shutter button, with the preview where the film window would be.

- Landscape rotates and re-lays out the whole back; **EYE** switches to the EVF view.
- New: **AE-L** (lever up), live **histogram** and **level**, **sounds**, a **memo holder** (tap to
  write a note, long-press for the phone's neutral view), **film-sim bracketing** (FS BKT: current
  film plus the next two RAW films, three frames per press), and **manual shutter / ISO** from the
  shutter and ISO dials.
- Q menu, full menu, playback and the RAW pipeline are unchanged from v0.3.5.
- See [The Fujifilm camera back](docs/RAW_FUJI_RECIPES.md#the-fujifilm-camera-back-v040).

## v0.3.5 — Sony s709

Sony's own cinema look ("developed alongside VENICE … subtle colours, smooth colour gradation and a
softer low contrast tone curve") as a new film.

- Import Sony's free `s709Cubes.zip` from Sony's S-Log grading guide (*Tune → Import s709 zip*).
  Grain never bundles it.
- Each photo is developed from RAW into Sony's S-Log3 / S-Gamut3.Cine with Sony's published
  formulas, converted exactly per pixel, then through Sony's table.
- Grain's choices: the meter places the scene at Sony's 18 % grey, and the highlight shoulder is
  stretched where the phone's RAW clips so clipped areas still reach white.
- Fujifilm-only settings (Dynamic range, Color Chrome, FX Blue) are not offered for it.
- See [Sony s709](docs/RAW_FUJI_RECIPES.md#sony-s709-v035).

## v0.3.4 — Face exposure

Fujifilm's Intelligent Face Detection "sets focus and exposure for human faces"; now a detected
face sets the exposure of Fujifilm-recipe photos too.

- Faces come from the camera's face detector or, if it finds none, from the phone's reference JPEG.
  The face closest to the centre is placed half a stop above the scene key, within +3 / −1.5 EV;
  the user's EV is added on top.
- Backlit faces come out bright and the background may blow out, as on a Fujifilm.
- On by default (`SET UP → ALL PREFERENCES → FUJIFILM RECIPES (RAW) → Face exposure`); off, or
  without a face, photos
  develop exactly as in v0.3.3.
- The EXIF Software tag now carries the real version.
- See [Face exposure](docs/RAW_FUJI_RECIPES.md#face-exposure-v034).

## v0.3.3 — FX Blue that deepens the sky

Color Chrome FX Blue now does what Fujifilm's does: blue skies get darker and richer at the same
hue, like a polarizing filter.

- Fitted to Fujifilm X100V camera JPEGs of one clear sky at FX Blue Off, Weak and Strong (Strong
  takes 9–24 % of the luma off the sky, Weak half of that); Grain reproduces them within about
  2 code values.
- Before, FX Blue only touched very saturated blues and changed a real sky by less than 0.3 %.
- Applies to every film and both developments, so Reggie's Superia (Strong) and Reggie's Portra
  (Weak) have deeper skies. With FX Blue Off every photo develops exactly as in v0.3.2.
- See [FX Blue](docs/RAW_FUJI_RECIPES.md#color-chrome-fx-blue-v033).

## v0.3.2 — Reggie's Superia

Reggie Ballesteros' Classic Negative recipe (Fuji X Weekly, March 2026), developed from RAW with
Fujifilm's official Classic Neg. LUT at its published settings.

- DR-Auto, Auto WB R+1 B−3, Highlight −2, Shadow −1, Color +1, Sharpness −2, NR −4, Clarity 0,
  Grain Strong/Large, Color Chrome Strong, FX Blue Strong, and no typical exposure (at Reggie's
  request).
- Moodier, grainier and more contrasty than his Portra, listed right after it, and starts on
  *Development: First version*; *Clean* is one tap away in the Grain extras.
- See [Published recipes](docs/RAW_FUJI_RECIPES.md#published-recipes-v030-reggies-superia-v032).

## v0.3.1 — Development: First version and Clean

- Reggie's Portra develops exactly like Grain v0.2.0 again (bit-identical on the test RAW): colour
  noise reduction only, no sharpening at Sharpness −2, full grain.
- New **Development** setting (Grain extras): *First version* or *Clean*, for every film.
  Reggie's Portra starts on First version, every other film on Clean.
- High ISO NR and Sharpness keep working in both. Everything from v0.2.1–v0.3.0 stays.
- See [Development](docs/RAW_FUJI_RECIPES.md#development-first-version-and-clean-v031).

## v0.3.0 — Six more Fujifilm recipes

Kodak Gold 200, Kodachrome 64, Pacific Blues, Vibrant Arizona, CineStill 800T and Kodak Tri-X 400
(Fuji X Weekly), developed from RAW with their published settings as the default, listed after
Reggie's Portra.

- New settings, usable on every film: fixed **white balance** (Daylight, Shade, Fluorescent 1–3,
  Incandescent, Kelvin 2500–10000 K) using Fujifilm white points measured from Fujifilm RAW files,
  **D-Range Priority** Weak/Strong, and the Acros **+Ye/+R/+G** filters.
- The recipe's typical exposure compensation is shown as a guide, never applied automatically.
- Fixed-white-balance films preview with the nearest phone preset.
- See [RAW Fujifilm recipes](docs/RAW_FUJI_RECIPES.md).

## v0.2.3 — Tuning, Halation and Bloom

- Every ◆ RAW film's settings can be tuned in the film sheet and are saved per film, with *Reset
  this look* back to the default.
- Clarity is now applied in development.
- **Halation** and **Bloom** (Off, 1–10) are optional Grain extras, off by default, made from the
  RAW light before the film simulation and shown in the developed photo only.

## v0.2.2 — Every Fujifilm film simulation

All 10 film simulations in Fujifilm's official LUT pack (Provia, Velvia, Astia, Classic Chrome,
Reala Ace, Pro Neg. Std, Classic Neg., Eterna, Eterna Bleach Bypass, Acros) are selectable once the
pack is imported, developed from RAW at standard settings.

- The pack's two Rec.709 video conversions are no longer counted as film simulations.

## v0.2.1 — Faster, cleaner RAW development

- Release build (much faster on the phone).
- Noise curve measured per frame (the phone's own profile is ~5× low), two-scale noise reduction,
  recipe sharpening, noise-aware grain.
- Sideways photos saved upright.

## v0.2.0 — First Fujifilm recipe from RAW

The ◆ **Reggie's Portra** film captures the sensor RAW (plus the phone's own JPEG), then develops it
on the phone:

Camera2 calibration → scene-linear → F-Log2/F-Gamut → Fujifilm's **official** Classic Chrome LUT
(imported by you from Fujifilm's free pack; never bundled) → a stills tone model fitted on public
Fujifilm RAF/JPEG pairs → recipe controls → JPEG, with the DNG and receipt kept for tuning.

- See [RAW Fujifilm recipes](docs/RAW_FUJI_RECIPES.md).

## v0.1.3 — Shadow tone sampling correction

- Corrects coarse shadow-tone sampling and flags explicitly unfocused captures.
- Recipe colour calibration remains pending.
- See [sampling diagnosis and remaining limitations](docs/TONE_SAMPLING.md).

## v0.1.2 — Upright viewfinder

- Corrects the double sensor-rotation path in the viewfinder.
- Confirmed upright on the Redmi.

## v0.1.1 — Native UI revision

- Native layout and controls follow the final HTML framing edition: custom orange shutter, line
  icons, anchored ratio/zoom controls, film-card sheet, paper gallery and preferences.
- Passed Android compilation, tests, lint and APK packaging in GitHub Actions.
- See [design parity](docs/DESIGN_PARITY.md).
