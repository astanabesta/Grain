# Fujifilm recipes from RAW (v0.3.3, engine `grain-raw-3`)

Grain's Fujifilm recipes are developed from the phone's sensor RAW. They are not filters on the
phone's finished JPEG. v0.3.2 has eight published recipes (Reggie's Portra and Reggie's Superia,
plus six from Fuji X Weekly) and every film simulation in the imported pack on its own. v0.3.1 added
the **Development** setting: Reggie's Portra develops like Grain's first version (v0.2.0) again,
every other film stays on Clean (see [Development](#development-first-version-and-clean-v031)).
v0.3.2 adds **Reggie's Superia**, which starts on First version like his Portra. v0.3.3 rebuilds
**Color Chrome FX Blue** from Fujifilm's own camera JPEGs, for every film and both developments
(see [FX Blue](#color-chrome-fx-blue-v033)).

## Published recipes (v0.3.0, Reggie's Superia v0.3.2)

Each recipe's default is its published settings (`FujiRecipe.PUBLISHED`); tuning and *Reset this
look* work as for every film. Recipes are listed before the plain film simulations, in
`FujiRecipe.PUBLISHED` order (Reggie's two first); ids never change.

| Recipe (id) | Film simulation | White balance | DR | Highlight / Shadow | Color | Grain | CCE / FX Blue | Sharpness / NR / Clarity | Typical EV |
|---|---|---|---|---|---|---|---|---|---|
| Reggie's Portra (4)¹ | Classic Chrome | Auto, R+2 B−4 | DR-Auto | −1 / −1 | +2 | Weak, Small | Strong / Weak | −2 / −4 / 0 | — |
| Reggie's Superia (23)¹ | Classic Neg. | Auto, R+1 B−3 | DR-Auto | −2 / −1 | +1 | Strong, Large | Strong / Strong | −2 / −4 / 0 | — |
| Kodak Gold 200 (17) | Classic Chrome | Daylight, R+4 B−5 | DR400 | −1.5 / +0.5 | +3 | Strong, Small | Weak / Off | −2 / −4 / −2 | +2/3 to +1 |
| Kodachrome 64 (18) | Classic Chrome | Daylight, R+2 B−5 | DR200 | 0 / +0.5 | +2 | Weak, Small | Strong / Off | +1 / −4 / +3 | 0 to +2/3 |
| Pacific Blues (19) | Classic Neg. | 5800K, R+1 B−3 | DR400 | −2 / +3 | +4 | Strong, Large | Strong / Weak | −2 / −4 / −3 | +2/3 to +1 |
| Vibrant Arizona (20) | Classic Chrome | 4350K, R+6 B−8 | DR-P Strong | set by DR-P | +4 | Weak, Small | Off / Weak | −2 / −4 / −3 | +2/3 to +1 1/3 |
| CineStill 800T (21) | Eterna | Fluorescent 3, R−6 B−4 | DR400 | 0 / +2 | +4 | Strong, Large | Strong / Weak | −3 / −4 / −5 | −1/3 to +2/3 |
| Kodak Tri-X 400 (22) | Acros | Daylight, R+9 B−9 | DR200 | 0 / +3 | n/a | Strong, Large | (Strong / Off, not applied) | +1 / −4 / +4 | +1/3 to +1 |

¹ Developed as *First version* by default (Reggie's Portra since v0.3.1, Reggie's Superia from the
start); all other recipes develop as *Clean*.

Reggie's Portra and Reggie's Superia are by Reggie Ballesteros; Fuji X Weekly published Superia on
2026-03-12 for X-Trans V (also usable on X-Trans IV, where blue renders a little less deeply) with
ISO Auto up to 6400. At his request neither has a typical exposure. Superia is his Classic Negative
take: moodier, grainier and more contrasty than his Portra, made for skin tones in directional light.
Gold 200, Kodachrome 64, Pacific Blues, Vibrant Arizona and CineStill 800T are by Ritchie Roesch;
Tri-X 400 is Anders Lindborg's recipe in Ritchie Roesch's X100V version (published for ISO
1600–12800). Sources are linked in `FujiRecipe` and in each photo's receipt. The typical exposure
compensation is shown in the app as guidance only; Grain never applies it automatically (the
exposure control does).

## Tuning (v0.2.3, extended in v0.3.0)

Every RAW film can be tuned in the film sheet (`FujiRecipe.Tuning`, `FujiRecipe.with`,
`TuningControl`, saved per film by `RecipeTuning` as `tune.<film key>`). The default is the film's
own: Fujifilm's standard settings for a simulation on its own, the published settings for a
recipe. *Reset this look* returns to it. Ranges follow Fujifilm's menus:

| Setting | Values |
|---|---|
| Filter (Acros only, v0.3.0) | None, +Ye, +R, +G |
| Dynamic range | DR-Auto, DR100, DR200, DR400, DR-P Weak, DR-P Strong (capture headroom follows: −1/3, 0, −1, −2, −1, −2 EV) |
| White balance (v0.3.0) | Auto, Daylight, Shade, Fluorescent 1, 2, 3, Incandescent, Kelvin |
| Color temperature (v0.3.0) | 2500–10000 K in 50 K steps (Kelvin only) |
| WB shift | R and B −9…+9, in every white-balance mode |
| Highlight, Shadow | −2…+4 in 0.5 steps (locked, "Set by DR-P", under D-Range Priority) |
| Color | −4…+4 (not shown for black and white) |
| Sharpness, High ISO NR | −4…+4 |
| Clarity | −5…+5 |
| Grain | Off, Weak/Strong × Small/Large |
| Color Chrome Effect, FX Blue | Off, Weak, Strong (not shown for black and white) |
| Development (Grain extra, v0.3.1) | Clean, First version (Reggie's Portra and Superia start on First version) |
| Halation, Bloom (Grain extras) | Off, 1–10 |

A setting that another one overrides stays visible but muted, with the reason (Highlight and
Shadow under DR-P, Color temperature outside Kelvin).

The receipt's `recipe` block records the effective settings, `tuned`, and the default values when
tuned; the JPEG description says "tuned". Clarity (new in v0.2.3) is a mid-band local-contrast
step on the display-code luma (the band between the 4×4 blocks and a Gaussian of 0.8 % of the long
side, weighted to mid-tones). Fujifilm does not publish its Clarity, so this is an approximation;
negative values soften like a light diffusion filter.

## Grain extras: Halation and Bloom (v0.2.3)

No Fujifilm film simulation has halation or bloom, so both are **Off** by default and are labelled
as Grain extras, not Fujifilm settings. They are steps 1–10, applied in scene-linear light before
F-Log2 and the LUT, so the film simulation renders the glow like any other light:

- normalised redistribution E′ = keep·E + glow: uniform areas are unchanged (tested), light only
  moves around structure;
- **Halation**: a second exposure of mainly the red record (green 0.3×, no blue), Gaussian σ 0.6 %
  of the long side, 1.5 % of the light per step — a red-orange rim around bright lights;
- **Bloom**: neutral, σ 1.5 % of the long side, 1.5 % per step — a soft diffusion-filter glow;
- clipped light sources (blocks whose green pixels clipped) glow as 8× their clipped value, an
  assumption because the RAW lost their true brightness; the inside of a clipped source keeps its
  colour. A saturated red object (green not clipped) is not boosted.

At 0 the development is bit-identical to v0.2.2. The extras affect the developed photo only, not
the viewfinder.

## Development: First version and Clean (v0.3.1)

Grain's own choice of how to develop the RAW, not a Fujifilm setting (it is the first row of the
Grain extras; saved per film as `development`; settings saved before v0.3.1 keep the film's
default). **Reggie's Portra and Reggie's Superia start on First version, every other film on
Clean**; *Reset this look* returns to that default.

| | First version (Grain v0.2.0) | Clean (v0.2.1 and later) |
|---|---|---|
| Colour noise | box blur of the opponent codes (r − Y, b − Y), luma kept; radius by ISO (1 up to ISO 200, 2 up to 800, 3 up to 3200, else 4), +1 per 3 steps of High ISO NR above −4, at most 4 | quarter-resolution luma-guided chroma filter plus a local Wiener step, from the measured noise curve |
| Luminance noise | none at High ISO NR −4; each step above adds 1/8 of Clean's fine-scale (pixel) luma filter | two scales (pixel guided filter + quarter resolution), from the measured noise curve |
| Sharpening | none at Sharpness −2; ±0.1 per step (softer below −2, sharper above) | 0.45 + 0.1 × Sharpness (0.25 at −2) |
| Grain | the recipe amount, whatever the sensor noise | topped up to the recipe amount only (sensor noise counts) |
| Color / Color Chrome | in OKLab (v0.2.0 constants) | in display-code opponent space, matched to the OKLab step |

Everything else is shared: RAW calibration, white balance (Auto and the fixed modes), metering,
Dynamic range and DR-P, the tone model, the LUT, FX Blue (since v0.3.3), Highlight/Shadow,
Clarity, the Acros filters, Halation and Bloom, orientation and the receipt.

The First version is the v0.2.0 code path, kept exactly except FX Blue, which v0.3.3 replaced in
both developments (see [FX Blue](#color-chrome-fx-blue-v033)). Up to v0.3.2, Reggie's Portra on
First version was **bit-identical** to the v0.2.0 engine on the phone test DNG (ISO 2742; tag
`v0.2.0`, with the same grain texture placement: same textures, offset per photo), and that v0.2.0
engine reproduces the phone's own v0.2.0 JPEG of that frame (mean |difference| 0.36 of 255 after a
light blur, which removes the random grain). Since v0.3.3 a JUnit golden test pins that First
version and Clean with FX Blue Off are bit-identical to v0.3.2 (so First version to v0.2.0), and
pins the hash of the new FX Blue. Reggie's Portra (FX Blue Weak) therefore differs from v0.2.0 only
in blues. On Clean, films with FX Blue Off are bit-identical to v0.3.0.

Measured on that DNG (12.5 MP, ISO 2742), fine texture (luma, after a 1.5 px blur is removed) and
colour noise, First version vs Clean: centre 11.0 vs 7.8 and 4.4 vs 3.0; a dark corner 17.1 vs 12.1
and 5.7 vs 3.9. Tone and colour are otherwise the same (mean RGB within 1 level; Clean's shadows are
about 1.3 levels darker, probably because less noise is clipped at black).

## Color Chrome FX Blue (v0.3.3)

Up to v0.3.2, FX Blue barely moved a real sky: it acted only on very saturated blues and darkened by
at most 5 %. On sky colours through Reggie's Superia it changed lightness by 0–0.3 %, while
Fujifilm's own FX Blue darkens a clear sky by 6–17 % (Strong, OKLab lightness).

v0.3.3 replaces it, for every film and both developments, with a model fitted to Fujifilm camera
JPEGs: Fuji X Weekly's X100V test of one clear sky at FX Blue Off, Weak and Strong
([2020-06-30](https://fujixweekly.com/2020/06/30/fujifilm-x100v-new-feature-color-chrome-effect-blue/),
Kodachrome 64 recipe). In ten 280 × 40 px sky bands from the deep top to the pale bottom:

- every channel drops by nearly the same amount (deepest band: R −38, G −37, B −36 code values), so
  the hue stays and the blue gets darker and relatively richer, like a polarizing filter;
- Weak is exactly half of Strong;
- the drop grows with how blue the colour is: Strong takes 9 % of the luma off the palest band and
  24 % off the deepest;
- Color Chrome Effect does not change the sky.

Grain applies it right after the Fujifilm LUT, on display codes: every channel −= depth ×
smoothstep(C; 0.08, 0.45) × hb⁴ × Y′, where Y′ is the BT.709 luma, C the chroma in the
(B′ − Y′, R′ − Y′) plane and hb the cosine to the sky-blue direction (OKLab hue ≈ 264°); depth is
0.277 for Strong and half for Weak. Over the 10 bands × Weak/Strong it reproduces Fujifilm's red and
green within 2.0 code values (rms 0.7), and blue within 2.4 on the two bands whose blue is not
clipped (elsewhere the Off JPEG's blue is clipped at 255, so Fujifilm's blue drops a little less
there). Neutrals, grey-blue clouds (below the chroma gate), skin, reds, yellows and greens are not
touched; dark blues deepen in proportion, without crushing.

| Sky through Reggie's Superia (synthetic phone RAW), FX Blue Off → Strong | v0.3.2 | v0.3.3 |
|---|---|---|
| deep | −0.2 % | −7.7 % |
| medium | −0.1 % | −7.4 % |
| pale | 0 % | −5.5 % |

Those skies start paler than the X100V test sky (Classic Negative here, a Classic Chrome recipe
there), and FX Blue, like Fujifilm's, deepens a pale blue less than a deep one. The measurement is
X-Trans IV; Reggie made Superia on X-Trans V, which renders Classic Negative blues about one FX Blue
step deeper (Fuji X Weekly). With FX Blue Off every film develops bit-identically to v0.3.2. On the
phone test DNG (indoor), Classic Neg. is bit-identical, Reggie's Superia changes 0.9 % of the pixels
and Reggie's Portra 1.1 %, all of them blue.

## White balance (v0.3.0)

**Auto** develops with the phone's as-shot white, exactly as before (bit-identical). A **fixed** mode
develops for its own white point, so the light's colour shows as it would on a Fujifilm camera set
the same way (e.g. Daylight under warm bulbs looks warm; CineStill's Fluorescent 3 turns daylight
teal-blue).

Fujifilm publishes only the preset names (Fluorescent 1/2/3 are daylight, warm-white and cool-white
tubes), not their white points. The presets were measured from Fujifilm's own data: every Fujifilm
RAF stores the camera's preset gain table (RAFData tag 0xc000: G, R, B triplets for Daylight, Shade,
Fluorescent 1–3, Incandescent, then a colour-temperature table). Public sample RAFs from
raw.pixls.us were read (images not kept or published):

| Camera | Daylight | Shade | Fl. 1 | Fl. 2 | Fl. 3 | Incandescent | D65 (standard) |
|---|---|---|---|---|---|---|---|
| X-T5 (R, B at G = 302) | 565, 560 | 618, 486 | 718, 485 | 611, 601 | 580, 746 | 381, 842 | 646, 494 |
| X-T4 | 554, 574 | 605, 496 | 712, 505 | 606, 627 | 580, 789 | 375, 878 | 632, 506 |
| GFX100 II | 531, 540 | 586, 460 | 685, 455 | 575, 551 | 540, 723 | 353, 830 | 607, 475 |

The gains were converted to white points two ways: with each camera's DNG colour matrix, and by
interpolating between Fujifilm's own standard-A and D65 gains (the two disagree by about
10 mired). A GFX100 II frame shot at Kelvin 5500 lands on the camera's own colour-temperature table.
Grain uses the compromise values (CCT on the Planckian locus, Duv in CIE 1960 uv; uncertainty about
±150 K, roughly one WB-shift step):

| Mode | CCT | Duv |
|---|---|---|
| Daylight | 4850 K | 0 |
| Shade | 6000 K | 0 |
| Fluorescent 1 | 6450 K | +0.0075 |
| Fluorescent 2 | 4950 K | +0.0078 |
| Fluorescent 3 | 4300 K | +0.015 |
| Incandescent | 2856 K (standard A) | 0 |
| Kelvin | the set value | 0 |

Development: the white point (`ColorMath.whiteXy`, Kim et al. Planckian locus) is turned into the
phone's camera neutral with the phone's own DNG calibration (`ColorMath.neutralForXy`), and the
colour matrix is built for that known white. The search a DNG reader does from a neutral is skipped
on purpose: this phone's two calibration matrices (D65 and A) disagree enough that one neutral can
have two self-consistent white points (a 4850 K neutral reads back as about 7200 K). The WB shift
applies on top in every mode (0.04 log2 per step, an approximation). Because the phone's sensor
differs from Fujifilm's, the same preset gives different raw gains on the phone; the white point,
not the gain numbers, is what is matched.

The viewfinder and the reference JPEG use the nearest Camera2 preset (Daylight and Fluorescent 2 →
DAYLIGHT, Shade → SHADE, Fluorescent 1 → CLOUDY_DAYLIGHT, Fluorescent 3 → FLUORESCENT, Incandescent →
INCANDESCENT; Kelvin: up to 3300 K INCANDESCENT, 3800 K WARM_FLUORESCENT, 4600 K FLUORESCENT, 5800 K
DAYLIGHT, 7000 K CLOUDY_DAYLIGHT, above SHADE); hold-to-compare shows the phone's auto white balance. The developed
photo always uses the exact white point.

## D-Range Priority (v0.3.0)

D-Range Priority replaces Dynamic Range and the Highlight/Shadow tone curve, as on the cameras (the
app locks Highlight and Shadow under DR-P). Fujifilm publishes no curve, so Grain follows Fuji X
Weekly's comparisons: DR-P Weak looks nearly identical to DR400 with Highlight −2 and Shadow −2,
with the RAW exposed like DR200; DR-P Strong is the same flat curve one DR step higher, with the RAW
exposed like DR400.

- **Weak**: DR400 tone with Highlight −2, Shadow −2; capture −1 EV.
- **Strong**: the same, plus one more stop of highlights compressed into the white when the RAW
  holds it (the shoulder maps white + 1 EV to white); capture −2 EV. When the RAW clips earlier, the
  RAW clip point maps to white as for every film.
- Fujifilm's slight mid-contrast boost under DR-P is not modelled.

## Acros filters (v0.3.0)

Acros +Ye, +R and +G are offered as a tuning *Filter* on black-and-white films (Tri-X 400 is published
with plain Acros). Fujifilm publishes no filter data. Two simpler designs were rejected after probing
the official Acros LUT:

- mixing the colours linearly and handing the LUT a grey: the Acros LUT renders saturated colours up
  to about 35 % darker than a linear mix, so every colour would brighten even with no filter;
- filtering the light before the LUT: the LUT is not scale-invariant for coloured input, so +R would
  bend the tone curve of greys (−0.95 EV at white).

Grain's filter keeps the LUT's own rendering: after the Acros LUT, each pixel's output is turned back
into its equivalent grey on the LUT's grey curve, scaled by the filter's brightness ratio (filtered
mix ÷ the LUT's own mix, probed around grey; exactly 1 for neutrals) and re-rendered on the same
curve. Filter transmissions (R, G, B): +Ye 1, 0.9, 0.1; +R 1, 0.25, 0.02; +G 0.4, 1, 0.4. On the
phone test photo, +R lightens a red figure and a wooden desk, +G darkens red, greys stay exact.

## Black and white

Color, Color Chrome Effect and FX Blue are not applied to a black-and-white film (they act on
colour), so the app hides them for Acros and the receipt says "not applied to black and white" for a
published value such as Tri-X's Color Chrome Effect Strong. White balance and WB shift still matter:
they change how colours translate to greys.

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
   (−1/3 EV; DR200 and DR-P Weak −1, DR400 and DR-P Strong −2). Auto-WB films request auto white
   balance; fixed-WB films the nearest Camera2 preset. The lens-shading map is requested. The capture records black and
   white levels (dynamic if reported), the as-shot neutral, colour/forward/calibration matrices,
   illuminants, ISO, exposure and the noise profile. The DNG is written with `DngCreator`.
2. **Sensor to scene** (`develop/RawDeveloper`, `ColorMath`):
   - black subtraction, lens-shading gains and white balance (the as-shot white for Auto, the mode's
     measured white point for a fixed white balance, then the WB shift);
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
     still reach white; DR-P uses DR400 with Highlight/Shadow −2, Strong also folding one more stop
     of highlights into the white.
5. **Film simulation**: Fujifilm's official 33-point `FLog2_to_<SIM>` LUT (trilinear). You import it
   from the free *GFX ETERNA 55* pack. It is stored privately in the app and is never committed,
   bundled or exported. On Acros, an optional filter (+Ye/+R/+G) re-renders each pixel's
   equivalent grey on the LUT's own grey curve.
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
7. **Recipe controls** (Grain's approximations, except FX Blue, which is fitted to Fujifilm JPEGs):
   - Color = chroma scale in the code domain (calibrated to match the earlier OKLab step);
   - Color Chrome = depth on saturated colours;
   - FX Blue = blues darker and relatively richer at the same hue, like a polarizer (v0.3.3, fitted
     to Fujifilm X100V JPEGs; applied right after the LUT);
   - grain = cached tileable luminance-weighted textures; the amount is reduced where the image's
     own noise already provides texture (never below a quarter of the recipe amount);
   - WB shift = 0.04 log2 per step;
   - not applied to black and white: Color, Color Chrome and FX Blue.
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
- that Reggie's Portra carries its published settings, and that LUT files are matched to F-Log2
  (never F-Log2C);
- that the frame noise measurement recovers a known noise curve;
- that noise reduction cleans flat areas without widening a strong edge;
- (v0.2.2) that every film simulation in the official pack is offered (the Rec.709 transforms are
  not), that a simulation on its own has neutral standard settings, and that the LUT preview keeps
  greys neutral and ordered;
- (v0.2.3) that Halation and Bloom leave uniform fields unchanged (clipped or not), that the
  halo around a clipped lamp is red-dominant and local and bloom is neutral, that Clarity lowers or
  raises local contrast without moving the mean, that tuning clamps to the camera ranges, that every
  app control position round-trips, and that the tone curve stays monotonic for every
  Highlight/Shadow/DR combination;
- (v0.3.0) that a fixed white point round-trips exactly on this phone's two matrices across
  2500–10000 K (the case the DNG search gets wrong), that Kelvin gains are ordered, that a fixed
  white balance ignores the as-shot white and renders its own light neutral (Incandescent blue,
  Shade warm, Kelvin = the same Planckian white, WB shift still applied), that DR-P Weak equals DR400
  with H/S −2 and ignores Highlight/Shadow while Strong only darkens highlights, that Acros filters
  leave greys bit-identical and keep the picture black and white while +R lightens red and +G
  darkens it, that the six recipes carry their published settings, and that the new settings clamp;
- (v0.3.1) that Reggie's Portra on First version is bit-identical to Grain v0.2.0 (golden hash of a
  synthetic frame with this phone's matrices, from the v0.2.0 engine; since v0.3.3 with FX Blue
  Off) and differs on Clean, that
  First version uses no sharpening and the full grain at the defaults while every High ISO NR and
  Sharpness position still changes the picture (NR cleaner, Sharpness crisper at each step), and
  that Reggie starts on First version, every other film on Clean, through tuning and `with()`;
- (v0.3.2) that Reggie's Superia carries every published setting and receipt row (no typical
  exposure), is listed right after Reggie's Portra, starts on First version (Reggie's two recipes on
  First version, every other film on Clean, unique keys), and develops with no sharpening and the
  full Strong / Large grain (heavier than Portra's Weak / Small) and deeper FX Blue;
- (v0.3.3) that FX Blue reproduces Fujifilm's X100V sky (10 bands, Weak and Strong, red and green
  within 2.5 code values), keeps the hue (every channel drops equally), leaves greys, skin, reds,
  yellows, greens and grey-blue clouds untouched, deepens the bluest golden patch by 6.5–7.1 % in
  the engine (v0.3.2: 0.4–1.4 %) while grey and warm patches stay exactly the same, and that both
  developments with FX Blue Off stay bit-identical to v0.3.2 (First version: to v0.2.0).

On the phone test DNG (ISO 2742, indoor), Reggie's Portra on First version develops bit-identically
to the v0.2.0 engine; on Clean, it and the other films develop bit-identically to v0.3.0. Adding
Reggie's Superia (v0.3.2) changed no engine code: Reggie's Portra and Pacific Blues develop
bit-identically to v0.3.1 on that DNG. The v0.3.3 FX Blue changes only blue pixels there (0.9 % for
Reggie's Superia, 1.1 % for Reggie's Portra); Classic Neg. on its own is bit-identical.

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
- (v0.3.0) The fixed-white-balance recipes in daylight and under lamps: Gold 200 and Kodachrome 64
  (Daylight) should look neutral-to-warm in sun, CineStill 800T (Fluorescent 3) close to neutral
  under warm artificial light and teal in daylight.
- (v0.3.0) Vibrant Arizona (DR-P Strong) on a bright, high-contrast scene: highlights should hold
  more detail than Kodak Gold 200 (DR400).
- (v0.3.1) Reggie's Portra should look like the v0.2.0 photos again (grain and texture); compare a
  shot with *Development: Clean*.
- (v0.3.2) Reggie's Superia on skin in directional light, the light it was made for; at high ISO,
  First version is very grainy (Strong / Large grain on top of sensor noise), so compare *Clean*.
- (v0.3.3) A blue sky with Reggie's Superia (FX Blue Strong), and the same sky with FX Blue Off:
  the sky should be clearly deeper, like with a polarizer. The DNG and receipt also show whether
  the phone's white balance or a bright sky exposure makes the sky paler than on a Fujifilm.

Desktop harness: `tools/DevelopRaw.java` develops a raw Bayer dump with any `.cube` file. No Android
is needed. `-Drecipe=<key>` picks a published recipe (e.g. `kodak-gold-200`, `vibrant-arizona`, `reggies-superia`) or
`sim-<LUT name>`; `-Dwb`, `-Dkelvin`, `-Ddr`, `-Dfilter`, `-DshiftR/-DshiftB`, `-Dhl/-Dsh`,
`-Dhalation`, `-Dbloom`, `-Dclarity`, `-Ddevelopment=CLEAN|FIRST_VERSION`, `-Dsharpness` and
`-DhighIsoNr` override single settings; `-Dgrain=true`, `-Dseed`, `-Dthreads` and `-Dev` set the
development options (`-Dnr` / `-Dsharpen` scale Clean's luma NR and sharpening, for tests).
