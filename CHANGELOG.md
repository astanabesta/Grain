# Changelog

Every release of Grain Photo, newest first. Grain is a personal **device-test beta** for the
Redmi Note 13 Pro+; versions below are the builds installed on that phone.

Format: `vMAJOR.MINOR.PATCH` — the patch number also counts small feature releases, because each
one was a separate APK install on the device.

---

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
