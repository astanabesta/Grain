## Grain Photo vVERSION — AF area (v0.5.1, device-test beta)

Install **Grain-vVERSION.apk** on the Redmi Note 13 Pro+.
- **From v0.2.1 – v0.5.0:** it installs as an update. Your photos, imported LUTs and tuning stay.
- **From v0.2.0 or older:** first save the photos you want to keep (photo → **Save to device gallery**), uninstall Grain, install this APK, then re-import the LUT pack (*Tune → Import LUT pack*).

### What this build is

The first half of the v0.5.0 focusing plan: **where the lens looks for focus**. It is v0.4.0's camera
back plus the AF area engine. Nothing in the capture pipeline changed — the same RAW development, the
same recipes, the same receipts.

### The AF area

**MENU → AF / FACE → AF AREA** cycles five modes. The region goes on every request, so continuous AF
searches only inside it, and a one-shot focus triggers on it.

| Mode | What the lens gets |
| --- | --- |
| **SINGLE** | The point you tap, or the lever puts there. This is the default and it is the old behaviour. |
| **MULTI** | A grid across the frame. The lens can pick any cell in it — this is the multi-point AF a real camera has. |
| **ZONE** | One larger box around the point. |
| **CENTER** | The middle of the frame, regardless of where the point is. |
| **FACE** | The last face the camera's own detector reported, padded. Follows the face. |

The grid (MULTI) or the box (ZONE) is drawn on the preview so you can see the region you are asking for.

**MENU → AF / FACE → AF LOCK** holds both the region and the lens where they are. With it on, the
autofocus does not hunt again — the same job the AE-L button does for exposure, for focus.

Everything is remembered: the mode, the lock, and the point.

### Still moving the point

Touch the preview, or drag the lever, moves the point in SINGLE / MULTI / ZONE / CENTER. In **FACE** the
region follows the detected face, so the point is ignored there.

### What to look for

- Does the grid in MULTI actually make the lens search more places than SINGLE?
- Does **FACE** hold on a face when it moves, or does it jump?
- Does **AF LOCK** stop the lens hunting when you take the photo?

### What is still missing

**Eye AF is not in this build.** It needs one number from your phone: whether the Camera2 HAL returns
face landmarks at all. v0.5.0 already reports it — **Camera diagnostics** (Q → diagnostics) shows the
`focus` block, and the one line that matters is `faceEyes` in a photo's receipt when a face is in frame.
Send that, and eye AF follows in v0.5.2.

Manual focus aids (peaking + magnifier) and AF-C face tracking are also still to come.

### The receipt

Every photo's receipt now reports the area in `requested.applied`: `afArea` (SINGLE / MULTI / ZONE /
CENTER / FACE) and `afLock`, alongside the existing `afState` in `actual`.

This is a personal device-test build, signed with the project's personal key (certificate
SHA-256 6e073ef6…). SHA256SUMS.txt verifies the APK download.
