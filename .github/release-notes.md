## Grain Photo vVERSION — faster, cleaner RAW development (device-test beta)

Install **Grain-vVERSION.apk** on the Redmi Note 13 Pro+.

### One-time uninstall (signing fix)
Every earlier Grain APK was accidentally signed with a new key, so Android always needed the old app removed first. From this version on, builds keep one personal key, so **future updates install over this one**.
1. In Grain, open each photo you want to keep → **Save to device gallery** (photos already saved to Pictures/Grain are safe).
2. Uninstall Grain, then install this APK.
3. Re-import the Fujifilm LUT pack (*Tune → Import LUT pack*; the ZIP is probably still in your Downloads).

### What changed after your first test shots
- **Speed:** this is now a release build (not debuggable), so Android compiles the RAW developer properly. The engine itself is also leaner. Your first test took 21 s, then about 7 s; it should now be several times faster. Please check the receipt's `timingMs` to see the real number.
- **Noise:** your phone's built-in noise profile reports about 5× less noise than the RAW really has. Grain now rescales it and measures the noise curve on every frame. Noise reduction now works at two scales: colour blotches and coarse mottling go, and a fine film-like grain stays. Strength follows the recipe's High ISO NR (Reggie's Portra: −4, so a light touch).
- **Sharpness** is now applied from the recipe (Reggie's −2 = gentle).
- **Grain** is lighter where the photo already has natural noise, so high-ISO shots don't get double grain.
- **Sideways photos:** pictures taken with the phone held sideways or upside down are now saved the right way up (JPEG, original, phone JPEG and DNG).
- **Receipts:** the empty tone-curve entry is gone, and Copy now copies the formatted JSON.

### Unchanged
- Reggie's Portra recipe, colour pipeline, Fujifilm LUT import, and the four ISP looks.

### Honest status
- Tuned on two indoor shots at ISO 2300–2700. Daylight and low-ISO shots are still untested on the phone.
- Noise reduction, grain, Color Chrome, FX Blue and the WB-shift step are Grain's approximations, not Fujifilm measurements.

This is a personal device-test build, signed with the project's debug key. Export important photos before uninstalling. SHA256SUMS.txt verifies the APK download.
