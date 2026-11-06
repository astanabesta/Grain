## Grain Photo vVERSION — Sony s709 (device-test beta)

Install **Grain-vVERSION.apk** on the Redmi Note 13 Pro+.
- **From v0.2.1 – v0.3.4:** it installs as an update. Your photos, imported LUTs and tuning stay.
- **From v0.2.0 or older:** first save the photos you want to keep (photo → **Save to device gallery**), uninstall Grain, install this APK, then re-import the LUT pack (*Tune → Import LUT pack*).

### Sony's cinema look, from your phone's RAW
- A new film, **Sony s709**: Sony's own look for its cinema cameras. In Sony's words, it was "developed alongside VENICE … subtle colours, smooth colour gradation and a softer low contrast tone curve. s709 is close to the film colour characteristics."
- **One-time setup:** pick *Sony s709* in the film strip → *Tune* → **Download from Sony** (s709Cubes.zip, about 0.5 MB, from Sony's S-Log grading guide) → **Import s709 zip** and choose the zip. Grain stores it only inside the app.
- How it works: your RAW is developed into Sony's S-Log3 / S-Gamut3.Cine with Sony's published formulas, converted exactly for every pixel, then run through Sony's official s709 LUT.
- Grain's own choices: the scene is metered to Sony's 18 % grey, and where the phone's RAW clips the highlight shoulder is stretched, so bright areas still reach white instead of flat grey.
- Settings: white balance and WB shift, Highlight, Shadow, Color, Sharpness, High ISO NR, Clarity, Grain and the extras all work. All at 0 / Off is plain s709. Dynamic range, Color Chrome Effect and FX Blue are Fujifilm settings, so they aren't offered.
- Face exposure works for Sony s709 too.

### Good to know
- s709 is a video look: lower in contrast and darker than the Fujifilm films (18 % grey at about 111 of 255). Use the exposure control or Highlight / Shadow if you want it brighter.
- It hasn't been compared with a real Sony camera; it is Sony's published look applied to this phone's RAW.
- The Fujifilm films develop exactly as in v0.3.4.

Most useful feedback: a few Sony s709 photos (daylight, a face, a bright sky) with their receipts, and the DNG if possible.

This is a personal device-test build, signed with the project's personal key (certificate SHA-256 6e073ef6…). SHA256SUMS.txt verifies the APK download.
