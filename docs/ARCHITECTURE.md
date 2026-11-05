# Grain Photo: first device beta

## Product / platform

Separate Android application `dev.grain.photo`, targeting the user's Redmi Note 13 Pro Plus. Native Java + Camera2; no Flutter, React Native, WebView camera, backend, microphone, or runtime network access. The programmatic UI follows the cream/olive/orange Grain prototype. The original self-contained HTML is included under `design/` as a reference, not as the production camera.

The decision to use native Java keeps the new repo small, reuses the relevant architecture of the user's Java Camera2 engine, avoids importing an entire video recorder, and reduces CI setup. The existing Zircon app is not modified or required at runtime.

## Capture path

1. Probe the selected camera's public characteristics, request keys, JPEG sizes, WB/AF/tonemap/edge/noise modes.
2. Select the largest public JPEG at or below the chosen safety ceiling (16 MP balanced, 24 MP larger). No forced 4K video size, no assumption of access to 200 MP.
3. Configure one preview + JPEG ImageReader session.
4. On recipe selection, submit supported WB preset, recipe EV bias, edge/noise preferences and per-channel tone curves in a Camera2 preview request.
5. On shutter, freeze a Snapshot, submit TEMPLATE_STILL_CAPTURE with those settings, JPEG quality 97, and orientation. No video FPS lock or shutter-angle limit.
6. Pair the returned JPEG with its TotalCaptureResult using SENSOR_TIMESTAMP, not callback arrival order.
7. Write source bytes to a staging file before image manipulation. For the selected frame, normalize EXIF orientation and center-crop to the ratio/zoom. Do not apply a second color filter. Do not upscale.
8. Optionally retain the full-frame, unzoomed recipe JPEG. This is already ISP-processed and already has the recipe baked in.
9. Write a JSON receipt and commit a directory by rename. Only committed directories appear in the gallery.
10. User chooses whether to export/share via MediaStore; photos otherwise stay private.

Only one capture/save is allowed at a time. This intentionally avoids unbounded JPEG queues on an untested phone. Burst and zero-shutter-lag are not implemented.

## Critical meanings

- **Full resolution** means the selected advertised still JPEG size, NOT sensor RAW, not stock-camera 200 MP, and not video frames. Digital zoom/aspect cropping reduces final pixel dimensions.
- **RAW** and **HEIF** are not implemented in v0.1. Advertising HEIF support is recorded in diagnostics but does not enable capture.
- **Full-frame capture** is a recipe-baked JPEG, not a clean/unfiltered copy. An unfiltered copy cannot be reconstructed after the ISP applies a recipe. A second exposure or separately supported pipeline would be needed.
- **Zoom** is digital software framing: preview transform + matching center crop of a full, 1x source. This preserves an unzoomed source when requested. No 0.5x/telephoto capability is invented.
- **Hold neutral** requests the phone's default tone processing and auto WB for preview only. It is not RAW, not a literal original preview, and is not what a recipe capture saves.
- **Strength** changes the RGB curves only. It does not interpolate the WB preset or pretend NR is continuously variable.
- The recipe ISO inspiration values do not force sensor ISO. Auto exposure adapts to light.

## Film recipes

Four original, experimental responses: Daylight, Coast, Golden, Afterhours. They define WB presets, exposure bias, edge/noise preferences and monotonic 1D RGB tone curves. The baseline transfer is sampled sRGB; recipes perturb that transfer and are subject to calibration on the device. They are not measured reproductions of named commercial film stocks.

Silver is intentionally not shipped: independent per-channel tone curves cannot correctly turn RGB into monochrome. A supported monochrome camera effect or a dedicated color-rendering path is needed. Grain, halation and 3D LUTs are also not faked as hardware tonemap features.

## Verification

Every shot stores recipe/version, requested supported values, warnings, returned modes, returned curve where present, sensor timestamp, source/output size, crop/zoom and device information. A returned matching mode is not proof of matching pixels. The test plan requires actual still-image comparisons on the Redmi and its current firmware.

Compatibility mode explicitly omits problematic custom tone/edge/noise requests if a repeating request fails. The UI warns, and receipts state this; no JPEG filter is silently substituted.

## Storage / safety

- Atomic directory commit; no gallery entry before completion.
- Decode memory bounded by advertised capture-size ceilings; images in the gallery use sampled decoding.
- If a geometry crop fails after a valid source is written, recovery attempts to commit the full-frame recipe JPEG instead, explicitly marked FULL/recovered in the receipt and UI. If even that cannot be committed (e.g. storage full), the error is reported and staging is retained; no staging-recovery browser is implemented yet. Do not use this beta for irreplaceable shots before testing.
- Undo is a short-lived rename-to-trash operation. Trash is removed after timeout / next deletion / restart.
- Private photos are removed on app uninstall. Export important shots first.
- EXIF GPS is not copied to cropped output, and the app never requests location or adds GPS to the capture request. Exported full-frame captures retain whatever EXIF the HAL produced.

## Source modules

- `GrainStyle`: drawn line icons, double-ring shutter, ratio shapes and paper thumbnail.
- `MainActivity`: native UI, gesture controls, preferences, timer, gallery, export, undo.
- `GrainCamera`: session/lifecycle, public capability negotiation, capture-time ISP requests, timestamp pairing and receipts.
- `Recipe` / `CurveMath`: versioned experimental settings and monotonic RGB responses.
- `Framing`: pure crop/preview/focus geometry.
- `PhotoStore`: file staging/commit, EXIF, crop only, private gallery, MediaStore export, trash.

No SDK, Gradle distribution or Flutter runtime is committed. GitHub Actions is the authority for Android resource linking, Java/Android compilation, lint, and APK packaging. Local syntax/math checks are not equivalent to a successful Android build.
