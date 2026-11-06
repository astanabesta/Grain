## Grain Photo vVERSION — the Fujifilm camera back

Install **Grain-vVERSION.apk** on the Redmi Note 13 Pro+.
- **From v0.2.1 – v0.3.5:** it installs as an update. Your photos, imported LUTs and tuning stay.
- **From v0.2.0 or older:** first save the photos you want to keep (photo → **Save to device gallery**), uninstall Grain, install this APK, then re-import the LUT pack (*Tune → Import LUT pack*).

### The screen is now the back of a Fujifilm camera
Everything you touch is drawn as the back of the camera: a dark body with a milled top plate, a
viewfinder window with a glass glare, a film dial, a shutter-speed dial, a command dial, a lever, a
memo holder, a power switch and a shutter button with a knurled ring. The preview sits inside the
body where the film window would be, and the OSD (film, frames, shutter, aperture, ISO, drive, zoom,
exposure, AF point, histogram, level, battery) is engraved on the plate around it.

### Portrait, landscape and the viewfinder
- **Portrait** is the default. Turn the phone sideways and the whole back re-lays out for landscape:
  the preview rotates with the sensor, the plate moves to the long sides, and the dials re-place.
- **DISP** toggles the information display. **EYE** switches to the **EVF view**: the preview fills
  the body with a black frame, the film row stays on top, and the shutter and Q buttons sit at the
  bottom. **◀ SCREEN** (or DISP) comes back.
- The viewfinder image is the live Camera2 preview, so what you see is what the RAW develops from.

### The dials and the lever
- **FILM** turns the film dial: the four ISP looks (DAY / CST / GLN / AFT), then every imported Fujifilm
  simulation and recipe, with the selected one engraved in orange. The ◆ films are the RAW ones.
- **SHUTTER** is the shutter-speed dial (1 s … 1/4000, plus the timer and bracketing positions); tap
  **ISO/SHUTTER** to make it the ISO dial (50 … 12800) instead. The engraving and the OSD follow.
- **COMMAND** is the command dial: with EV not on **C** it sets the zoom (1×–5×, the same as pinch);
  at **C** it steps the exposure compensation in 0.1 EV within ±2 EV. The OSD shows the value.
- **LEVER** is the five-way lever (centre / up / right / down / left): up is AE-L, right steps the
  film, down opens the menu, left moves the focus point, centre confirms (Q menu, playback).
- **Q** opens the quick menu (the same 4×4 grid as before) over the preview; **OK** opens the full
  menu (I.Q. / AF / SHOOT / SET UP); **PLAY** is playback.

### New controls
- **AE-L** (lever up, or the AEL button): lock the exposure and the focus point where you point. The
  OSD shows the lock, and the receipt keeps the metered values.
- **Histogram** and **level**: the histogram is drawn from the live preview ( luma, 64 bins ) and the
  level is a two-axis horizon indicator; both are in Settings → Display, off by default.
- **Sounds**: shutter, dial clicks and focus beeps, off by default (Settings → Sounds).
- **Memo holder**: a small card on the body. Tap it to open the memo sheet (write a note about the
  shot); long-press to hold the phone's neutral view (the plain preview without the recipe look).
- **Film-sim bracketing (FS BKT)** in the drive dial: the current film plus the next two RAW films
  with a LUT, three frames per press. The DNG and the phone JPEG are kept for frame 1 only.
- **Manual shutter / ISO**: with the shutter dial on a fixed speed (or the ISO dial on a fixed ISO)
  the capture uses those values instead of the auto exposure. The receipt reports what was requested
  and what the sensor actually used (`requested.manual`, `actual`).

### Deviations from the Fujifilm design (so nothing surprises you)
- Zoom is 1×–5×, set by the command dial (when EV is not on C) or by pinch; there is no optical zoom.
- The four ISP looks (DAY / CST / GLN / AFT) are on the film dial, not in a separate mode.
- Flip to the front camera is in MENU → SHOOT → CAMERA.
- The phone's neutral view (no recipe look) is the memo long-press, not a dial position.
- FS BKT with an ISP look (a baked JPEG film) shoots a single frame, because there is no RAW to
  re-develop; the drive still advances.
- Manual shutter / ISO apply to the still only, and need Camera2 manual sensor control; if the phone
  refuses a value, the receipt says so in `requested.warnings`.
- EV **C** means the command dial is free for the zoom; exposure compensation then steps in 0.1 EV
  within ±2 EV. Outside that range the dial returns to zoom.

### Good to know
- Everything from v0.2.0 – v0.3.5 is unchanged underneath: the RAW pipeline (Camera2 calibration →
  scene-linear → F-Log2/F-Gamut → the official Fujifilm LUT you import → recipe controls → JPEG + DNG),
  the tuning, the Halation/Bloom extras, Face exposure and Sony s709.
- The layout is drawn from code (no layout XML); the screenshots in the dev CI run
  (`CameraBackScreensTest`, branch `ci-screens`) show the 16 states.
- This is a personal device-test build, signed with the project's personal key (certificate
  SHA-256 6e073ef6…). SHA256SUMS.txt verifies the APK download.

Most useful feedback: a few photos taken with the new back (portrait and landscape, one with the
EVF on) with their receipts, and what felt wrong — a dial that did not do what you expected, or a
frame that did not look like the preview.
