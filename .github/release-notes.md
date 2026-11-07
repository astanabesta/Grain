## Grain Photo vVERSION — Recipe-Named Photo & RAW Filenames in File Info (`Android` + `iOS`)

- **Android (`Grain-vVERSION.apk`):** Install directly inside Grain (**MENU → SET UP → APP UPDATE**) or download **`Grain-vVERSION.apk`** below.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** Download **`Grain-iOS-vVERSION.ipa`** below and install onto any iPhone (iOS 16+, including **iPhone XR / XS** and newer) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)**.

### What's new in v0.7.10

#### 1. Photo & RAW Filenames Now Include the Film Recipe (`Grain_<Recipe_Name>_<Date>_<Time>`)
- Instead of bare numbers (`IMG_0001.JPG` or `Grain_1762583422.jpg`), every photo and `.dng` RAW file now carries the **exact recipe it was shot with** right in its filename:
  - e.g. **`Grain_Kodak_Portra_800_v3_20261108_143512.jpg`**
  - **`Grain_Leica_X_20261108_143529.jpg`**
  - **`Grain_CineStill_800T_20261108_143544.jpg`**
  - **`Grain_Reggies_Portra_20261108_143601.jpg`**
- **iOS Photos `ⓘ` File Info, Share & Files:** Sets `PHAssetResourceCreationOptions.originalFilename` when saving to Apple Photos (so swiping up / tapping **`ⓘ` Info** in Photos shows `Grain_<Recipe>_...jpg` instead of `IMG_XXXX.JPG`), embeds the recipe in EXIF `ImageDescription` & `UserComment`, and shows the full filename in Darkroom Roll & Receipt.
- **Android Gallery File Info & MediaStore:** Exports `Pictures/Grain/Grain_<Recipe>_...jpg` (and `_sensor.dng` / `_receipt.json`), preserves EXIF `ImageDescription` + `UserComment` across cropped & uncropped frames, and works retroactively when exporting existing photos already on your roll.
