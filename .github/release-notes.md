## Grain Photo vVERSION — AF area, honest menu (v0.5.2, device-test beta)

Install **Grain-vVERSION.apk** on the Redmi Note 13 Pro+.
- **From v0.2.1 – v0.5.1:** it installs as an update. Your photos, imported LUTs and tuning stay.
- **From v0.2.0 or older:** first save the photos you want to keep (photo → **Save to device gallery**), uninstall Grain, install this APK, then re-import the LUT pack (*Tune → Import LUT pack*).

### Why this build exists

v0.5.1 shipped the AF area with five modes. Your diagnostics came back, and two of them **cannot work
on this phone**. This build makes the menu say what the phone can actually do, instead of offering a
control that quietly does something else.

### What the phone reported

From **Camera diagnostics** → `focus`:

| Number | Value | What it means |
| --- | --- | --- |
| `maxRegionsAf` | **1** | The HAL accepts **one** AF region. A 3×3 grid needs nine. |
| `faceDetectModes` | **[0, 1]** | OFF and SIMPLE only. **FULL is not offered**, so no eye / mouth / id landmarks. |
| `maxFaces` | 15 | Up to fifteen faces tracked at once. |
| `minFocusDistance` | 10 | The lens does report a closest focus distance. |
| `manualSensor` | **true** | `LENS_FOCUS_DISTANCE` is accepted — **real manual focus is possible**. |
| `requestKeys` | afRegions, aeRegions, lens.focusDistance, statistics.faceDetectMode, afTrigger | All the focusing keys you'd want are accepted. |
| `resultKeys` | statistics.faces | `STATISTICS_FACES` does come back — as rectangles only. |

And from the face photo's receipt: `cameraFaces: 1`, `faceEyes: 0`, `faceIds: 0`, `faceMouths: 0`,
`faceLandmarks: "none: this HAL fills only the face rectangle"`. The camera **does** find and box the
face. It does not tell you where the eyes are.

### What changed in the menu

**AF AREA now cycles only the modes this phone can run.** On this Redmi that is **SINGLE**, **ZONE**,
**CENTER** and **FACE** — **MULTI is gone**, because with `maxRegionsAf = 1` a grid is one region, which
is the same as CENTER, and a control that does not do what its name says is not a control.

**FACE** is face-detected AF, not eye AF. It takes the camera's own face rectangle, picks the face
nearest the frame centre (Fujifilm's Intelligent Face Detection rule), pads it and aims the AF region
there. It follows the face. That is real and it is worth using; it is not eye AF.

### What this means for the plan

- **Eye AF is not available through Camera2 on this phone.** FULL face detection is not offered, so
  there are no landmarks to aim at. The only routes left are the camera's own face box (done, above) or
  an on-device landmark model, which is a large dependency and a real project of its own.
- **Multi-point AF is not available.** One region is all this HAL takes. Vendor camera apps get
  multi-point through Xiaomi's private keys, not through the standard API Grain uses.
- **Manual focus got better than planned.** The v0.5.0 plan assumed no `MANUAL_SENSOR` and settled for
  peaking + magnifier as aids. The phone advertises `MANUAL_SENSOR` and accepts `LENS_FOCUS_DISTANCE`,
  so a real manual focus control is possible.

Nothing in the capture pipeline changed. Same RAW development, same recipes, same receipts.

This is a personal device-test build, signed with the project's personal key (certificate
SHA-256 6e073ef6…). SHA256SUMS.txt verifies the APK download.
