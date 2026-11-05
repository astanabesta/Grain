## Grain Photo vVERSION — all Fujifilm film simulations (device-test beta)

Install **Grain-vVERSION.apk** on the Redmi Note 13 Pro+.
- **From v0.2.1:** it installs as an update. Your photos and imported LUTs stay.
- **From v0.2.0 or older:** first save the photos you want to keep (photo → **Save to device gallery**), uninstall Grain, install this APK, then re-import the LUT pack (*Tune → Import LUT pack*). Earlier builds were each signed with a different key; from v0.2.1 on, every build uses the same one.

### New: every film simulation in your LUT pack
- Fujifilm's GFX ETERNA 55 pack has **12 LUTs**. **10 are film simulations**: Provia, Velvia, Astia, Classic Chrome, Reala Ace, Pro Neg. Std, Classic Neg., Eterna, Eterna Bleach Bypass and Acros. The other 2 (FLog2-709 and WDR-709) are technical video conversions, not film looks. Grain now says "10 film simulations + 2 video LUTs" instead of "12 film simulations".
- All 10 now appear as ◆ films in the film strip and the film sheet, as soon as the pack is imported. Each one is developed from RAW with Fujifilm's official LUT at **standard settings**: DR-Auto, Auto white balance, and every tone, colour, grain and Color Chrome setting at 0.
- The film sheet's Fujifilm tiles now show each simulation's real character: a reference picture run through that LUT. They're approximate guides; your photos are always developed from RAW.
- Reggie's Portra is unchanged (it's Classic Chrome with Reggie's published settings).

### Also in this build (from v0.2.1)
- Release build, so RAW development is much faster than v0.2.0.
- Noise measured on every photo, with two-scale noise reduction.
- Photos taken sideways are saved upright.

### Honest status
- The film simulation itself is Fujifilm's own LUT. The phone-to-F-Log2 step, the stills tone model, noise reduction and grain are Grain's own work. They were tuned on two indoor test shots so far; daylight is still untested.

This is a personal device-test build, signed with the project's personal key. SHA256SUMS.txt verifies the APK download.
