## Grain Photo vVERSION — Fix 112 MP Out-of-Memory & Stream High-Resolution Saves (`Android`)

- **Android (`Grain-vVERSION.apk`):** Install directly inside Grain (**MENU → SET UP → APP UPDATE**) or download **`Grain-vVERSION.apk`** below and tap it to install directly over your current install (no uninstall needed).
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** Download **`Grain-iOS-vVERSION.ipa`** below and install onto any iPhone (iOS 16+, including **iPhone XR / XS** and newer) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)**.

### What's fixed in v0.8.8

#### 1. Fixed `"Hi-Res merge failed: not enough memory"` on 9-shot `HI-RES 3 112MP`
- **Root cause:** developing a merged `12220 × 9164` (`112 MP`) RAW frame on a 512 MB Android heap held the 224 MB merged `short[]`, full-frame quarter-res chroma/glow/denoise `float[]` planes (~250+ MB), and 8 full-width strip workers (~320 MB) simultaneously, then buffered the entire encoded JPEG in a Java `byte[]` alongside the 448 MB `ARGB_8888` `Bitmap`.
- **Fixed — Band-tiled RAW development (`RawDeveloper`):** frames above 32 MP now develop in overlapping horizontal bands with exact cascaded halo/bloom/denoise/clarity support, global auto-exposure & chroma-noise calibration, in-place `quarterDenoise` scratch reuse, and compact `16`-row ultra-wide strip workers allocated only after temporary quarter planes are freed. Verified seam-free within ≤ 1 code value against single-pass development and stress-tested at `12220 × 9164` on 8 threads inside a 384 MB JVM heap.

#### 2. Zero-copy file-streamed JPEG encode & `BitmapRegionDecoder` cropping
- `RawDevelopment` now releases the single-use merged `RawFrame` before JPEG compression, streams `Bitmap.compress(JPEG, 95, …)` straight to a temp file (0 MB Java `byte[]`), and recycles the 448 MB `Bitmap` before writing EXIF and moving the file into `PhotoStore`.
- Non-`full` aspect-ratio / digital-zoom crops over 20 MP now decode only the cropped region via `BitmapRegionDecoder` instead of allocating two full-sensor `ARGB_8888` bitmaps (~896 MB).

#### 3. One-tap `SAVE` to phone Gallery after Hi-Res capture
- Grain stores photos privately first (**PLAY** button or **MENU → PLAYBACK → PHOTO GALLERY**). Completed Hi-Res captures now also include a one-tap **SAVE** action on the completion banner to export a copy directly to `Pictures/Grain` in your phone's system Gallery.
