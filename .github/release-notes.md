## Grain Photo vVERSION — Fix the Startup Crash & Ship the Guided 112 MP Mode (`Android`)

- **Android (`Grain-vVERSION.apk`):** Install directly inside Grain (**MENU → SET UP → APP UPDATE**) or — if updating from a crashing `v0.8.5` / `v0.8.6` build — download **`Grain-vVERSION.apk`** below and tap it to install directly over your current install (no uninstall needed).
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** Download **`Grain-iOS-vVERSION.ipa`** below and install onto any iPhone (iOS 16+, including **iPhone XR / XS** and newer) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)**.

### What's fixed in v0.8.7

#### 1. Fixed the startup `StackOverflowError` in `v0.8.5` / `v0.8.6`
- **Root cause:** when generalising the tripod Hi-Res drive checks for the new 9-frame mode, `MainActivity.isHiRes(int drive)` accidentally called `isHiRes(drive)` inside its own body (`java.lang.StackOverflowError` during `loadPrefs()` at launch).
- **Fixed:** extracted all drive-dial constants and Hi-Res parameters into pure-Java `DriveModes`, fixed `isHiRes(int)`, and added unit + Robolectric startup regression tests that boot `MainActivity` and cycle all 8 stops of the **DRIVE** dial on every push to `main`.

#### 2. Fixed the secondary `onResume` crash when startup safe-mode triggers
- In `v0.8.6`, `onCreate` caught the `StackOverflowError` and showed *"Grain could not start"*, then `onResume()` ran against uninitialised fields (`store.list()` `NullPointerException`, captured by the new `Download/Grain/Grain-crash-*.txt` writer).
- **Fixed:** when `startupFailed` is set, lifecycle callbacks enter minimal safe mode — the dialog stays on screen with a **Check update** button, no uninitialised camera/store fields are touched, and stale crash logs from an older version no longer re-prompt after you update.

#### 3. Guided 9-shot 112 MP super-resolution polish
- Fixed the alpha channel on the green sub-pixel drift indicator (`Δ x.xx px`) in the on-screen 3×3 phase guide so sub-0.7 px alignment draws in crisp green instead of transparent.
- **HI-RES 3 112MP** is ready to test on the **DRIVE** dial (6 s tripod settle → 9 locked-exposure RAW frames → live 3×3 sub-pixel phase grid + drift readout → ~112 MP developed photo + full phase diagnostics in the receipt).
