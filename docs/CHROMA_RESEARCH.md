# Chroma / Fujifilm recipe investigation

Research date: 2026-11-05. Target: https://github.com/TareqAmeer/Chroma-App . Source inspected from main; repository landing identified revision c04e90c9d8b44d614db6c90567870b2449336fcf. No Chroma code or LUT bytes were imported into Grain.

## Short answer

**Yes: Chroma has many recognizable Fuji-inspired film-simulation bases. No: its catalog is not a library of the popular, named Fujifilm camera-setting recipes.** These are different things, despite some overlapping names.

The inspected `LUT_META` has **135 catalog entries**, although the README advertises 133. Catalog entries do not prove that every asset loads correctly. The “User Looks” category has 11 entries: a beach preset and these 10 Fuji-style bases:

| Displayed concept | Catalog key |
|---|---|
| Astia | `astia` |
| Classic Chrome | `classic_chrome_composed` |
| Classic Negative | `classic_neg` |
| Eterna / cinema | `eterna_cinema_composed` |
| Eterna Bleach Bypass | `eterna_bleach_bypass_composed` |
| Nostalgic Negative | `nostalgic_neg_composed` |
| Pro Neg. Standard | `pro_neg_std_composed` |
| Provia | `provia_composed` |
| Reala Ace | `reala_ace_composed` |
| Velvia | `velvia` |

This confirms the names and assets in the source, **not their colorimetric accuracy or equivalence to a Fujifilm camera**.

Other categories contain film-stock/print combinations and standalone film looks: Fujicolor C200, Pro 400H, Superia, Fujichrome Provia/Velvia, Neopan Acros, Kodak Gold 200, Portra 160/400/800 and Kodachrome 64 among others. A Kodak Portra 400 stock LUT is not automatically Reggie's Portra.

## Named recipes: what is and is not there

A case-insensitive search of the authoritative catalog keys found no Reggie's Portra, Pacific Blues, Vibrant Arizona or Cuban Negative entries. This is a finding about the inspected built-in catalog, not a claim about every possible user-imported look.

Examples from the original recipe publisher explain the distinction:

- **Reggie's Portra** starts with Classic Chrome, then combines Auto WB with +2 Red / -4 Blue, highlight/shadow changes, Color Chrome controls, grain, sharpening and NR. Source: https://fujixweekly.com/2022/06/11/fujifilm-x-trans-iv-film-simulation-recipe-reggies-portra/
- **Pacific Blues (X-Trans V)** starts with Classic Negative, uses 5800K with +1 Red / -3 Blue, DR400, highlight -2 / shadow +3, Color +4, grain and Color Chrome controls. Its Blue setting differs from the X-Trans IV recipe. Source: https://fujixweekly.com/2022/12/06/pacific-blues-fujifilm-x-t5-x-trans-v-film-simulation-recipe/
- Fuji X Weekly's first-quarter 2026 ranking listed Reggie's Portra first and included Pacific Blues. This is evidence of popularity, **not presented as the latest ranking**: https://fujixweekly.com/2026/03/31/the-26-most-popular-fujifilm-recipes-of-2026-so-far-first-quarter/

Finding the base Classic Chrome or Classic Negative LUT does not establish the presence of those complete recipes.

## How Chroma makes its looks

The built-in preset loader uses **33 × 33 × 33 RGB8 LUTs**, 107,811 bytes per uncompressed color cube, with inline/cache/vendor loading. Its WebGL image-processing path can combine a look with print emulation and additional effects. This is an image-development/editor architecture, not Android Camera2 request parameters.

Primary source files:

- Catalog and inline core looks: https://github.com/TareqAmeer/Chroma-App/blob/main/src/app/19-script.js.html (`LUT_META`, `LUT_PRESETS`).
- Loader: https://github.com/TareqAmeer/Chroma-App/blob/main/src/app/20-script/color-raw/built-in-preset-bytes-inline-core-looks.js.html .
- LUT composition: https://github.com/TareqAmeer/Chroma-App/blob/main/src/app/20-script/color-raw/cont-composeluts.js.html .
- Licensing/provenance: https://github.com/TareqAmeer/Chroma-App/blob/main/LICENSES-MODELS.md .

The app is GPL-3.0; Spektrafilm assets have CC BY-SA 4.0 attribution/share-alike terms, and calibration tooling references Dehancer-derived assets. Review each asset's provenance and redistribution terms rather than assuming a public repository makes every LUT unrestricted. No assets have been copied into Grain.

## What this means for Grain's recipe problem

Current Grain has **four original experimental ISP presets**: Daylight, Coast, Golden and Afterhours. They are not Fujifilm film simulations, nor are they calibrated reproductions of named Fuji recipes.

The current implementation requests supported WB presets, independent RGB tone curves, exposure compensation and NR/sharpening choices before JPEG capture. Important limitations:

1. Independent 1D RGB curves cannot reproduce arbitrary 3D hue/saturation behavior from Classic Chrome, Classic Negative or Color Chrome.
2. Camera2 has no standard Fujifilm film-simulation, DR400 or Color Chrome controls. Fujifilm numerical WB offsets also have no universal direct mapping to phone ISP settings.
3. If the phone does not expose custom tone curves, Grain deliberately reports that recipe color response is unavailable. It does not substitute a JPEG color filter.
4. Tone strength changes the curves, not WB, recipe exposure bias or all ISP preferences. Zero strength is not guaranteed to equal the phone's default rendering.
5. A HAL accepting a request or echoing metadata is not proof of the resulting pixel appearance.

Therefore importing Chroma names, or renaming Daylight to Reggie's Portra, would be misleading. The actual reported recipe symptom remains unspecified: wrong colors, no visible difference, preview/capture mismatch or an error require different fixes. A screenshot plus one exported capture receipt will help distinguish unsupported controls from a rendering fault.

### Recommended path, respecting the capture-time requirement

First validate the corrected preview and inspect device receipts/side-by-side output under fixed lighting. Then choose deliberately between:

- Honest, calibrated **phone-ISP approximations**, clearly labelled as approximations and limited to exposed controls; or
- A real sensor/RAW-development pipeline, if the device exposes suitable RAW, with demosaic/color management and a licensed 3D look integrated during development. This is new substantial work, not a claim that the current JPEG beta already does it.

Do not silently add a LUT on top of the finished JPEG as the supposed solution to capture-time recipes.

## Rotated viewfinder finding and v0.1.2 change

Android documents that TextureView already rotates the sensor image buffer according to sensor orientation, but does not handle display rotation or preview scaling:
https://developer.android.com/media/camera/camera2/camera-preview#textureview

Grain previously applied `jpegOrientation()` to the TextureView as well. With a typical 90-degree phone sensor in portrait mode, that adds a second sensor rotation and explains the sideways viewfinder.

v0.1.2 separates these paths: preview retains framework sensor correction, applies only display compensation, and center-fills/scales for crop and zoom. Still JPEG orientation stays separate and unchanged. Regression tests cover portrait sensors at 90/270 degrees, square crop, zoom, inverted display and landscape geometry. Physical-device validation is still required; this code diagnosis is not a claim of having tested the Redmi phone.
