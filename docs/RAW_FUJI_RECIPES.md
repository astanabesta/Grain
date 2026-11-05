# Fujifilm recipes from RAW (v0.2.0)

Grain's first Fujifilm recipe, **Reggie's Portra**, is developed from the phone's sensor RAW. It is not
a filter on the phone's finished JPEG.

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
6. **Recipe controls** (Grain's approximations, not Fujifilm measurements):
   - Color = OKLab chroma scale;
   - Color Chrome = depth on saturated colours;
   - FX Blue = depth on blues;
   - grain = tileable luminance-weighted noise;
   - chroma noise reduction in the code domain;
   - WB shift = 0.04 log2 per step.
7. **Output**: a JPEG at quality 95 with EXIF copied from the phone JPEG. The receipt's
   `development` block records every number used.

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
- that a flat Bayer field develops flat and neutral.

## What the first phone test must establish

- That the Redmi exposes RAW with lens-shading maps and matrices, from the Diagnostics → `rawProbe`
  block.
- Exposure and white point on real scenes, from the Grain JPEG vs the phone JPEG and DNG.
- How the colour of skin, sky and foliage compares with known Classic Chrome renderings.
- Development time and memory on the phone (target: at most a few seconds).

Desktop harness: `tools/DevelopRaw.java` develops a raw Bayer dump with any `.cube` file. No Android is needed.
