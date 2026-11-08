## Grain Photo vVERSION — The Camera Report Can Now Be Saved, Not Just Copied (`Android`)

- **Android (`Grain-vVERSION.apk`):** Install directly inside Grain (**MENU → SET UP → APP UPDATE**) or download **`Grain-vVERSION.apk`** below.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** Download **`Grain-iOS-vVERSION.ipa`** below and install onto any iPhone (iOS 16+, including **iPhone XR / XS** and newer) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)**.

### What's new in v0.8.4

The camera report grew long enough that copying it could come out cut off, so it can now be **saved as a file** as well as copied.

#### 1. Save the Report as a Text File
- **New:** the diagnostics dialog now has a **Save as .txt** button. It writes the whole report to **`Download/Grain`** — a real file you can attach to a message, open in any text editor, or keep.
- Nothing can cut a file off, so this is the reliable way to hand a full report over.

#### 2. Copy Just the Part That Matters
- **New:** a **Copy summary** button that copies only the few lines that decide how large a photo the phone can take: the biggest public output size for JPEG, RAW and YUV, the Android 13+ maximum-resolution map, the vendor switches for super-resolution and remosaic, and the values the vendor attaches to them.
- It is short enough to survive any clipboard, so it always arrives intact.
- The report now also **opens with that summary**, and the high-resolution block sits **first** in the JSON — so even a truncated copy still contains the part that matters.

#### 3. Everything Else From v0.8.3 Still Here
- The manual-focus scale under the screen, engraved with the real distances the lens reports.
- The high-resolution probe in `MENU → SET UP → DIAGNOSTICS`.
