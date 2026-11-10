## Grain Photo vVERSION — Sony-Style Dual-Axis Level

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, tunings, and photos). Still the lean build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.25 — the leveler knows pitch now

The electronic level (SET UP → ELECTRONIC LEVEL) gains the Sony-style second axis: a **pitch chevron** riding the frame's vertical center, fed by the gravity sensor. It sits on the side the camera is tilting toward and slides toward center as you level out; roll and pitch both true → everything snaps **green** with a center dot, just like the camera in that reel.

### Previously in v0.9.24 — golden-ratio grid
FRAMING GRID cycle: Off → Thirds → Square → Diag + Square → **Golden ratio** (φ cuts at 38.2%/61.8% + center dot). If the fifth style doesn't show for you, make sure the update actually installed (this release's APP UPDATE carries it).

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, tunings, and photos). Still the lean build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.24 — phi grid

**FRAMING GRID** gains a fifth style: **Golden ratio** — the φ cuts (38.2% / 61.8%) on both axes with a center dot. The cycle is now Off → Thirds → Square → Diag + Square → Golden ratio.

### Previously in v0.9.23 — Sony-style framing grids
Grid styles: Off / Thirds / Square (square cells) / Diag + Square (corner-to-center diagonals).

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, tunings, and photos). Still the lean build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.23 — four grid styles

**SET UP → FRAMING GRID** now cycles: **Off → Thirds → Square → Diag + Square**, matching Sony's grid-line choices (Square uses true square cells across your aspect, Diag + Square adds the corner-to-center diagonals that make tilted frames instantly readable). The little Composition-grid toggle in preferences stays as the on/off shortcut and remembers your chosen style; anyone who had the grid on lands on Thirds automatically.

### Previously in v0.9.22 — Development picker for iPhone recipes
Every iPhone look gains the Fuji films' **Development (Grain)** switch (Clean / First version) in the gallery editor's new GRAIN section: Apple tone and colour as published, smoothing stages optional — First version renders grittier texture in hair, beard and fabric.

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, tunings, and photos). Still the lean build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.22 — Development picker for iPhone recipes

By request: the Fuji films' **Development (Grain)** choice now exists on every iPhone look too, inside the gallery editor under a new **GRAIN** section (right after DETAIL):

- **Clean** (default — unchanged): the faithful iPhone Smart HDR development, including its smoothing stages.
- **First version**: Grain v0.2.0's render path — smoothing stages off, hair/beard/fabric texture comes through grittier, the same way Reggie's Portra and the other films render. The look's white balance, tone and colour stay exactly as published; only the development stages differ.

Nothing else moved: the full iPhone tuner (LIGHT / COLOR / DETAIL sliders + tone curve) is untouched, the TUNED badge works as before, long-press on the row resets to Clean, and the choice is saved per recipe and reported in the receipt as `Development (Grain): First version`.

### Previously in v0.9.21 — frame stack now actually earns its noise win
Measured-noise motion gate (fixes 43% phantom gating on lying HAL profiles) + tolerance in pair-difference sigma. Still-scene stacking gain now actually reaches the photo.

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, tunings, and photos). Still the lean build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.21 — measured-noise motion gate

User evidence (thank you!): a successful 3-frame stack showed `stackGatedPct: 42.9` on a **still** scene and measured at noise parity with the single-frame shot. Root cause: the merge's motion gate was calibrated from the camera HAL's self-reported noise profile — and on MediaTek hardware that profile under-reports by ~6× (shot) to ~190× (read), so the "2.4-sigma motion tolerance" was really a 1-sigma hair-trigger: almost half of every frame's pixels were rejected as phantom "motion", and the stack merged a fraction of what it should.

The gate now runs on the same trusted noise model the develop pipeline uses: HAL profile × calibration, clamped against a measurement taken on the reference frame itself at merge time. On a still scene, gating collapses from ~43% to a few %, and the √3 (≈1.7×) noise reduction actually reaches the photo — most visible in walls, skies, skin, and shadow. A regression test replicating this phone's lying profile pins the fix.

### Previously in v0.9.20 — stack automatic can no longer stall
Receipt rekeyed to the held burst result (tryFinish timestamp-equality deadlock), terminal desync failures with counted toasts, post-assemble backstop: worst case fails clearly inside ~6 s.

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, tunings, and photos). Still the lean build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.20 — the timeout toast is gone for good

**"Photo capture timed out" with FRAME STACK = AUTOMATIC.** Found the last escape hatch, and it was a subtle one: when the camera pipeline drops the burst frame's *result* (metadata) which also carries the JPEG, v0.9.19's fallback correctly found an adjacent frame's result — but a timestamp-equality check further down the receipt path then rejected it with a *silent* return, and with the safety-net already disarmed, the shot sat until the 12 s watchdog. Three fixes close every variant of this:

1. The receipt is now keyed to the result the burst actually holds — a dropped JPEG result degrades to same-burst sibling metadata instead of deadlocking.
2. Every desync state inside burst assembly now fails the shot **immediately and loudly** (toast lists frames/JPEG/result counts) instead of waiting on anyone.
3. The success path gained a terminal guarantee: if assembly leaves the shot incompletable for any reason, it fails at once — the 6 s salvage and this backstop mean the 12 s watchdog should now be unreachable in practice.

Worst case on problematic hardware is now a clear error inside ~6 s (or a degraded single/partial-frame photo), never a frozen shot.

### Previously in v0.9.19 — critical hotfix: RAW captures develop again
Fixed the v0.9.17/18 RAW-develop NPE (stack label read after the frame release) with a new end-to-end smoke test, plus the first round of burst-desync hardening.

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, tunings, and photos). Still the lean build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.19 — two v0.9.17/18 regressions fixed

1. **"RAW development failed: …stackCount… null object reference" on every RAW shot.** The v0.9.17 EXIF label for stacked photos read the frame **after** the develop pipeline deliberately frees the 225 MB pixel buffer (`frame = null`) — so every RAW capture, stacked or not, died at the EXIF stage. The stack count is now captured before the release. A new end-to-end smoke test drives a synthetic RAW through the full develop + EXIF + JPEG path so this class of regression can never ship again.
2. **Stack-automatic shots time out at 12 s.** If the camera pipeline dropped a burst frame's *metadata result* (observed on MediaTek HALs), the receipt was keyed to a non-JPEG frame's timestamp, the finalizer's timestamp invariant failed silently, and the salvage routine then skipped itself. Now: the receipt result is keyed to the JPEG's own timestamp, missing metadata fails the shot loudly and immediately, and the salvage always resolves — merge partial, single-frame fallback, or a clear error — inside 6 s.

Apologies for the double stumble on the stacking rollout. Everything you verified in v0.9.16 (lift-aware NR, editor tuner, Q tiles) is untouched; the stack engine itself (alignment, motion gate, noise-model coupling) is unchanged.

### Previously in v0.9.18 — frame stack stabilised
Same watchdog/decisive-salvage hardening, 3 frames everywhere.

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, tunings, and photos). Still the lean build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.18

#### Fixes a possible stuck shutter on stacked captures
v0.9.17's burst could stall on "capturing RAW + phone JPEG" if the camera pipeline dropped or delayed a burst frame: the burst path registered no overall timeout and the salvage routine could leave the shot waiting on a JPEG that never came. Now:

- **Every capture path sits under the same 12 s watchdog** — single or stacked.
- **The 6 s salvage is decisive**: no JPEG → fail with a clear message; fewer frames than requested → merge the ones that arrived (a 2-frame stack is still a stack), or fall back to the plain single-frame path. A stack burst can never hang anymore.
- Stacks are **3 frames everywhere** (zoom crop and low light alike) — the difference versus 4 frames is ~1.15× noise, halve-a-heartbeat faster and kinder to conservative camera HALs.

If a stack still fails on your device, the toast now says exactly which stage starved ("n/3 raw frames in"), and **SHOOT → FRAME STACK → OFF** stays the escape hatch.

### Previously in v0.9.17 — multi-frame RAW stacking (automatic)
RAW recipes auto-burst 3 frames into one for zoom crops and low light: Bayer-phase-safe global alignment, per-pixel 2.4-sigma motion gate (moved subjects fall back to frame 1 — can never ghost), frame-measured noise model follows the stack so NR relaxes by the real gain. Receipt reports stackFrames/stackGatedPct/stackMeanShiftPx.

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, tunings, and photos). Still the lean build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.17

#### Computational photography arrives: stacks like the big vendors
RAW recipes now automatically capture a **burst of 3–4 back-to-back frames** and merge them into one, exactly the way the phone's own app closes its detail gap. Automatic, and only where it matters:

- **2× / zoomed shots** → 3 frames (a digital crop uses a quarter of the sensor's photons; the stack wins ~2× of them back).
- **Low light** (roughly ISO 400+ territory, metering-based) → 3 frames, 4 when darker.
- Bright daylight and non-RAW recipes stay plain single captures — nothing about them changes.
- **SHOOT → FRAME STACK** can turn it off (AUTOMATIC/OFF).

#### Motion-safe by construction
Frames are aligned globally (fast thumbnail search, Bayer-phase-safe shifts), then merged with a **per-pixel motion gate**: any pixel where a frame disagrees with the reference by more than ~2.4 sigma of the sensor's noise model is dropped from the average *there*. Your subject can move — the moving area simply falls back to frame 1, so the output can never ghost or double; worst case is exactly the photo you'd have taken before. Hand jitter is the *good* part: it's what alignment eats for free.

#### The numbers you can check
- The merged frame is developed by the exact same pipeline — but the noise it measures is now genuinely ~2× lower, so denoise automatically relaxes and texture survives.
- The photo receipt reports `stackFrames`, `stackGatedPct` (how much of the frame the motion gate rescued) and `stackMeanShiftPx` (how much your hands trembled).
- The saved DNG, when RAW saving is on, holds reference frame 1 (a real single capture), and the EXIF description says "N-frame stack".

Unit-tested on synthetic raw bursts: 4-frame noise variance quarters, alignment eats ±8 px shifts without tearing the Bayer pattern, and a moved subject is recovered from the reference frame bit-for-bit.

### Previously in v0.9.16 — lift-aware NR follows √gain
Shadow denoise for iPhone looks tuned down to keep shadow texture (caps 2.5× luma / 3.5× chroma on the tonal-gain response).

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, tunings, and photos). Still the lean build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.16

#### Lift-aware NR follows √gain instead of raw gain
v0.9.15's first pass was visibly cleaning shadows but smoothing too hard in the deepest zones. The extra denoise now scales with the **square root of the tonal gain** (the visible noise sigma, not the variance) and the caps drop: luma up to **2.5×** (was 3.5×), chroma up to **3.5×** (was 5×). On XS Max Warm that means the bottom octave gets ~1.6× luma / ~2.4× chroma cleanup instead of 2.7×/4× — shadow blotches are still fought, but skin texture, fabric weave and hair strands in shaded areas keep their honest detail back.

The tuner-coupling behaviour is unchanged: deepen with Shadows − and the cleanup digs in, lift Shadows + and it relaxes. Mid-tones/highlights remain pinned at exactly the v0.9.14 behaviour.

### Previously in v0.9.15 — lift-aware noise reduction for iPhone looks
Per-code luminance-gain tables of the actual tone transfer (recipe + style grade + tuner) drive the guided-filter denoise: lifted shadow zones get proportionally more cleanup, zones the tone map compresses keep old behaviour. Receipt prints the ratio as `liftNr`.

### Previously in v0.9.14 — the Q menu joins the iPhone editor
Q on iPhone looks now shows the editor tiles (EXP · HI · SHDW · SAT · SHARP · NR · CLAR · VIBR · WARM · CNTR + GRAIN, steps of 5), same per-look store as the SETTINGS editor.

### Previously in v0.9.13 — the iPhone editor tuner
Gallery-style sliders for the five iPhone looks: Light (Exposure…Fade + tone curve), Color (Saturation, Vibrance, Warmth, Tint), Detail (Sharpness, NR, Clarity) — OKLab domain, vibrance skin-protected and truly different from saturation, all −100…+100 with 0 = published look, saved per look.

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, tunings, and photos). Still the lean build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.15

#### Shadow denoise that follows the tone map (iPhone looks)
Measured on a real low-light pair (Xiaomi app vs Grain, same 1/100 s f/1.65 capture): our lifted shadows carried ~10× the vendor's noise, because the raw-stage denoiser never saw how much the iPhone tone map amplifies those zones downstream. Now it does. For every iPhone look the developer computes a 256-bin luminance-gain table of the *actual* neutral transfer (recipe tone + style grade + your tuner values, exposure included) and lets the guided-filter denoise follow it:

- **Deep-shadow zones** (the ones the look lifts hardest) get up to 3.5× luma and 5× chroma noise reduction — the red/green blotches in dark skin and bedsheets are targeted specifically.
- **Mid-tones and highlights keep today's exact behaviour** (weight pinned at 1, unit-tested), so texture there is untouched.
- **The tuner's tone sliders move the weights**: darken with Exposure − and the extra NR backs off; lift Shadows and it digs in. Zone weights are normalized to the mid-grey gain, so this adapts per look and per tuning automatically.

Fuji/Sony recipes are byte-identical to v0.9.14 (tables are only built for iPhone looks). The develop receipt now prints the shadow/mid NR ratio under `liftNr`.

### Previously in v0.9.14 — the Q menu joins the iPhone editor
Q on iPhone looks now shows the editor tiles (EXP · HI · SHDW · SAT · SHARP · NR · CLAR · VIBR · WARM · CNTR + GRAIN, steps of 5), same per-look store as the SETTINGS editor.

### Previously in v0.9.13 — the iPhone editor tuner
Gallery-style sliders for the five iPhone looks: Light (Exposure…Fade + tone curve), Color (Saturation, Vibrance, Warmth, Tint), Detail (Sharpness, NR, Clarity) — OKLab domain, vibrance skin-protected and truly different from saturation, all −100…+100 with 0 = published look, saved per look.

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, tunings, and photos). Still the lean build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.14

#### The Q menu now speaks editor on iPhone looks
v0.9.13's editor tuner lived only on the recipe SETTINGS page; pressing **Q** still showed the old Fujifilm steppers. For iPhone looks the Q grid now is:

- **FILM · WB · WB R · WB B** — as before (viewfinder follows white balance).
- **EXP** (Exposure) · **HI** (Highlights) · **SHDW** (Shadows) · **SAT** (Saturation) · **SHARP** (Sharpness) · **NR** (Noise reduction) · **CLAR** (Clarity) · **GRAIN** (kept, Fujifilm-style) · **VIBR** (Vibrance) · **WARM** (Warmth) · **CNTR** (Contrast) · **FACE** — all editor tiles step by 5, run −100…+100, and glow orange off neutral.

The same per-look values as the SETTINGS editor, of course — adjust a tile in Q and the matching slider has moved when you open SETTINGS. The remaining ten sliders (Brightness, Lightness, Light/Dark range, Whites, Blacks, Fade, Tint) and the tone curve stay on the full SETTINGS page.

### Previously in v0.9.13 — the iPhone editor tuner
Gallery-style sliders for the five iPhone looks: Light (Exposure…Fade + tone curve), Color (Saturation, Vibrance, Warmth, Tint), Detail (Sharpness, NR, Clarity) — OKLab domain, vibrance skin-protected and truly different from saturation, all −100…+100 with 0 = published look, saved per look.

- **Android (`Grain-vVERSION.apk`):** Install directly via **MENU → SET UP → APP UPDATE** (on `v0.8.9+`) or download **`Grain-vVERSION.apk`** below and tap once to update in-place (keeps all LUTs, recipes, tunings, and photos). Still the lean build — ~5.4 MB, everything fully offline.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** unchanged this round (sideload instructions as before).

### What's new in v0.9.13

#### A real photo-editor tuner for the five iPhone looks
Open any iPhone recipe's **SETTINGS** and the Fujifilm-style steppers are replaced by a true editor (White balance / Kelvin stay at top, as the viewfinder follows them). All sliders run **−100…+100** with **0 = the published look** (orange when changed; long-press a slider's name to reset it):

- **LIGHT** — Exposure · Brightness · Lightness · Contrast · Highlights · Shadows · Light range · Dark range · Whites · Blacks · Fade · **Tone curve** (drag knots, tap the graph to add one, drag below the graph to remove).
- **COLOR** — Saturation · **Vibrance** · Warmth · Tint.
- **DETAIL** — Sharpness · Noise reduction · Clarity.

#### Studied like a real editor, so there is no mistake
- **Vibrance is not saturation.** Saturation multiplies every colour equally (skin included); Vibrance's gain falls as a colour's own chroma rises — muted colours wake first — and human skin hues are protected. Both facts are locked in by unit tests.
- **Tonal edits preserve hue, colour edits preserve lightness** — everything runs in OKLab after the style's own grading.
- **Exposure is true EV** (linear light, applied before the perceptual stage), **Brightness** is mid-tone-weighted, **Lightness** is a broad airy lift that reaches shadows but protects highlights, **Contrast** pivots at mid-grey, zone sliders hit nested ranges, Whites/Blacks move the anchors, Fade lifts the black floor.
- The tone curve is rendered through a monotone cubic, so it can never solarize or band.
- Detail sliders scale the recipe's own develop-time amounts; the published look is untouched when everything is at 0 (verified bit-exact in tests).

Tuning is saved per look (Standard, XS Max Warm, Natural Rose, Rich Contrast, Amber Cozy each remember their own) and applies at develop time — the receipt and EXIF note when an editor tuning was used.
