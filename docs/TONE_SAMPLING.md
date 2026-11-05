# v0.1.3: shadow sampling correction

## Device evidence

The user confirmed that v0.1.2 fixes the viewfinder orientation on the Redmi Note 13 Pro Plus. An Afterhours capture receipt at 80% tone strength reported:

- Custom contrast-curve mode, no compatibility fallback and no request warnings.
- Returned RGB curves closely matching interpolation of the requested curves, on a denser grid with quantized outputs. For example, the requested red curve interpolates to 0.326580 at input 0.1; the reported value is 0.327468.
- ISO 1575 and approximately 10 ms exposure.
- AF state 6 (`PASSIVE_UNFOCUSED`). This is a report of focus failure, not a measurement of image sharpness or proof that every visible softness is due to autofocus.
- Edge enhancement OFF and high-quality noise reduction.

This is evidence against the simple diagnosis “custom curves are unsupported.” It is not proof that every ISP operation exactly follows the requested curve, nor a calibrated comparison of rendered colors.

The private photograph and full receipt are deliberately not committed to the public repository.

## Independently verified math problem

Curve version 1 used 32 equally spaced **linear-input** samples. Its first nonzero sample was at 1/31 (approximately 0.032258). Camera2 tone curves are piecewise linear between their sample points, so this leaves too few samples around the steep dark toe of the intended transfer function.

For Afterhours red at 80% strength, linear input 0.01:

- Intended analytic response: approximately **0.086242**.
- Old requested piecewise-linear response: approximately **0.054782**.
- Camera-reported response in the provided receipt: approximately **0.054741**.

Thus the curve specification itself undersampled this shadow region. This does not establish the sole cause of the overall photo's appearance.

## Change

Curve version 2 spaces sample points uniformly by the baseline sRGB-encoded brightness, then inverse-transfers those positions to **linear input coordinates**. The recipe equation and 32-point request budget remain unchanged. This allocates more points to shadows and approximates the intended equation more faithfully; it is not a second gamma operation or post-JPEG color filter.

New tests check the Afterhours shadow reference and neutral sRGB approximation at multiple dark inputs. Existing monotonicity and endpoint tests remain. The receipt records recipe version 2 and its sampling strategy.

Separately, explicitly unfocused captures (PASSIVE_UNFOCUSED / NOT_FOCUSED_LOCKED) now receive a receipt warning and an on-save notice. **This adds truthful feedback; it does not implement a new AF-lock state machine or guarantee focus.** No changes to noise-reduction preferences, exposure strategy, Fuji emulation or capture architecture are included.

## Device validation still needed

Retake a similar scene with Afterhours at 80%, EV 0 and 1× after installing v0.1.3. Keep the phone steady; tap a well-lit, contrasty subject and allow autofocus to settle before taking the shot. Compare with the previous output, recognizing that automatic exposure and lighting can change between shots. If the issue persists, inspect the new receipt for recipe version, curve sampling and focus warning.

A brighter room or daylight comparison is preferable for separating recipe behavior from high-ISO noise reduction and bright-light clipping. This is a targeted sampling fix, not a claim of calibrated film reproduction or confirmed device-level image-quality improvement.
