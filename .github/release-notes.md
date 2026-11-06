## Grain Photo vVERSION — FX Blue that deepens the sky (device-test beta)

Install **Grain-vVERSION.apk** on the Redmi Note 13 Pro+.
- **From v0.2.1 – v0.3.2:** it installs as an update. Your photos, imported LUTs and tuning stay.
- **From v0.2.0 or older:** first save the photos you want to keep (photo → **Save to device gallery**), uninstall Grain, install this APK, then re-import the LUT pack (*Tune → Import LUT pack*).

### Color Chrome FX Blue, rebuilt from Fujifilm's own photos
- Before, FX Blue only touched very saturated blues and changed a real sky by less than 0.3 %, even on Strong. That's why Reggie's Superia skies looked thin.
- Now it matches Fujifilm's FX Blue: blue skies get darker and richer at the same hue, like a polarizing filter.
- Calibrated on Fujifilm X100V camera JPEGs of one clear sky at FX Blue Off, Weak and Strong (Fuji X Weekly's test):
  - Strong takes 9 % (pale sky) to 24 % (deep sky) of the brightness off the sky;
  - Weak takes half of that;
  - Grain reproduces Fujifilm's result within about 2 code values (out of 255).
- Skin, reds, yellows, greens, greys and white clouds are not touched.

### Where you'll see it
- **Reggie's Superia** (FX Blue Strong): clearly deeper blue skies.
- **Reggie's Portra** (FX Blue Weak): somewhat deeper skies. Its grain, texture and every other colour are still exactly Grain v0.2.0's.
- Every other film with FX Blue Weak or Strong, in both developments (First version and Clean).

### Unchanged
- With FX Blue Off, every photo develops exactly as in v0.3.2 (checked bit for bit). On the indoor test RAW, only the blue pixels changed: about 1 % of the picture.
- Color is unchanged in this version.

Most useful feedback: a blue-sky shot with Reggie's Superia, with the DNG + receipt (and the phone's own JPEG if you have it), plus the same sky with FX Blue Off for comparison.

This is a personal device-test build, signed with the project's personal key (certificate SHA-256 6e073ef6…). SHA256SUMS.txt verifies the APK download.
