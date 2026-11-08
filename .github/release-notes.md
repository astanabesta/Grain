## Grain Photo vVERSION — Fix 112 MP Out-of-Memory & Rate-Limit-Free In-App Updates (`Android`)

- **Android (`Grain-vVERSION.apk`):** Download **`Grain-vVERSION.apk`** below and tap it once to install directly over `v0.8.7` / `v0.8.8` (no uninstall needed; keeps all LUTs, recipes, and photos). From `v0.8.9` onward, **MENU → SET UP → APP UPDATE** no longer hits GitHub's `HTTP 403` shared-IP API limit.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** Download **`Grain-iOS-vVERSION.ipa`** below and install onto any iPhone (iOS 16+, including **iPhone XR / XS** and newer) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)**.

### What's fixed in v0.8.8 – v0.8.9

#### 1. Fixed `Update check failed: Update server returned HTTP 403` (`v0.8.9`)
- **Root cause:** unauthenticated calls to `api.github.com` share a 60 requests/hour quota per carrier/ISP CGNAT public IP, returning `HTTP 403` on mobile data and shared broadband.
- **Fixed:** `AppUpdater` now resolves updates directly via `github.com/.../releases/latest` HTTP 302 redirect + `SHA256SUMS.txt` + `releases.atom` without consuming `api.github.com` quota, and offers a one-tap **Open Releases** fallback dialog if a network ever blocks automated checks.

#### 2. Fixed `"Hi-Res merge failed: not enough memory"` on 9-shot `HI-RES 3 112MP` (`v0.8.8`)
- **Band-tiled RAW development (`RawDeveloper`):** frames above 32 MP develop in overlapping horizontal bands with exact cascaded halo/bloom/denoise/clarity support, global auto-exposure & chroma-noise calibration, and compact `16`-row ultra-wide strip workers. Stress-tested at `12220 × 9164` (`112 MP`) across 8 threads inside a 384 MB JVM heap.
- **Zero-copy file-streamed JPEG save:** releases the 224 MB merged `RawFrame` before JPEG encode, streams `Bitmap.compress(JPEG, 95, …)` straight to disk (`0 MB` Java `byte[]`), recycles the 448 MB `Bitmap` before EXIF write, and uses `BitmapRegionDecoder` for non-`full` crops.
- **One-tap `SAVE` to phone Gallery:** completed Hi-Res captures now include a **`SAVE`** action on the completion banner to export a copy directly to `Pictures/Grain` in your phone's system Gallery.
