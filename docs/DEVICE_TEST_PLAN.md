# Redmi Note 13 Pro Plus acceptance checklist

Do not label the app production-tested until these pass on the user's actual phone/firmware.

## First build / install
- [ ] GitHub Actions green: unit tests, lint, assembleDebug.
- [ ] APK artifact downloaded, ZIP extracted, APK installed; app label Grain / package dev.grain.photo.
- [ ] Existing Zircon app is still installed separately.
- [ ] Camera permission only; no microphone / broad media / network permission.
- [ ] Copy Camera diagnostics from Preferences; record Android version, firmware, camera ID, JPEG size and supported modes.

## Capture correctness
- [ ] Rear preview orientation, selfie orientation, saved EXIF orientation, portrait framing.
- [ ] All four recipes produce actual JPEG files and receipts without hangs.
- [ ] Rapidly select recipe then shutter; the receipt and photo correspond to the frozen recipe.
- [ ] With stable light / tripod / color chart, compare neutral, Daylight, Coast, Golden, Afterhours.
- [ ] Compare requested vs returned WB, tone mode/curve, edge and NR modes.
- [ ] Do not infer success merely from CaptureRequest acceptance or a matching mode field.
- [ ] Test daylight, tungsten, mixed light, skin, bright highlights, deep shadows and low light.
- [ ] Confirm whether preview and still response match sufficiently; document deviations.
- [ ] 1x/2x/5x and all ratios match preview geometry against a fixed chart. Check known scene edges, not just dimensions.
- [ ] Full-frame capture is unzoomed and uncropped but still has the recipe baked in.
- [ ] Output dimensions decrease with digital zoom; no upscaled exports.
- [ ] Balanced and Larger settings choose real supported JPEG sizes; no forced stock-camera resolution claim.

## QoL / lifecycle
- [ ] Tap focus maps correctly at corners/center for every ratio/zoom and front/rear camera.
- [ ] Pinch zoom, 1x/2x shortcuts, long-press zoom slider.
- [ ] Grid and per-film strengths survive restart; ratio/timer/keep-full preferences survive restart.
- [ ] Timer 3s/5s, tap shutter again to cancel, background app to cancel.
- [ ] Volume-button shutter.
- [ ] Background/resume, lock/unlock, flip camera repeatedly, other app temporarily using camera.
- [ ] Single in-flight save guard works; no duplicate callbacks or stuck busy state.

## Files / failure behavior
- [ ] Private gallery, photo detail, small thumbnails, capture receipt Copy.
- [ ] Export JPEG and full-frame JPEG to Pictures/Grain; share grants temporary URI access.
- [ ] Delete and Undo; delete a second photo; restart during Undo window.
- [ ] No orphaned MediaStore pending entries after an export failure.
- [ ] Storage-full save/export reports failure, not false success.
- [ ] Crop a larger capture on a memory-constrained device; observe memory, thermal behavior and latency.
- [ ] Export important images before uninstalling. Private gallery is intentionally not cloud-backed.

## Not implemented / do not imply
RAW/DNG development, HEIF capture, precise calibrated Kelvin/tint, flash capture, burst, ZSL, live histogram, optical lens switching, film grain/halation, Silver monochrome, imported 3D LUTs, gallery re-editing, production signing and Play Store release.
