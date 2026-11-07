## Grain Photo vVERSION — A Real Manual-Focus Scale, and a Probe for the 108 MP Path (`Android`)

- **Android (`Grain-vVERSION.apk`):** Install directly inside Grain (**MENU → SET UP → APP UPDATE**) or download **`Grain-vVERSION.apk`** below.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** Download **`Grain-iOS-vVERSION.ipa`** below and install onto any iPhone (iOS 16+, including **iPhone XR / XS** and newer) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)**.

### What's new in v0.8.3

Both changes below are **Android only for now** — the iOS build follows later.

#### 1. A Manual-Focus Scale Under the Screen
- **New:** Pick **MANUAL** in **MENU → FOCUS MODE** and a focus scale appears directly under the screen, in the space between the display and the bottom deck. It is there only while manual focus is selected, and it disappears the moment you go back to **AF-C** or **AF-S**.
- **The stops are real distances, not step numbers.** A lens focuses by *dioptres*, and the distance is the reciprocal — one dioptre is one metre, ten dioptres is ten centimetres — so the scale is stretched at the far end and crowded at the near end, exactly like the distance scale engraved on a lens barrel.
- On the Redmi Note 13 Pro+ the lens reports a closest focus of **10 cm**, so the scale runs from **∞** down to **10 cm** and is engraved **∞ · 1 m · 0.50 m · 0.20 m · 0.10 m**. The exact distance follows your thumb while you drag.
- **It works while the focus loupe is up**, which is where manual focus is actually used: magnify, drag the scale, watch the focus plane settle.
- **Also:** the **MF DISTANCE** menu item, the magnifier label and the focus toast now read the distance (`0.30 m`) instead of `STEP 7 / 30`.

#### 2. The HAL Probe — Is There a 108 MP Path?
- **New:** **MENU → SET UP → DIAGNOSTICS** now also dumps everything the camera's own hardware says about its high-resolution paths: the vendor **super-resolution** and **remosaic** characteristics, the vendor **request keys** that switch them on, and every output size the scaler advertises — including the Android 13+ maximum-resolution map.
- **Why:** on this phone the public Camera2 sizes stop at **4080 × 3060 (12.5 MP)** for *both* JPEG and RAW. Any bigger mode therefore has to come from the phone's own engine, and the probe establishes whether one exists before any of it is built.
- **How to send it:** open **MENU → SET UP → DIAGNOSTICS**, tap **Copy**, and paste the text back. The **hal** block at the end of the report is the one that matters.

#### 3. Everything Else From v0.8.2 Still Here
- The preview read-back can no longer crash Grain, and Grain still shows its own crash report on the next launch.
- The two tripod high-resolution modes (`HI-RES 1 36MP` and `HI-RES 2 48MP`) are unchanged.
