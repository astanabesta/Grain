## Grain Photo vVERSION — Clean EXIF: No More '?' in Photo Metadata

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, and photos). Still the lean v0.9.9 build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.10

#### Photo metadata — traced and polished
- **Trace verdict:** Make / Model / ISO / ExposureTime have been written into every developed JPEG since **v0.9.2** (verified end-to-end on a real saved file — including the fallbacks that stamp them even when the phone's own JPEG is absent). Any shots missing those tags simply predate v0.9.2.
- **Fixed — '?' mojibake:** recipe names carry typography like **·** and **—**, which EXIF's ASCII-only fields (`ImageDescription`, `UserComment`, …) cannot store — ExifInterface was mangling them into `?` (e.g. `iPhone XS Max ? Warm`). Those characters are now mapped to clean ASCII equivalents before writing, verified by a round-trip test through a real JPEG file.
