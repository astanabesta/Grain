package dev.grain.photo.develop;

import java.util.Locale;

/**
 * Apple iPhone 16 Pro (Smart HDR 5 + Next-Gen Photographic Styles) scene-linear RAW tone mapping,
 * Display P3 colour science, skin-undertone engine, and ISO 21496-1 Ultra HDR gain-map math.
 *
 * <p>Implements the mathematical stages documented in Apple ProRAW (DNG 1.6 / 1.7) and Apple's
 * colour pipeline without requiring any external {@code .cube} LUT file:
 *
 * <ol>
 *   <li><b>Spatially-varying local tone mapping ({@code ProfileGainTableMap} law):</b> a smooth,
 *       edge-aware quarter-resolution illumination envelope driven by a {@code max(R,G,B)}-blended
 *       scene norm lifts shadows while pulling down skies and bright windows without losing local
 *       micro-contrast or introducing silhouette halos.
 *   <li><b>Hue-preserving global S-curve ({@code RefBaselineRGBTone}):</b> DNG's sorted {@code
 *       (max, mid, min)} tone operator paired with a steep black-toe {@code ProfileToneCurve} and
 *       adaptive sensor-clip shoulder keeps HSV hue angle invariant across highlights and shadows.
 *   <li><b>Display P3 + OKLab Photographic Styles:</b> native wide-gamut Display P3 working space
 *       with dedicated skin-chroma ceiling protection, iPhone 16 Pro undertones (<i>Standard</i>,
 *       <i>Natural Rose</i>, <i>Rich Contrast</i>, <i>Amber Cozy</i>), cerulean sky alignment, and
 *       natural leaf-green calibration.
 *   <li><b>Ultra HDR Gainmap headroom:</b> recovers true pre-shoulder linear RAW highlight energy
 *       above diffuse white up to {@value #GAINMAP_RATIO_MAX}x OLED luminance.
 * </ol>
 */
public final class AppleRender {
  /** Prefix used in {@link FujiRecipe#filmSimulation} to identify built-in Apple RAW profiles. */
  public static final String PREFIX = "APPLE-";

  /** Metering key in linear Rec.2020 (places 18% grey right at Apple's open midtone anchor). */
  public static final double METERING_KEY = 0.210;

  /**
   * 65th-percentile linear face luminance target for Apple portrait metering (calibrates lit
   * foreheads to ~179/255 and lit cheeks to ~162/255 sRGB with full 3D shadow modeling).
   */
  public static final double FACE_KEY = 0.368;

  /** Maximum linear HDR/SDR highlight boost encoded into the ISO 21496-1 Ultra HDR gainmap. */
  public static final float GAINMAP_RATIO_MAX = 3.0f;

  /** Gainmap numerical stability offset matching {@code android.graphics.Gainmap} epsilon. */
  public static final float GAINMAP_EPSILON = 1f / 64f;

  private static final float LOG_RATIO_MAX = (float) Math.log(GAINMAP_RATIO_MAX);

  /** Linear BT.2020 (D65) -> Linear Display P3 (D65); exact row sums = 1.0. */
  public static final float[] REC2020_TO_P3 = {
    1.34357825f, -0.28217967f, -0.06139858f,
    -0.06529745f, 1.07578792f, -0.01049046f,
    0.00282179f, -0.01959849f, 1.01677671f
  };

  /** Linear Display P3 (D65) -> Linear BT.709 / sRGB (D65); exact row sums = 1.0. */
  public static final float[] P3_TO_REC709 = {
    1.22494018f, -0.22494018f, 0.00000000f,
    -0.04205695f, 1.04205695f, 0.00000000f,
    -0.01963755f, -0.07863605f, 1.09827360f
  };

  /** Linear BT.709 / sRGB (D65) -> Linear Display P3 (D65); exact row sums = 1.0. */
  public static final float[] REC709_TO_P3 = {
    0.82246197f, 0.17753803f, 0.00000000f,
    0.03319420f, 0.96680580f, 0.00000000f,
    0.01708263f, 0.07239744f, 0.91051993f
  };

  /** Linear Display P3 (D65) -> Ottosson OKLab LMS cone responses; exact row sums = 1.0. */
  private static final float[] P3_TO_LMS = {
    0.48137985f, 0.46211837f, 0.05650178f,
    0.22883194f, 0.65321681f, 0.11795124f,
    0.08394576f, 0.22416527f, 0.69188898f
  };

  /** Ottosson OKLab LMS cone responses -> Linear Display P3 (D65); exact row sums = 1.0. */
  private static final float[] LMS_TO_P3 = {
    3.12776899f, -2.25713580f, 0.12936681f,
    -1.09100905f, 2.41333176f, -0.32232271f,
    -0.02601081f, -0.50804133f, 1.53405214f
  };

  private static final float[] CBRT_Q = new float[2049];
  private static final float[] SRGB_ENC = new float[2049];

  static {
    for (int i = 0; i <= 2048; i++) {
      double q = i / 2048.0;
      double x = q * q * q * q;
      CBRT_Q[i] = (float) Math.cbrt(x);
      double lin = q * q;
      SRGB_ENC[i] =
          (float) (lin <= 0.0031308 ? 12.92 * lin : 1.055 * Math.pow(lin, 1.0 / 2.4) - 0.055);
    }
  }

  /** Apple iPhone Smart HDR & Photographic Styles offered natively in Grain. */
  public enum Style {
    STANDARD(
        "APPLE-STANDARD",
        "i16",
        "Smart HDR 5 · Standard",
        1.000,
        1.000,
        0.56f,
        0.84f,
        1.17,
        0.48,
        1.32),
    XS_MAX_WARM(
        "APPLE-XS-MAX",
        "iXS",
        "A12 Smart HDR · XS Max Warm",
        1.056,
        0.915,
        0.62f,
        0.86f,
        1.15,
        0.48,
        1.50),
    NATURAL_ROSE(
        "APPLE-NATURAL-ROSE",
        "iRS",
        "Smart HDR 5 · Natural Rose",
        1.000,
        1.000,
        0.60f,
        0.82f,
        1.13,
        0.49,
        1.35),
    RICH_CONTRAST(
        "APPLE-RICH-CONTRAST",
        "iRC",
        "Smart HDR 5 · Rich Contrast",
        1.000,
        1.000,
        0.35f,
        0.88f,
        1.33,
        0.48,
        1.23),
    AMBER_COZY(
        "APPLE-AMBER-COZY",
        "iAM",
        "Smart HDR 5 · Amber Cozy",
        1.036,
        0.952,
        0.56f,
        0.84f,
        1.18,
        0.48,
        1.32);

    public final String id;
    public final String dialCode;
    public final String label;
    /** Subtle Apple AWB preference multipliers on R and B camera neutral gains. */
    public final double wbRedMul, wbBlueMul;
    /** Default local shadow lift and highlight pull-down strengths (in EV). */
    public final float shadowLiftEv, highlightPullEv;
    /** Global {@code ProfileToneCurve} shape parameters. */
    public final double curveToe, curveShoulder, curveMidGain;

    Style(
        String id,
        String dialCode,
        String label,
        double wbRedMul,
        double wbBlueMul,
        float shadowLiftEv,
        float highlightPullEv,
        double curveToe,
        double curveShoulder,
        double curveMidGain) {
      this.id = id;
      this.dialCode = dialCode;
      this.label = label;
      this.wbRedMul = wbRedMul;
      this.wbBlueMul = wbBlueMul;
      this.shadowLiftEv = shadowLiftEv;
      this.highlightPullEv = highlightPullEv;
      this.curveToe = curveToe;
      this.curveShoulder = curveShoulder;
      this.curveMidGain = curveMidGain;
    }

    public static Style fromSimulation(String sim) {
      if (sim == null) return null;
      String u = sim.trim().toUpperCase(Locale.ROOT);
      for (Style s : values()) if (s.id.equals(u)) return s;
      return null;
    }
  }

  private AppleRender() {}

  public static boolean isAppleSimulation(String sim) {
    return Style.fromSimulation(sim) != null;
  }

  /**
   * 5-weight scene norm inspired by DNG 1.6 {@code ProfileGainTableMap.MapInputWeights}: blends CIE
   * luminance with {@code max(R,G,B)} so saturated blue skies and neon highlights trigger highlight
   * protection before any individual channel clips.
   */
  public static float sceneNormDisplayP3(float rP3, float gP3, float bP3) {
    float r = Math.max(0f, rP3), g = Math.max(0f, gP3), b = Math.max(0f, bP3);
    float y = 0.22897456f * r + 0.69173852f * g + 0.07928691f * b;
    float mx = Math.max(r, Math.max(g, b));
    return 0.68f * y + 0.32f * mx;
  }

  /**
   * Linear Display P3 human-skin reflectance locus used as a portrait-exposure fallback when a
   * turned/profile face was missed by the camera's frontal detector. Separates melanin/hemoglobin
   * skin (`b/g >= 0.48`) from warm walls, shirts, hair, and high-absorption wood/amber (`b/g < 0.40`).
   */
  public static boolean isPortraitSkinLinearP3(float rP3, float gP3, float bP3) {
    if (!(gP3 > 0.004f && rP3 > gP3 && gP3 > bP3)) return false;
    float rg = rP3 / gP3;
    float bg = bP3 / gP3;
    return rg >= 1.18f
        && rg <= 2.35f
        && bg >= 0.45f
        && bg <= 0.86f
        && (rP3 - bP3) > 0.03f * gP3
        && rg * bg > 0.72f;
  }

  /**
   * Spatially-varying local exposure delta (in stops) for a smoothed illumination envelope {@code
   * envEv = log2(u / METERING_KEY)}. Leaves lit skin and upper midtones ({@code 0 .. +1.30 EV})
   * uncompressed to preserve 3D facial modeling, lifts shadow foregrounds while anchoring jet-black
   * hair ({@code < -2.65 EV}), and pulls bright walls/windows/skies smoothly back into range.
   */
  public static float localToneDeltaEv(
      float envEv, Style style, double highlightTone, double shadowTone) {
    if (envEv < 0f) {
      float lift = style.shadowLiftEv - (float) (0.14 * shadowTone);
      lift = Math.max(0f, Math.min(1.65f, lift));
      if (lift == 0f) return 0f;
      float neg = -envEv;
      float shaped = (float) Math.tanh(neg / 1.90);
      float blackGuard = 1f - 0.72f * sstep(2.65f, 4.65f, neg) - 0.28f * sstep(4.65f, 6.60f, neg);
      return lift * shaped * blackGuard;
    }
    float pull = style.highlightPullEv - (float) (0.14 * highlightTone);
    pull = Math.max(0.15f, Math.min(1.85f, pull));
    return -pull * sstep(1.30f, 3.00f, envEv);
  }

  /** Precomputed 4097-entry monotonic global {@code ProfileToneCurve} over {@code [0, whiteLin]}. */
  public static final class ToneTable {
    public final float whiteLin;
    public final float invWhite;
    final float[] lut = new float[4097];

    public ToneTable(Style style, double whiteLinear, double highlightTone, double shadowTone) {
      double wl = Math.max(0.95, Math.min(3.5, whiteLinear));
      this.whiteLin = (float) wl;
      this.invWhite = 4096f / this.whiteLin;

      double toe = Math.max(1.02, Math.min(1.75, style.curveToe + 0.055 * shadowTone));
      double shoulder = Math.max(0.34, Math.min(1.18, style.curveShoulder - 0.055 * highlightTone));
      double midGain = style.curveMidGain;
      double fw = rawCurve(wl, toe, shoulder, midGain);
      double invFw = 1.0 / Math.max(1e-6, fw);

      float prev = 0f;
      lut[0] = 0f;
      for (int i = 1; i < 4096; i++) {
        double x = wl * (i / 4096.0);
        double y = rawCurve(x, toe, shoulder, midGain);
        double t = (x - 0.30) / Math.max(0.16, wl - 0.30);
        t = t < 0 ? 0 : t > 1 ? 1 : t;
        double ts = t * t * (3.0 - 2.0 * t);
        double scale = 1.0 + ts * (invFw - 1.0);
        float val = (float) Math.min(1.0, Math.max(0.0, y * scale));
        if (val < prev) val = prev;
        lut[i] = val;
        prev = val;
      }
      lut[4096] = 1f;
    }

    private static double rawCurve(double v, double toe, double shoulder, double midGain) {
      if (v <= 0.0) return 0.0;
      double u = v / 0.18;
      double up = Math.pow(u, toe) * midGain;
      return 0.18 * up / (1.0 + shoulder * Math.pow(up * 0.18, 0.88));
    }

    public float eval(float x) {
      if (x <= 0f) return 0f;
      if (x >= whiteLin) return 1f;
      float p = x * invWhite;
      int i = (int) p;
      if (i >= 4096) return 1f;
      return lut[i] + (p - i) * (lut[i + 1] - lut[i]);
    }
  }

  /**
   * Applies {@code table} using DNG's hue-preserving {@code RefBaselineRGBTone} sorted {@code (max,
   * mid, min)} operator blended 84/16 with luminance-ratio scaling so hue angle stays invariant
   * while deep shadows avoid excess chroma expansion.
   */
  public static void applyRgbTone(float r, float g, float b, ToneTable table, float[] out) {
    float cr = Math.max(0f, r), cg = Math.max(0f, g), cb = Math.max(0f, b);
    float mx, md, mn;
    int order;
    if (cr >= cg) {
      if (cg >= cb) {
        mx = cr;
        md = cg;
        mn = cb;
        order = 0; // R >= G >= B
      } else if (cr >= cb) {
        mx = cr;
        md = cb;
        mn = cg;
        order = 1; // R >= B > G
      } else {
        mx = cb;
        md = cr;
        mn = cg;
        order = 2; // B > R >= G
      }
    } else {
      if (cr >= cb) {
        mx = cg;
        md = cr;
        mn = cb;
        order = 3; // G > R >= B
      } else if (cg >= cb) {
        mx = cg;
        md = cb;
        mn = cr;
        order = 4; // G >= B > R
      } else {
        mx = cb;
        md = cg;
        mn = cr;
        order = 5; // B > G > R
      }
    }

    float tMax = table.eval(mx);
    float tMin = table.eval(mn);
    float span = mx - mn;
    float tMid = span > 1e-7f ? tMin + (tMax - tMin) * ((md - mn) / span) : tMax;

    float hr, hg, hb;
    switch (order) {
      case 0:
        hr = tMax;
        hg = tMid;
        hb = tMin;
        break;
      case 1:
        hr = tMax;
        hb = tMid;
        hg = tMin;
        break;
      case 2:
        hb = tMax;
        hr = tMid;
        hg = tMin;
        break;
      case 3:
        hg = tMax;
        hr = tMid;
        hb = tMin;
        break;
      case 4:
        hg = tMax;
        hb = tMid;
        hr = tMin;
        break;
      default:
        hb = tMax;
        hg = tMid;
        hr = tMin;
        break;
    }

    // 16% luminance-ratio blend keeps shadow saturation organic while preserving 100% hue angle.
    float y = 0.22897456f * cr + 0.69173852f * cg + 0.07928691f * cb;
    if (y > 1e-6f && mx > 1e-6f) {
      float lumGain = Math.min(table.eval(y) / y, 1f / mx);
      float lr = cr * lumGain;
      float lg = cg * lumGain;
      float lb = cb * lumGain;
      out[0] = 0.84f * hr + 0.16f * lr;
      out[1] = 0.84f * hg + 0.16f * lg;
      out[2] = 0.84f * hb + 0.16f * lb;
    } else {
      out[0] = hr;
      out[1] = hg;
      out[2] = hb;
    }
  }

  /**
   * Grades post-tone-curve linear Display P3 in OKLab according to the iPhone 16 Pro Photographic
   * Style, compresses softly into Display P3 (or sRGB when {@code outputDisplayP3 == false}), and
   * encodes with Display P3 / sRGB transfer curve.
   */
  public static void gradeDisplayP3(
      float[] linP3, Style style, int colorStep, boolean outputDisplayP3) {
    gradeDisplayP3(linP3, style, colorStep, outputDisplayP3, null);
  }

  /**
   * Grades one pixel; when {@code tune} is non-null the user's iPhone editor tuning applies after
   * the style's own grading: exposure (linear pre-gain), the tone/curve map on OKLab L, then
   * saturation / vibrance / warmth / tint on OKLab a/b. Lightroom-style semantics — tonal edits
   * preserve hue, colour edits preserve lightness.
   */
  public static void gradeDisplayP3(
      float[] linP3, Style style, int colorStep, boolean outputDisplayP3, AppleTune.Plan tune) {
    float lr, lg, lb;
    if (tune == null) {
      lr = uClamp(linP3[0]);
      lg = uClamp(linP3[1]);
      lb = uClamp(linP3[2]);
    } else {
      // Exposure first, in linear light (true EV); highlights may extend past 1, cbrtUnit copes.
      float g = tune.expoGain;
      lr = Math.max(0f, Math.min(4f, linP3[0] * g));
      lg = Math.max(0f, Math.min(4f, linP3[1] * g));
      lb = Math.max(0f, Math.min(4f, linP3[2] * g));
    }
    float l = cbrtUnit(P3_TO_LMS[0] * lr + P3_TO_LMS[1] * lg + P3_TO_LMS[2] * lb);
    float m = cbrtUnit(P3_TO_LMS[3] * lr + P3_TO_LMS[4] * lg + P3_TO_LMS[5] * lb);
    float s = cbrtUnit(P3_TO_LMS[6] * lr + P3_TO_LMS[7] * lg + P3_TO_LMS[8] * lb);

    float L = 0.21045426f * l + 0.79361779f * m - 0.00407204f * s;
    float A = 1.97799850f * l - 2.42859221f * m + 0.45059371f * s;
    float Bb = 0.02590404f * l + 0.78277177f * m - 0.80867580f * s;
    float C = (float) Math.sqrt(A * A + Bb * Bb);
    float wSkinNow = 0f;

    if (C > 1e-5f) {
      float ua = A / C, ub = Bb / C;

      // Skin hue sector (~42 deg in OKLab, gated to realistic human skin chroma)
      float cSkin = ua * 0.7431f + ub * 0.6691f;
      float cSkin2 = cSkin > 0f ? cSkin * cSkin : 0f;
      float wSkin =
          (cSkin2 * cSkin2 * cSkin2)
              * sstep(0.012f, 0.038f, C)
              * (1f - sstep(0.095f, 0.148f, C));
      wSkinNow = wSkin;

      // Foliage yellow-green sector (~128 deg)
      float cLeaf = ua * (-0.6157f) + ub * 0.7880f;
      float cLeaf2 = cLeaf > 0f ? cLeaf * cLeaf : 0f;
      float wLeaf = (cLeaf2 * cLeaf2) * sstep(0.020f, 0.075f, C);

      // Sky blue-cyan sector (~252 deg)
      float cSky = ua * (-0.3090f) + ub * (-0.9511f);
      float cSky2 = cSky > 0f ? cSky * cSky : 0f;
      float wSky = (cSky2 * cSky2) * sstep(0.020f, 0.075f, C);

      // Warm gold/amber sector (~78 deg)
      float cGold = ua * 0.2079f + ub * 0.9781f;
      float cGold2 = cGold > 0f ? cGold * cGold : 0f;
      float wGold = (cGold2 * cGold2) * sstep(0.020f, 0.075f, C);

      // Deep red sector (~24 deg)
      float cRed = ua * 0.9135f + ub * 0.4067f;
      float cRed2 = cRed > 0f ? cRed * cRed : 0f;
      float wRed = (cRed2 * cRed2 * cRed2) * sstep(0.050f, 0.120f, C);

      float shEnv = 0.60f + 0.40f * sstep(0.04f, 0.18f, L);
      float hlEnv = 1f - 0.45f * sstep(0.84f, 0.99f, L);
      float env = shEnv * hlEnv;

      // Non-skin background vibrance gate: boosts moderate/rich background colours while keeping
      // near-neutrals (C < 0.008) clean and protecting human skin (1 - 0.82 * wSkin).
      float vibGate = sstep(0.008f, 0.030f, C) * (1f - 0.35f * sstep(0.16f, 0.28f, C));
      float bgVib = (1f - 0.82f * wSkin) * vibGate * env;

      // User Color slider (-4..+4), protecting skin from half of positive saturation boosts
      float stepGain = 0.065f * colorStep * env * (colorStep > 0 ? (1f - 0.48f * wSkin) : 1f);
      float cScale = Math.max(0f, 1f + stepGain);

      float rotDeg;
      float midSkin = wSkin * sstep(0.20f, 0.46f, L) * (1f - sstep(0.78f, 0.94f, L));
      switch (style) {
        case XS_MAX_WARM:
          // Iconic A12 Smart HDR iPhone XS Max: golden-honey warmth, velvety warm-peach skin,
          // lush sunlit greens, deep cobalt skies, and vibrant warm-lit backgrounds.
          // Chroma-magnitude ramp matched to the reference Mi-gallery edit (vibrance +24 /
          // saturation +4): near-neutrals untouched, mid chroma +3.5%, rich chroma +5.5%.
          L += 0.012f * midSkin;
          cScale *=
              (1f
                  + 0.22f * bgVib
                  + 0.06f * wLeaf
                  + 0.08f * wSky
                  + 0.05f * wRed
                  + 0.07f * wGold * (1f - 0.65f * wSkin)
                  + 0.035f * sstep(0.028f, 0.055f, C) * (1f - 0.5f * wSkin)
                  + 0.020f * sstep(0.055f, 0.12f, C) * (1f - 0.5f * wSkin)
                  + 0.02f * wSkin);
          L -= (0.026f * wSky + 0.020f * wLeaf + 0.015f * wRed) * L;
          rotDeg = 1.8f * wSkin + 4.5f * wLeaf - 3.2f * wSky - 1.2f * wGold;
          break;
        case NATURAL_ROSE:
          // Portrait undertone: tames indoor yellow cast on skin while keeping backgrounds vibrant
          L += 0.013f * midSkin;
          cScale *=
              (1f
                  + 0.17f * bgVib
                  - 0.04f * wSkin
                  + 0.04f * wLeaf
                  + 0.06f * wSky
                  + 0.03f * wRed);
          L -= (0.020f * wSky + 0.018f * wLeaf) * L;
          rotDeg = -3.6f * wSkin + 6.5f * wLeaf - 3.6f * wSky;
          break;
        case RICH_CONTRAST:
          // Tone -50 / Dramatic: deep blacks, high pigment density, vibrant skies/greens, safe skin
          cScale *=
              (1f
                  + 0.26f * bgVib
                  + 0.06f * wLeaf
                  + 0.09f * wSky
                  + 0.07f * wRed
                  + 0.04f * wGold * (1f - wSkin));
          L -= (0.030f * wSky + 0.026f * wLeaf + 0.020f * wRed) * L;
          rotDeg = 0.4f * wSkin + 6.5f * wLeaf - 3.8f * wSky;
          break;
        case AMBER_COZY:
          // Golden-hour warmth across midtones, lush backgrounds, and protected warm skin
          L += 0.010f * midSkin;
          cScale *=
              (1f
                  + 0.20f * bgVib
                  + 0.05f * wLeaf
                  + 0.06f * wSky
                  + 0.05f * wRed
                  + 0.08f * wGold * (1f - 0.65f * wSkin));
          L -= (0.022f * wSky + 0.020f * wLeaf + 0.014f * wRed) * L;
          rotDeg = 2.0f * wSkin - 1.6f * wGold + 5.5f * wLeaf - 3.4f * wSky;
          break;
        case STANDARD:
        default:
          // Balanced Smart HDR 5 standard: true-warm protected skin + punchy iPhone background colour
          L += 0.008f * midSkin;
          cScale *=
              (1f
                  + 0.20f * bgVib
                  + 0.05f * wLeaf
                  + 0.07f * wSky
                  + 0.04f * wRed
                  + 0.03f * wGold * (1f - wSkin));
          L -= (0.024f * wSky + 0.022f * wLeaf + 0.014f * wRed) * L;
          rotDeg = 0.5f * wSkin + 6.2f * wLeaf - 3.5f * wSky;
          break;
      }

      // Apple skin-chroma ceiling (applied after style scaling): prevents orange/oversaturated faces
      float postSkinC = C * cScale;
      if (wSkin > 0f && postSkinC > 0.074f) {
        float excess = (postSkinC - 0.074f) * 0.36f * wSkin;
        cScale *= Math.max(0.74f, (postSkinC - excess) / postSkinC);
      }

      float rad = rotDeg * 0.0174532925f;
      float r2 = rad * rad;
      float cs = 1f - 0.5f * r2 * (1f - r2 * (1f / 12f));
      float sn = rad * (1f - r2 * (1f / 6f));
      float sa = A * cScale, sb = Bb * cScale;
      A = sa * cs - sb * sn;
      Bb = sa * sn + sb * cs;
    }

    if (style == Style.AMBER_COZY || style == Style.XS_MAX_WARM) {
      float wWarmHi =
          sstep(0.66f, 0.84f, L)
              * (1f - sstep(0.94f, 0.995f, L))
              * (1f - 0.5f * sstep(0.05f, 0.14f, C));
      float kHi = style == Style.XS_MAX_WARM ? 0.88f : 1.0f;
      A += 0.0009f * kHi * wWarmHi;
      Bb += 0.0031f * kHi * wWarmHi;
    } else if (style == Style.NATURAL_ROSE) {
      // Subtle rosy highlight delicacy on low-chroma skin/highlights, zero cast on pure white
      float wRose =
          sstep(0.55f, 0.75f, L)
              * (1f - sstep(0.90f, 0.98f, L))
              * sstep(0.012f, 0.04f, C)
              * (1f - sstep(0.08f, 0.14f, C));
      A += 0.0014f * wRose;
      Bb -= 0.0006f * wRose;
    }

    // iPhone editor tuning: tone/curve on OKLab L, then colour on OKLab a/b.
    if (tune != null) {
      L = tune.toneAt(L);
      if (C > 1e-5f) {
        float g = tune.satK; // saturation: uniform chroma multiplier, skin included
        if (tune.vibK != 0f) g *= tune.vibranceGain(C, wSkinNow); // vibrance: muted first, skin safe
        if (g != 1f) {
          A *= g;
          Bb *= g;
        }
      }
      float whiteGuard = 1f - sstep(0.93f, 1.005f, L); // warmth/tint taper off pure white
      Bb += tune.warmB * whiteGuard;
      A += tune.tintA * whiteGuard;
    }

    L = uClamp(L);

    float outR = 0f, outG = 0f, outB = 0f;
    for (int iter = 0; iter < 4; iter++) {
      float l2 = L + 0.39633778f * A + 0.21580376f * Bb;
      float m2 = L - 0.10556135f * A - 0.06385417f * Bb;
      float s2 = L - 0.08948418f * A - 1.29148555f * Bb;
      l2 = l2 * l2 * l2;
      m2 = m2 * m2 * m2;
      s2 = s2 * s2 * s2;
      float pr = LMS_TO_P3[0] * l2 + LMS_TO_P3[1] * m2 + LMS_TO_P3[2] * s2;
      float pg = LMS_TO_P3[3] * l2 + LMS_TO_P3[4] * m2 + LMS_TO_P3[5] * s2;
      float pb = LMS_TO_P3[6] * l2 + LMS_TO_P3[7] * m2 + LMS_TO_P3[8] * s2;
      if (outputDisplayP3) {
        outR = pr;
        outG = pg;
        outB = pb;
      } else {
        outR = P3_TO_REC709[0] * pr + P3_TO_REC709[1] * pg + P3_TO_REC709[2] * pb;
        outG = P3_TO_REC709[3] * pr + P3_TO_REC709[4] * pg + P3_TO_REC709[5] * pb;
        outB = P3_TO_REC709[6] * pr + P3_TO_REC709[7] * pg + P3_TO_REC709[8] * pb;
      }
      float mx = Math.max(outR, Math.max(outG, outB));
      float mn = Math.min(outR, Math.min(outG, outB));
      if (mx <= 0.992f && mn >= -0.002f) break;
      A *= 0.89f;
      Bb *= 0.89f;
    }

    linP3[0] = srgbEncodeFast(outR);
    linP3[1] = srgbEncodeFast(outG);
    linP3[2] = srgbEncodeFast(outB);
  }

  /**
   * Computes a single-channel ISO 21496-1 Ultra HDR gainmap byte ({@code 0..255}) from a block's
   * pre-LTM scene-linear peak and its post-tonemap SDR linear peak. Midtones and skin ({@code
   * scenePeak <= 0.45}) evaluate strictly to {@code 0} ({@code 1.0x}), while true sunlit clouds,
   * windows, and specular highlights smoothly expand up to {@link #GAINMAP_RATIO_MAX}x.
   */
  public static byte computeGainmapByte(float scenePeakLinear, float sdrPeakLinear) {
    if (scenePeakLinear <= 0.45f) return 0;
    float ramp = sstep(0.45f, 0.85f, scenePeakLinear);
    float rawRatio = (scenePeakLinear + GAINMAP_EPSILON) / (Math.max(0f, sdrPeakLinear) + GAINMAP_EPSILON);
    if (rawRatio <= 1f) return 0;
    float ratio = 1f + ramp * (Math.min(GAINMAP_RATIO_MAX, rawRatio) - 1f);
    if (ratio <= 1.004f) return 0;
    float g = (float) (Math.log(ratio) / LOG_RATIO_MAX);
    int b = Math.round(uClamp(g) * 255f);
    return (byte) (b < 0 ? 0 : Math.min(255, b));
  }

  // ── Official Apple Log Profile (Sept 2023 White Paper, Rec.2020) ──

  private static final double ALOG_R0 = -0.05641088;
  private static final double ALOG_RT = 0.01;
  private static final double ALOG_C = 47.28711236;
  private static final double ALOG_BETA = 0.00964052;
  private static final double ALOG_GAMMA = 0.08550479;
  private static final double ALOG_DELTA = 0.69336945;
  private static final double ALOG_PT = ALOG_C * (ALOG_RT - ALOG_R0) * (ALOG_RT - ALOG_R0);
  private static final double INV_LOG2 = 1.0 / Math.log(2.0);

  /** Scene-linear reflection {@code R} (0.18 = 18% grey) -> Apple Log code {@code P in [0, 1]}. */
  public static double appleLogEncode(double r) {
    if (r < ALOG_R0) return 0.0;
    if (r < ALOG_RT) {
      double d = r - ALOG_R0;
      return ALOG_C * d * d;
    }
    return ALOG_GAMMA * (Math.log(r + ALOG_BETA) * INV_LOG2) + ALOG_DELTA;
  }

  /** Apple Log code {@code P} -> scene-linear reflection {@code R}. */
  public static double appleLogDecode(double p) {
    if (p < 0.0) return ALOG_R0;
    if (p < ALOG_PT) return Math.sqrt(p / ALOG_C) + ALOG_R0;
    return Math.pow(2.0, (p - ALOG_DELTA) / ALOG_GAMMA) - ALOG_BETA;
  }

  private static float cbrtUnit(float x) {
    if (x <= 0f) return 0f;
    if (x >= 1f) return x == 1f ? 1f : (float) Math.cbrt(x);
    float u = (float) Math.sqrt(Math.sqrt(x)) * 2048f;
    int i = (int) u;
    if (i >= 2048) i = 2047;
    return CBRT_Q[i] + (u - i) * (CBRT_Q[i + 1] - CBRT_Q[i]);
  }

  private static float srgbEncodeFast(float lin) {
    if (lin <= 0f) return 0f;
    if (lin >= 1f) return 1f;
    float p = (float) Math.sqrt(lin) * 2048f;
    int i = (int) p;
    if (i >= 2048) i = 2047;
    float f = p - i;
    if (f < 0f) f = 0f;
    else if (f > 1f) f = 1f;
    return SRGB_ENC[i] + f * (SRGB_ENC[i + 1] - SRGB_ENC[i]);
  }

  private static float uClamp(float t) {
    return t < 0f ? 0f : t > 1f ? 1f : t;
  }

  private static float sstep(float a, float b, float x) {
    float t = (x - a) / (b - a);
    t = t <= 0f ? 0f : t >= 1f ? 1f : t;
    return t * t * (3f - 2f * t);
  }
}
