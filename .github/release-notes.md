## Grain Photo vVERSION — first Fujifilm recipe from RAW (device-test beta)

Install **Grain-vVERSION-debug.apk** on the Redmi Note 13 Pro+ (installs over earlier Grain builds).

### New: Reggie's Portra, developed from your phone's RAW
- New ◆ **Reggie's Portra** film (Classic Chrome · DR-Auto · WB Auto R+2 B−4 · H −1 · S −1 · Color +2 · Grain Weak/Small · CCE Strong · FX Blue Weak), as published by Reggie Ballesteros on Fuji X Weekly.
- The shutter captures the sensor **RAW** plus the phone's own JPEG. Grain develops the RAW on the phone: Camera2 calibration (black/white levels, lens shading, white balance, colour matrices) → scene-linear → **F-Log2 / F-Gamut** → Fujifilm's **official Classic Chrome LUT** → stills tone model fitted on public Fujifilm RAF/JPEG pairs → Color, Color Chrome, FX Blue, grain → JPEG.
- **One-time setup:** download Fujifilm's free *GFX ETERNA 55 3D LUT* pack (about 46 MB) on the phone, then *Tune → Import LUT pack* (or *Settings → Import Fujifilm LUT pack*). The LUTs stay inside the app; they are not part of this APK or the repository.
- Each Fujifilm-recipe photo keeps the **DNG**, the **phone JPEG** and a detailed **receipt** (Settings → Keep RAW test files). *Photo → Save RAW test files* exports them to Pictures/Grain and Download/Grain.
- The viewfinder shows the phone's normal preview for this recipe; the look is applied when the photo is developed (about 1–3 s).
- The four ISP looks (Daylight, Coast, Golden, Afterhours) are unchanged.

### Honest status
- Desktop cross-check on a real Bayer RAW: Grain's engine matches a LibRaw reference through the same Fujifilm LUT within mean ΔE2000 0.65.
- The stills tone model improves on the LUT alone in leave-one-out tests (2.01 → 1.96 mean ΔE2000), but it still needs checking on this phone. Grain, Color Chrome, FX Blue, the WB-shift step size and the DR-Auto rule are Grain's approximations, not Fujifilm measurements.
- RAW support on this exact phone and firmware is confirmed only by the first test shot. Diagnostics now includes a full RAW probe.

This is a **debug-signed device-test build**, not a production release. Export important photos before uninstalling. SHA256SUMS.txt verifies the APK download.
