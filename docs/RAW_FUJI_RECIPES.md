# Fujifilm recipes from RAW (v0.2.2, engine `grain-raw-2`)

Grain's first Fujifilm recipe, **Reggie's Portra**, is developed from the phone's sensor RAW. It is not
a filter on the phone's finished JPEG.

## Film simulations on their own (v0.2.2)

Each film simulation in the imported pack is also offered on its own (`FujiRecipe.SIMULATIONS`,
`FujiRecipe.standard`): the same RAW pipeline at standard settings (DR-Auto, Auto WB, all tone,
colour, NR, sharpness, grain and Color Chrome settings at 0). A simulation appears once its LUT is
imported. The GFX ETERNA 55 v1.10 pack holds 12 F-Log2 33-point LUTs: 10 film simulations (PROVIA,
Velvia, ASTIA, CLASSIC-CHROME, REALA-ACE, PRO-Neg.Std, CLASSIC-Neg., ETERNA, ETERNA-BB, ACROS) and
two technical transforms (FLog2-709, WDR-709), which are video display conversions and are not
offered as films. Pro Neg. Hi and Nostalgic Neg. are listed for packs that include them.

The film sheet's Fujifilm tiles are `LutPreview` thumbnails: a reference sRGB picture, linearised,
metered to mid-grey, converted to Rec.2020 and F-Log2, then run through the real LUT. They show how
the simulations differ; photos are always developed from RAW.

## Pipeline

1. **Capture** (`GrainCamera`, `RawSupport`): Camera2 `RAW_SENSOR` and the phone's JPEG come from the
   same exposure, paired by sensor timestamp. AE runs normally, with headroom for DR-Auto
   (−1/3 EV; DR200 −1, DR400 −2). The lens-shading map is requested. The capture records black and
   white levels (dynamic if reported), the as-shot neutral, colour/forward/calibration matrices,
   illuminants, ISO, exposure and the noise profile. The DNG is written with `DngCreator`.
2. **Sensor to scene** (`develop/RawDeveloper`, `ColorMath`):
   - black subtraction, lens-shading gains and white balance;
   - Malvar–He–Cutler demosaic, with a soft highlight roll-off toward neutral where green clips;
   - DNG colour processing: the forward-matrix path if present, otherwise the colour matrix plus a
     Bradford adaptation, interpolated between the two illuminants by CCT;
   - linear Rec.2020 / D65, which is how F-Gamut is treated here.
3. **Exposure**: a centre-weighted log-average is mapped to the key measured on public Fujifilm
   JPEGs (0.148). The user's EV is applied here.
4. **Tone** (`develop/ToneModel`):
   - F-Log2 encoding (data sheet v1.1), plus stills offsets fitted on public RAF/JPEG pairs: a short
     highlight shoulder, a small shadow lift, and Highlight/Shadow/DR modifiers;
   - if the RAW clips before the curve's white point, the shoulder is expanded so clipped areas
     still reach white.
5. **Film simulation**: Fujifilm's official 33-point `FLog2_to_<SIM>` LUT (trilinear). You import it
   from the free *GFX ETERNA 55* pack. It is stored privately in the app and is never committed,
   bundled or exported.
6. **Noise** (v0.2.1, before the LUT, in the F-Log2 code domain):
   - noise curve: the camera's `SENSOR_NOISE_PROFILE` (green) is the baseline, scaled ×5.3 because
     on this phone it under-reports noise variance about five-fold (measured on the first two test
     DNGs), then re-fitted on each frame from same-colour pixel differences in flat 8×8 blocks
     (clamped to 0.5–1.6× / 0.5–12× of the baseline);
   - low frequencies: a quarter-resolution pass (4×4 RAW block means → codes) removes blotchy luma
     noise with a self-guided filter and colour blotches with a luma-guided chroma filter;
   - pixel scale: a self-guided 5×5 filter whose epsilon follows the noise curve through gain, lens
     shading and the tone curve; High ISO NR sets strength and how much fine grain is kept;
   - Sharpness: unsharp mask on the denoised luma (Reggie's −2 is gentle).
7. **Recipe controls** (Grain's approximations, not Fujifilm measurements):
   - Color = chroma scale in the code domain (calibrated to match the earlier OKLab step);
   - Color Chrome = depth on saturated colours;
   - FX Blue = depth on blues;
   - grain = cached tileable luminance-weighted textures; the amount is reduced where the image's
     own noise already provides texture (never below a quarter of the recipe amount);
   - WB shift = 0.04 log2 per step.
8. **Output**: a JPEG at quality 95 with EXIF copied from the phone JPEG. The receipt's
   `development` block records every number used, including per-stage timings.

## Evidence so far

| Check | Result |
|---|---|
| Java engine vs LibRaw, real Bayer RAW (GFX100S crop), same LUT | mean ΔE2000 **0.65**, p95 1.44 |
| Stills tone model, leave-one-out on 11 default Fujifilm shots | 2.01 → **1.96** mean ΔE2000 (LUT alone → model) |
| Highlight/Shadow gains across leave-one-out folds | H −0.011…−0.016, S +0.003…+0.011 per step (consistent) |
| Color gain per step | +0.011 joint fit; folds −0.05…+0.04 (**weak**) |
| DR200 modifier | small and weakly determined; DR400 assumed to be 2 × DR200 |
| Deep shadows (below −4 EV) | the fit was non-monotonic (noise or flare), so these knots hold the −4 EV value |

Local JUnit tests (`RawEngineTest`) check:
- the F-Log2 round trip;
- the tone table against the direct curve;
- curve monotonicity;
- the LUT identity and its binary round trip;
- neutral preservation on both colour paths;
- that a flat Bayer field develops flat and neutral;
- that the frame noise measurement recovers a known noise curve;
- that noise reduction cleans flat areas without widening a strong edge.

## First phone test (v0.2.0 → v0.2.1)

Two Redmi shots (ISO 2742 and 2363, indoor light), each with DNG, phone JPEG and receipt:

| Finding | Change in v0.2.1 |
|---|---|
| The desktop harness reproduces the phone's development exactly (same gain, CCT, DR) | used for all tuning |
| Exposure is camera-like: indoor scenes are developed brighter than the phone's own JPEG (+1.8 EV on the darkest) | kept, by design |
| Heavy noise; the camera's noise profile is about 5× too low | rescaled + per-frame measurement; two-scale NR |
| Colour blotches and coarse mottling in shadows | quarter-resolution luma and chroma NR |
| 21 s first photo, about 7 s after | release (non-debuggable) build; faster engine |
| Photos taken with the phone sideways were saved sideways | device rotation added to JPEG/DNG orientation |

On the desktop harness (2 threads), a full 12.5 MP development takes about 0.7 s warm. Time on the phone
must be re-measured with the receipt's `timingMs`.

## What the next phone test should check

- Development time on the phone (`timingMs`, target: about 2 s or less).
- Daylight and low-ISO scenes (the first test was indoor at ISO 2300–2700).
- Skin, sky and foliage colour against known Classic Chrome renderings.
- Landscape shots now save upright.

Desktop harness: `tools/DevelopRaw.java` develops a raw Bayer dump with any `.cube` file. No Android is needed.
