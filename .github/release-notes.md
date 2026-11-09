## Grain Photo vVERSION — Q Menu Joins the iPhone Editor

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, tunings, and photos). Still the lean build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.14

#### The Q menu now speaks editor on iPhone looks
v0.9.13's editor tuner lived only on the recipe SETTINGS page; pressing **Q** still showed the old Fujifilm steppers. For iPhone looks the Q grid now is:

- **FILM · WB · WB R · WB B** — as before (viewfinder follows white balance).
- **EXP** (Exposure) · **HI** (Highlights) · **SHDW** (Shadows) · **SAT** (Saturation) · **SHARP** (Sharpness) · **NR** (Noise reduction) · **CLAR** (Clarity) · **GRAIN** (kept, Fujifilm-style) · **VIBR** (Vibrance) · **WARM** (Warmth) · **CNTR** (Contrast) · **FACE** — all editor tiles step by 5, run −100…+100, and glow orange off neutral.

The same per-look values as the SETTINGS editor, of course — adjust a tile in Q and the matching slider has moved when you open SETTINGS. The remaining ten sliders (Brightness, Lightness, Light/Dark range, Whites, Blacks, Fade, Tint) and the tone curve stay on the full SETTINGS page.

### Previously in v0.9.13 — the iPhone editor tuner
Gallery-style sliders for the five iPhone looks: Light (Exposure…Fade + tone curve), Color (Saturation, Vibrance, Warmth, Tint), Detail (Sharpness, NR, Clarity) — OKLab domain, vibrance skin-protected and truly different from saturation, all −100…+100 with 0 = published look, saved per look.

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, tunings, and photos). Still the lean build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.13

#### A real photo-editor tuner for the five iPhone looks
Open any iPhone recipe's **SETTINGS** and the Fujifilm-style steppers are replaced by a true editor (White balance / Kelvin stay at top, as the viewfinder follows them). All sliders run **−100…+100** with **0 = the published look** (orange when changed; long-press a slider's name to reset it):

- **LIGHT** — Exposure · Brightness · Lightness · Contrast · Highlights · Shadows · Light range · Dark range · Whites · Blacks · Fade · **Tone curve** (drag knots, tap the graph to add one, drag below the graph to remove).
- **COLOR** — Saturation · **Vibrance** · Warmth · Tint.
- **DETAIL** — Sharpness · Noise reduction · Clarity.

#### Studied like a real editor, so there is no mistake
- **Vibrance is not saturation.** Saturation multiplies every colour equally (skin included); Vibrance's gain falls as a colour's own chroma rises — muted colours wake first — and human skin hues are protected. Both facts are locked in by unit tests.
- **Tonal edits preserve hue, colour edits preserve lightness** — everything runs in OKLab after the style's own grading.
- **Exposure is true EV** (linear light, applied before the perceptual stage), **Brightness** is mid-tone-weighted, **Lightness** is a broad airy lift that reaches shadows but protects highlights, **Contrast** pivots at mid-grey, zone sliders hit nested ranges, Whites/Blacks move the anchors, Fade lifts the black floor.
- The tone curve is rendered through a monotone cubic, so it can never solarize or band.
- Detail sliders scale the recipe's own develop-time amounts; the published look is untouched when everything is at 0 (verified bit-exact in tests).

Tuning is saved per look (Standard, XS Max Warm, Natural Rose, Rich Contrast, Amber Cozy each remember their own) and applies at develop time — the receipt and EXIF note when an editor tuning was used.
