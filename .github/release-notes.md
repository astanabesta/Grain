## Grain Photo vVERSION — Back to the Craft: Portrait Mode Removed, Truer iPhone XS Max Warm

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, and photos). The portrait experiment is over — the three bundled neural networks are gone and the APK is back down to **~5.4 MB** (from ~53 MB).
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.9

#### 1. Portrait mode — removed entirely
The neural bokeh never got close enough to a real lens. The **P/PORT key is gone**, the depth/matting/segmentation models are gone, and the whole portrait develop path is reverted: Grain is again exactly the **v0.9.3 camera** — instant develops, tiny download, zero portrait code, everything still fully offline.

#### 2. XS Max Warm retuned to your reference
Measured pixel-by-pixel against your edited target shot (the one that *"looks like iPhone"*):

- **Mid-tones lifted** with a smooth taper into highlights — the reference curve is now matched within a couple of code values from shadows to skin to sky.
- **Gentler toe** so open shadows stay airy instead of blocking up.
- **Warmer auto white balance** — a touch more red, a touch less blue, aligned to the reference's red/blue ratio; skin protection and the other four iPhone profiles are unchanged.

#### 3. A tidier update dialog
- Release notes now render as clean sections with real bullets — markdown links, image tags, checksum tables, and install boilerplate are stripped automatically.
- The dialog reads top-to-bottom: **Installed vX → vY · size**, then **── What's new ──**, with the SHA-256 verification note at the end.
