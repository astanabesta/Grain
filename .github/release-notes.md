## Grain Photo vVERSION — manual focus + peaking (v0.5.3, device-test beta)

Install **Grain-vVERSION.apk** on the Redmi Note 13 Pro+.
- **From v0.2.1 – v0.5.2:** it installs as an update. Your photos, imported LUTs and tuning stay.
- **From v0.2.0 or older:** first save the photos you want to keep (photo → **Save to device gallery**), uninstall Grain, install this APK, then re-import the LUT pack (*Tune → Import LUT pack*).

### Why this build exists

Your diagnostics said `manualSensor: true` and that the HAL accepts `android.lens.focusDistance`. The
v0.5.0 plan assumed the opposite and settled for peaking + magnifier as *aids*. On your phone the lens
can be driven directly, so this is a real manual focus — and peaking is the aid that makes it usable.

### Manual focus

**MENU → AF / FACE → FOCUS MODE** switches between **AUTO** and **MANUAL**. In MANUAL the autofocus is
taken out of the loop (`CONTROL_AF_MODE_OFF`) and the lens is positioned straight from a distance
(`LENS_FOCUS_DISTANCE`). **MF DISTANCE** moves it in thirty steps:

- **STEP 0** is **INFINITY** — the lens is fully retracted, far things are sharp.
- **STEP 30 / 30** is the lens' own **closest focus**, which your camera reports as `minFocusDistance: 10`.
- Between them the distance is interpolated linearly, so the steps are even in *distance*, not in
  how near things look. Dial toward infinity for a landscape, toward 30 for something close.

The fraction is mapped onto whatever the HAL reports, so the control works whatever unit the phone uses.
Only the two ends are guaranteed on every camera; the receipt reports the distance the lens actually
reports back, so you can see whether it took the value.

### Peaking

**MENU → AF / FACE → PEAKING** draws the preview's own edges back over the picture in white, brighter
where the contrast is sharper. Turn it on, switch to MANUAL, and dial **MF DISTANCE** until the edges
you care about glow — that is the lens actually on the subject. It reads a small (160 × 120) copy of the
preview about seven times a second, so it costs the preview very little.

Peaking only marks **mid-tones**. Pure white and pure black carry no focus information; marking them
would just make the sky and the shadows glow.

### The receipt

`requested.applied` now carries `focusMode` and `focusDistance` when manual focus is on, and `actual`
carries `focusDistance` — the value the lens reported back. A camera that cannot do it says so in
`warnings` rather than writing a guessed distance.

### Still missing

- **Magnifier** — a held-to-magnify view around the focus point.
- **AF-C face tracking** — continuous focus following the face between shots.
- **Focus bracketing** — a burst across focus distances (planned for v0.5.2 in the focus plan).

Nothing in the RAW development or recipe pipeline changed. Same engines, same goldens.

This is a personal device-test build, signed with the project's personal key (certificate
SHA-256 6e073ef6…). SHA256SUMS.txt verifies the APK download.
