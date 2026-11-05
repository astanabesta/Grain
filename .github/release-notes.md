## Grain Photo vVERSION — six more Fujifilm recipes (device-test beta)

Install **Grain-vVERSION.apk** on the Redmi Note 13 Pro+.
- **From v0.2.1, v0.2.2 or v0.2.3:** it installs as an update. Your photos, imported LUTs and tuning stay.
- **From v0.2.0 or older:** first save the photos you want to keep (photo → **Save to device gallery**), uninstall Grain, install this APK, then re-import the LUT pack (*Tune → Import LUT pack*).

### New recipes (from RAW, published settings as default)
- **Kodak Gold 200**, **Kodachrome 64**, **Vibrant Arizona** (Classic Chrome), **Pacific Blues** (Classic Neg.), **CineStill 800T** (Eterna) and **Kodak Tri-X 400** (Acros), from Fuji X Weekly. They need the same Fujifilm LUT pack you already imported.
- Recipes now come right after Reggie's Portra, before the plain film simulations.
- Selecting a recipe shows its typical exposure compensation (for example +2/3 to +1 EV for Gold 200). It's a guide only: set it with the exposure control if you want it.

### New settings for every film (Tune)
- **White balance:** Auto, Daylight, Shade, Fluorescent 1/2/3, Incandescent and Kelvin (Color temperature 2500–10000 K). Fujifilm doesn't publish its preset values, so Grain uses white points measured from Fujifilm's own RAW files. WB shift works in every mode. A fixed white balance shows the light's real colour, like the camera does: CineStill 800T is made for lamps and turns teal in daylight.
- **D-Range Priority** Weak and Strong. As on the camera, it sets Highlight and Shadow itself, so those rows show "Set by DR-P".
- **Filter** for Acros films: +Ye, +R or +G (Tri-X starts with none). Color, Color Chrome and FX Blue don't change a black-and-white picture, so they're hidden for Acros.
- The viewfinder now follows a fixed white balance with the nearest phone preset. The photo itself uses the exact white point.

### Good to know
- Reggie's Portra and the plain film simulations develop exactly as in v0.2.3.
- D-Range Priority, the Acros filters, Dynamic range, WB shift, Clarity, Grain, Color Chrome, FX Blue, Halation and Bloom are Grain's approximations of camera processing. The white-balance presets are measured, with about ±150 K uncertainty. The film simulation itself is Fujifilm's own LUT.
- Most useful feedback: the fixed-white-balance recipes in daylight and under lamps, each with the DNG + receipt.

This is a personal device-test build, signed with the project's personal key (certificate SHA-256 6e073ef6…). SHA256SUMS.txt verifies the APK download.
