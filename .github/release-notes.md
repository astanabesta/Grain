## Grain Photo vVERSION — Crash Fix: Thumbnail Counter NPE

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, and photos). Still the lean v0.9.9-line build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.11

#### Fixed: rare startup crash (from your crash report)
- The background frame-counter (`updateThumb`) could read the photo store before its write was visible to the worker thread — a one-in-a-while `NullPointerException` right after launch. The store reference is now `volatile` and captured locally before dispatch, so the race is gone by construction.

#### Also settled: the "low detail" mystery
- Forensics on your uploads showed the soft shots all came from the portrait-experiment builds (removed in v0.9.9): one v0.9.5 file had literally zero fine detail (the fixed whole-frame-blur bug), and the 1877×1408 / 2040×1530 outputs were 2–2.2× zoom crops. Normal-shot files from every other build are full native **4080×3060** with higher measured acutance than even your Mi-gallery edit. v0.9.10 also fixed EXIF `?` mojibake; v0.9.9 retuned XS Max Warm to your reference.
