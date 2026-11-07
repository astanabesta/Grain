## Grain Photo vVERSION — The Preview Read-Back Can No Longer Crash Grain (`Android`)

- **Android (`Grain-vVERSION.apk`):** Install directly inside Grain (**MENU → SET UP → APP UPDATE**) or download **`Grain-vVERSION.apk`** below.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** Download **`Grain-iOS-vVERSION.ipa`** below and install onto any iPhone (iOS 16+, including **iPhone XR / XS** and newer) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)**.

### What's new in v0.8.2

#### 1. The Real Cause of the Focus Peaking Crash
- **Found:** Turning on `PEAKING` and then leaving the menu could take Grain down. The culprit was **not** the peaking code — it was the **live histogram**, which reads the same preview pixels and had no guard at all.
- **Why it crashed there:** Reading a `TextureView` back needs its hardware layer, and Android *throws* instead of returning nothing when it cannot get one. The window where that happens is right after the view tree changes — closing the menu re-lays out the camera back and rebuilds the preview surface. The histogram resumed its read-back in exactly that window and took the app with it.
- **Fixed:** All three preview readers — the **histogram**, the **focus loupe** and the **peaking** — now go through one shared, guarded read-back. A frame that cannot be read is simply a frame with no histogram, no loupe image and no peaking overlay. Nothing there can crash Grain any more.
- **Also fixed:** The read-back is held off for a moment after the view tree changes, and a bitmap that comes back the wrong size is rejected instead of being read past its end.

#### 2. Grain Now Remembers Its Own Crashes
- **New:** If Grain crashes, the next launch shows the crash report on screen with a **Copy** button — so a crash is something you can send, not something you have to guess at.
- The report says the exception, the stack trace, the Android version and the exact device.
- The last report also stays available in **MENU → SET UP → DIAGNOSTICS**.

#### 3. Everything Else From v0.8.1 Still Here
- The two tripod high-resolution modes (`HI-RES 1 36MP` and `HI-RES 2 48MP`) are unchanged.
- The receipt and the Darkroom gallery still record which merge produced each frame.
