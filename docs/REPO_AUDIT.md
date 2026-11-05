# What was reused from Zircon Cinema

Inspected source: https://github.com/astanabesta/camera, public main snapshot previously reported as short commit `4e62554`.

Read the README, handover, changelog, architecture/research notes, Java CameraEngine/MainActivity, Dart bridge/controller, and the included tonemap dump. The source and guide do not all describe the same historical state, so implementation takes precedence over marketing claims.

Relevant files:
- `android/app/src/main/java/ai/arena/zirconcinema/ui/CameraEngine.java`
- `android/app/src/main/java/ai/arena/zirconcinema/ui/MainActivity.java`
- `lib/src/camera/native_camera_engine.dart`
- `lib/src/model/camera_ui_controller.dart`

## Useful existing approach

`applyControls()` requests WB presets, exposure, AF, EDGE_MODE, NOISE_REDUCTION_MODE and TONEMAP_MODE_CONTRAST_CURVE with separate RGB curves. CaptureResult telemetry exposes returned modes. This is directly relevant to a capture-aware photo recipe and is the design carried into Grain's independent still engine.

## Not carried across

- P010/ImageWriter/HEVC recording, MediaRecorder, audio, muxing, video timestamps.
- Video AE FPS locks, shutter-angle-derived exposure limits, continuous-video AF.
- The earlier app's fixed video preview/record output assumptions.
- Flutter UI and its dependencies.
- `.cube` files or third-party camera code.

## Important corrections / limits

- P010 is processed 10-bit YUV, not Bayer sensor RAW, even when a filename says RAW.
- HLG is not HDR10+ dynamic metadata.
- A submitted contrast curve is a request; device firmware may override or omit its result. The included dump reports tone-map mode 2 and zero point data, which is not proof that an intended custom curve was applied. It does not establish all behavior of the device either.
- Kelvin-labeled WB options in the original Java map to AWB presets such as DAYLIGHT and CLOUDY_DAYLIGHT. They are not precise calibrated Kelvin/tint gains.
- Three independent RGB tone curves cannot reproduce arbitrary cross-channel 3D color transforms, genuine monochrome conversion, spatial grain or halation.
- Still-photo output support and preview/still matching must be tested separately from a working video mode.
- Original app's minSdk 36 was not carried into this photo beta; Grain uses minSdk 29 and compile/target 36. This is a compatibility choice, not proof every Android 10 device supports the recipes.

Grain is a fresh, separately installable app. The original repository and original APK are not edited or replaced.
