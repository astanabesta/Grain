## Grain Photo vVERSION — focus probe (v0.5.0, device-test beta)

Install **Grain-vVERSION.apk** on the Redmi Note 13 Pro+.
- **From v0.2.1 – v0.4.0:** it installs as an update. Your photos, imported LUTs and tuning stay.
- **From v0.2.0 or older:** first save the photos you want to keep (photo → **Save to device gallery**), uninstall Grain, install this APK, then re-import the LUT pack (*Tune → Import LUT pack*).

### What this build is

A diagnostic build for the v0.5.0 focusing plan (multi-point AF, eye AF, manual focus). It is v0.4.0's
camera back plus a probe that reports what **your** phone's Camera2 HAL actually exposes. Nothing in
the capture pipeline changed: the same RAW development, the same recipes, the same receipts.

### The probe

**1. Camera diagnostics.** The diagnostics panel (Q → diagnostics, or the panel shown after a photo)
now carries a `focus` block:

- `afModes` — the AF modes the HAL offers (OFF / MACRO / CONTINUOUS_VIDEO / CONTINUOUS_PICTURE / AUTO)
- `maxRegionsAf`, `maxRegionsAe` — how many metering regions the HAL accepts (this is the number that
  decides the AF grid size)
- `faceDetectModes` — OFF / SIMPLE / FULL
- `maxFaces` — the most faces the HAL tracks at once
- `minFocusDistance` — the closest the lens goes (manual focus range)
- `requestKeys` — which of `CONTROL_AF_REGIONS`, `CONTROL_AE_REGIONS`, `LENS_FOCUS_DISTANCE`,
  `STATISTICS_FACE_DETECT_MODE`, `CONTROL_AF_TRIGGER` the HAL accepts
- `resultKeys` — whether `STATISTICS_FACES` comes back at all
- `manualSensor` — whether `MANUAL_SENSOR` is advertised (this is what a real manual focus needs)
- `live` — one capture taken with FULL face detection at open: whether the HAL accepts FULL, and
  whether `STATISTICS_FACES` returns (with no face in frame it reports the empty result)

**2. Face landmarks (eye AF) switch.** Settings → Fujifilm recipes → **Face landmarks (eye AF)**. It
asks the camera for FULL face detection instead of SIMPLE. SIMPLE reports only a rectangle and a
confidence; FULL also reports each face's id and its eye and mouth positions. It is off by default
because SIMPLE is faster and face exposure does not need the landmarks.

**3. The receipt.** Every photo's receipt now reports `faceDetectMode`, `cameraFaces`, and — when the
camera's own detector supplied the faces — `faceEyes`, `faceIds`, `faceMouths`. If faces were found
without landmarks it says so explicitly: `faceLandmarks: "none: this HAL fills only the face
rectangle"`.

### What to send back

Two things, and that is enough:

1. **The `focus` block** from the diagnostics panel (a photo of the screen, or paste the JSON).
2. **One photo of a face** taken with **Face landmarks (eye AF)** on, with its receipt.

Those numbers decide how v0.5.0's eye AF is built:

- `faceEyes` > 0 → the phone reports eye positions, so eye AF is wiring: pick the face nearest the
  centre, take its eye box, aim the AF region there.
- `faceEyes` = 0 with faces present → this HAL fills no landmarks, so eye AF falls back to the face
  box (still better than whole-frame AF) and the UI will say which one ran.
- `maxRegionsAf` = 0 → multi-point AF is not real on this phone, and the AF area control will offer
  only single point.

This is a personal device-test build, signed with the project's personal key (certificate
SHA-256 6e073ef6…). SHA256SUMS.txt verifies the APK download.
