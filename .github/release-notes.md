## Grain Photo vVERSION — Face exposure (device-test beta)

Install **Grain-vVERSION.apk** on the Redmi Note 13 Pro+.
- **From v0.2.1 – v0.3.3:** it installs as an update. Your photos, imported LUTs and tuning stay.
- **From v0.2.0 or older:** first save the photos you want to keep (photo → **Save to device gallery**), uninstall Grain, install this APK, then re-import the LUT pack (*Tune → Import LUT pack*).

### Faces set the exposure, like Fujifilm's face detection
- Before, Grain metered the whole frame. A window or a bright wall behind you pulled the exposure down, and your face came out 2–3 stops too dark, even at +0.9 EV.
- Now, when a face is found, the face sets the exposure. Fujifilm's manuals: "Intelligent Face Detection sets focus and exposure for human faces".
- A backlit face comes out bright. The background gets brighter and may blow out, as on a Fujifilm. DR-Auto still switches to DR200 to keep a little more of a bright background.
- Faces come from the phone camera's own face detector. If it finds none, Grain searches the phone's reference JPEG of the same shot.
- With face exposure on, **start at 0 EV**: +0.9 on top makes faces very bright.
- **Settings → Fujifilm recipes → Face exposure** turns it off (it is on by default). Off, or with no face in the frame, photos develop exactly as in v0.3.3.
- The receipt shows what happened: `faceExposure`, where the face was, its measured level and the EV applied.

### Also fixed
- The EXIF "Software" tag still said "Grain Photo 0.2.3". It now shows the installed version.

### Good to know
- This is not the phone's HDR. Grain, like a Fujifilm, develops one exposure without local tone mapping, so it can't keep both a bright face and a bright window. The Redmi camera merges several frames to do that.
- A face lifted by 2–3 stops shows more sensor noise, because the phone exposed for the room. If the receipts show the camera keeps underexposing backlit faces, the next step is to make the camera expose for the face at capture.

Most useful feedback: the same backlit shot again (face exposure on, 0 EV) with its receipt, and the DNG if possible.

This is a personal device-test build, signed with the project's personal key (certificate SHA-256 6e073ef6…). SHA256SUMS.txt verifies the APK download.
