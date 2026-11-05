## Grain Photo vVERSION — tune your films, plus optional Halation and Bloom (device-test beta)

Install **Grain-vVERSION.apk** on the Redmi Note 13 Pro+.
- **From v0.2.1 or v0.2.2:** it installs as an update. Your photos and imported LUTs stay.
- **From v0.2.0 or older:** first save the photos you want to keep (photo → **Save to device gallery**), uninstall Grain, install this APK, then re-import the LUT pack (*Tune → Import LUT pack*).

### New: tune any RAW film
- Open the film sheet (**Tune** button) on any ◆ film. Every setting now has − / + buttons: Dynamic range, WB shift (red and blue), Highlight, Shadow, Color, Sharpness, High ISO NR, Clarity, Grain, Color Chrome Effect and Color Chrome FX Blue. The ranges follow Fujifilm's own menus.
- Defaults: the 10 film simulations start at **Fujifilm's standard settings** (everything 0 / Off). **Reggie's Portra** starts at its **published recipe**.
- A changed value turns orange, and the button says **Tuned ✓**. Tuning is saved separately for each film. **Reset this look** puts the film back to its default.
- **Clarity** now really works. Before this build it was ignored, which didn't matter while every film used 0. Negative Clarity softens like a light diffusion filter; positive adds local contrast.

### New: Halation and Bloom (optional extras)
- Two extra settings, **Off** by default: **Halation** (a red-orange glow around bright lights, like film without an anti-halation layer) and **Bloom** (a soft glow from highlights, like a diffusion filter). Strength 1–10.
- No Fujifilm film simulation has either, so with both Off your photos stay true to Fujifilm (and to Reggie's recipe).
- They are made from the RAW light before the film simulation, not pasted on afterwards. They show most around bright, clipped lights such as lamps, windows and the sun.

### Good to know
- Tuning and the extras change the developed photo only; the viewfinder still shows the phone's normal preview.
- Dynamic range, WB shift, Clarity, Grain, Color Chrome, FX Blue, Halation and Bloom are Grain's approximations of camera processing. The film simulation itself is Fujifilm's own LUT.
- Daylight is still untested; outdoor photos (DNG + receipt) are the most useful feedback.

This is a personal device-test build, signed with the project's personal key (certificate SHA-256 6e073ef6…). SHA256SUMS.txt verifies the APK download.
