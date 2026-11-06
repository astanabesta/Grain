## Grain Photo vVERSION — magnifier, AF-C face tracking, focus bracketing & in-app updates (device-test beta)

Install **Grain-vVERSION.apk** once on the Redmi Note 13 Pro+ — from this build onward, **Grain checks GitHub Releases on launch and updates itself directly inside the app**.
- **From v0.2.1 – v0.5.3:** installs cleanly as an update. Your photos, imported LUTs and tuning stay intact.
- **From v0.2.0 or older:** first export the photos you want to keep (photo → **Save to device gallery**), uninstall Grain, install this APK, then re-import your LUT pack (*Tune → Import LUT pack*).

### What's new in v0.6.0

#### 1. Focus Check Magnifier (`4×` / `8×` held-to-magnify)
- **Press and hold** anywhere on the picture to open a live `4×` or `8×` magnified loupe centered on the active focus point (or tracked face), and lift your finger to dismiss it without firing a tap-focus.
- **MENU → AF / FACE → MAGNIFIER** switches between **`OFF`**, **`4× HOLD`** (default), **`8× HOLD`**, and **`4× ON`** (continuous loupe).
- In **MANUAL** focus, half-pressing the shutter also opens the Focus Check loupe, and turning the **Command dial** while magnified steps `MF DISTANCE` (`0..30`) live with instant readout (`4× · 15/30`). When **PEAKING** is on, mid-tone sharp edges are highlighted right inside the loupe.

#### 2. AF-C Face Tracking
- **MENU → AF / FACE → FOCUS MODE** now separates **`AF-C`** (continuous tracking), **`AF-S`** (single-servo autofocus), and **`MANUAL`**.
- With **`AF AREA → FACE`** in **`AF-C`**, Grain enables Camera2 face detection on both ISP and RAW previews, tracks your subject with sticky proximity/ID locking and exponential smoothing, re-aims the live `CONTROL_AF_REGIONS` repeating request as the face moves across the frame, and draws live corner brackets that turn green on `PASSIVE_FOCUSED`.

#### 3. Focus Bracketing (`FOCUS BKT`)
- Press **DRIVE** (or **MENU → SHOOT → DRIVE**) to select **`FOCUS BKT`**.
- Configure **BKT FRAMES** (**`3`**, **`5`**, or **`7`** frames) and **BKT STEP** (**`NARROW`**, **`MEDIUM`**, or **`WIDE`**) in **MENU → SHOOT**.
- Pressing the shutter steps `LENS_FOCUS_DISTANCE` across focus planes around your current focus distance (locking AE after the first frame for consistent exposure across the stack), waits for `LENS_STATE_STATIONARY` on each plane, and captures either ISP JPEGs or developed RAW frames with per-frame `focusBracket` metadata in the receipt.

#### 4. In-App Update + Startup Notification
- On launch, Grain checks `astanabesta/Grain` GitHub Releases in the background and notifies you immediately when a newer version is published (**Update now** / **Skip** / **Later**).
- **Update now** downloads the release APK with live progress, verifies its **SHA-256** digest against GitHub's release metadata / `SHA256SUMS.txt` before touching the installer, and commits a `PackageInstaller` session directly inside Grain (on Android 12+, subsequent updates can complete without extra confirmation prompts).
- Manual check and notification toggle live in **MENU → SET UP → APP UPDATE** and **ALL PREFERENCES → APP UPDATES**.

Nothing in the RAW colour science or LUT development pipeline changed — same engines, same goldens.

Signed with the project's personal key (certificate SHA-256 `6e073ef6…`). `SHA256SUMS.txt` verifies the APK download.
