package dev.grain.photo.develop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Grain's RAW developer. Phone Bayer RAW in, 8-bit display RGB out:
 *
 * <ol>
 *   <li>black level, white level, lens-shading gains, as-shot white balance (+ recipe WB shift)
 *   <li>Malvar-He-Cutler demosaic, soft clip of green-saturated highlights toward neutral
 *   <li>DNG colour calibration to linear Rec.2020 / F-Gamut (D65), metering-based exposure
 *   <li>F-Log2 encoding plus the still-photo tone correction, DR and Highlight/Shadow offsets
 *   <li>noise reduction in the log domain, driven by a noise curve measured on the frame: a
 *       quarter-resolution pass removes blotchy low-frequency luma and chroma noise (guided filters),
 *       a full-resolution self-guided filter tames pixel-level luma noise, then sharpening
 *   <li>Fujifilm's official F-Log2 3-D LUT for the recipe's film simulation (user-imported), or
 *       Sony's official s709 LUT, sampled through an exact per-pixel conversion to S-Log3 /
 *       S-Gamut3.Cine (see {@link CubeLut.Input}, {@link SLog3})
 *   <li>Color Chrome FX Blue (fitted to Fujifilm camera JPEGs), then Color (chroma) and Color
 *       Chrome Effect (approximations) in a display-code opponent space (OKLab for First version),
 *       film grain scaled down when sensor noise already supplies texture
 * </ol>
 *
 * Work is split into 64-row strips processed in parallel, each with its own buffers.
 */
public final class RawDeveloper {
  public static final String VERSION = "grain-raw-5";

  /**
   * Where Fujifilm places the centre-weighted log-average scene luminance, in F-Log2 scene-linear
   * units (median of 11 public RAF/JPEG pairs, spread 0.40 EV; fit_stills_model.py).
   */
  public static final double METERING_KEY = 0.148;

  /**
   * Scene key for Sony's s709 (v0.3.5): the metered average sits at Sony's own exposure reference,
   * 18 % grey (S-Log3 code 420 of 1023), instead of Fujifilm's fitted {@link #METERING_KEY}. s709
   * is a cinema look and no Sony stills data exists for it; this is Grain's choice, documented in
   * docs/RAW_FUJI_RECIPES.md. Face exposure keeps its half stop above the key.
   */
  public static final double SONY_KEY = 0.18;

  /**
   * Face exposure, Grain's version of Fujifilm's Intelligent Face Detection, which "sets focus and
   * exposure for human faces" (the face closest to the centre, see {@link Faces}). The {@link
   * #FACE_PERCENTILE} luminance of the face's inner box (skin rather than hair, beard, eyes or the
   * background) is placed at FACE_KEY, half a stop above the scene key: Grain's choice (Fujifilm
   * publishes no face target), which renders medium skin about as bright as phone cameras do and
   * keeps brown skin close to natural. The user's EV is applied on top. The correction relative to
   * the scene metering is limited to FACE_MAX_BRIGHTEN_EV / FACE_MAX_DARKEN_EV.
   */
  public static final double FACE_KEY = METERING_KEY * 1.4142135623730951;

  public static final double FACE_PERCENTILE = 0.65;
  public static final double FACE_MAX_BRIGHTEN_EV = 3, FACE_MAX_DARKEN_EV = 1.5;

  /** Approximation: no public measurement of Fujifilm's WB-shift step exists. */
  public static final double WB_SHIFT_LOG2_PER_STEP = 0.04;

  /**
   * D-Range Priority (approximation; Fujifilm publishes no curve). Fuji X Weekly's comparisons:
   * DR-P Weak renders like DR400 with Highlight -2 and Shadow -2 (RAW exposure like DR200); DR-P
   * Strong applies the same flat curve one DR step higher (RAW exposure like DR400). So both use the
   * DR400 tone with H/S -2, and Strong compresses one more stop of highlights into the white.
   */
  public static final double DRP_HIGHLIGHT = -2, DRP_SHADOW = -2, DRP_STRONG_EXTRA_EV = 1;

  /**
   * Acros filter transmissions (R, G, B) applied to the Acros LUT's own channel weights: +Ye cuts
   * blue, +R cuts green and blue, +G cuts red and blue. Approximations of yellow / red / green
   * contrast filters; Fujifilm publishes no numbers.
   */
  static final double[][] MONO_FILTER_TRANSMISSION = {
    {1, 1, 1}, {1, 0.9, 0.1}, {1, 0.25, 0.02}, {0.4, 1, 0.4}
  };

  /** Approximations of effects Fujifilm does not publish (see docs/RAW_FUJI_RECIPES.md). */
  static final float CCE_C0 = 0.18f, CCE_C1 = 0.50f, CCE_STRONG = 0.045f;

  /**
   * Color Chrome FX Blue (v0.3.3, both developments): every channel of a blue display-code colour
   * drops by depth * smoothstep(C; FXB_C0, FXB_C1) * hb^4 * Y' (C and hb: chroma and sky-blue
   * cosine in the (B'-Y', R'-Y') plane), so blues get darker and relatively richer at the same hue,
   * like a polarizing filter. Fitted to Fujifilm X100V camera JPEGs of one clear sky at FX Blue
   * Off / Weak / Strong (Fuji X Weekly, 2020-06-30): 10 sky bands, rms error 0.7 code values, and
   * Weak measured at half of Strong.
   */
  static final float FXB_C0 = 0.08f, FXB_C1 = 0.45f, FXB_STRONG = 0.277f;

  static final float GRAIN_WEAK = 0.012f, GRAIN_STRONG = 0.020f;
  /** Display-code (B'-Y', R'-Y') direction of OKLab hue ~264 degrees (sky/deep blue). */
  static final float BLUE_U = 0.949f, BLUE_V = -0.314f;
  /**
   * Development.FIRST_VERSION (Grain v0.2.0), kept exactly: Color Chrome Effect chroma ramp in
   * OKLab and the largest colour-noise box radius. Its OKLab FX Blue (at most 5 %, gated to very
   * saturated blues) was replaced in v0.3.3 by the fitted FX Blue shared with Clean.
   */
  static final float FV_CCE_C0 = 0.07f, FV_CCE_C1 = 0.19f;

  static final int PROFILE_NONE = 0,
      PROFILE_KODAK_800_V3 = 1,
      PROFILE_LEICA_X = 2,
      PROFILE_CINESTILL_800T = 3;
  private static final float[] CBRT_Q = new float[2049];
  private static final float[] SRGB_DEC = new float[4097], SRGB_ENC = new float[2049];

  static {
    for (int i = 0; i <= 2048; i++) {
      double u = i / 2048.0;
      CBRT_Q[i] = (float) Math.pow(u, 4.0 / 3.0);
      double lin = u * u;
      SRGB_ENC[i] = (float) (lin <= 0.0031308 ? 12.92 * lin : 1.055 * Math.pow(lin, 1 / 2.4) - 0.055);
    }
    for (int i = 0; i <= 4096; i++) {
      double c = i / 4096.0;
      SRGB_DEC[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
    }
  }

  static int sensorProfileOf(FujiRecipe r) {
    if (r == null) return PROFILE_NONE;
    if ("kodak-800-v3".equals(r.key)) return PROFILE_KODAK_800_V3;
    if ("leica-x".equals(r.key)) return PROFILE_LEICA_X;
    if ("cinestill-800t".equals(r.key)) return PROFILE_CINESTILL_800T;
    return PROFILE_NONE;
  }

  /**
   * Maps Fujifilm's menu Kelvin dial to the RAF-measured DNG Planckian CCT curve (Incandescent
   * 3000K -> 2856K, Daylight 5500K -> 4850K, Shade 7000K -> 6000K), preventing 6600K recipes from
   * overshooting past Shade into heavy yellow/orange on smartphone DNG matrices.
   */
  static double fujiKelvinToDngCct(double k) {
    if (k <= 3000.0) return k * (2856.0 / 3000.0);
    if (k <= 5500.0) return 2856.0 + (4850.0 - 2856.0) * (k - 3000.0) / (5500.0 - 3000.0);
    if (k <= 7000.0) return 4850.0 + (6000.0 - 4850.0) * (k - 5500.0) / (7000.0 - 5500.0);
    return 6000.0 + 0.80 * (k - 7000.0);
  }

  private static float cbrtUnit(float x) {
    if (x <= 0f) return 0f;
    if (x >= 1f) return x == 1f ? 1f : (float) Math.cbrt(x);
    float u = (float) Math.sqrt(Math.sqrt(x)) * 2048f;
    int i = (int) u;
    if (i >= 2048) i = 2047;
    return CBRT_Q[i] + (u - i) * (CBRT_Q[i + 1] - CBRT_Q[i]);
  }

  private static float srgbDecodeFast(float c) {
    if (c <= 0f) return 0f;
    if (c >= 1f) return 1f;
    float p = c * 4096f;
    int i = (int) p;
    if (i >= 4096) i = 4095;
    return SRGB_DEC[i] + (p - i) * (SRGB_DEC[i + 1] - SRGB_DEC[i]);
  }

  private static float srgbEncodeFast(float lin) {
    if (lin <= 0f) return 0f;
    if (lin >= 1f) return 1f;
    float p = (float) Math.sqrt(lin) * 2048f;
    int i = (int) p;
    if (i >= 2048) i = 2047;
    return SRGB_ENC[i] + (uClamp(p - i)) * (SRGB_ENC[i + 1] - SRGB_ENC[i]);
  }

  private static float uClamp(float t) {
    return t < 0f ? 0f : t > 1f ? 1f : t;
  }

  private static float sstep(float a, float b, float x) {
    float t = (x - a) / (b - a);
    t = t <= 0f ? 0f : t >= 1f ? 1f : t;
    return t * t * (3f - 2f * t);
  }

  /**
   * Perceptual OKLab phone-sensor-to-emulsion/Summilux colour, Color Chrome pigment density, skin
   * protection, foliage hue calibration and hue-preserving soft gamut compression.
   */
  static void applySensorCalibration(float[] rgb, int profile, int colorStep, int cceLevel) {
    float vr = rgb[0], vg = rgb[1], vb = rgb[2];
    if (profile == PROFILE_LEICA_X && vr == vg && vg == vb) return;
    float lr = srgbDecodeFast(vr), lg = srgbDecodeFast(vg), lb = srgbDecodeFast(vb);
    float l = cbrtUnit(0.41222146f * lr + 0.53633255f * lg + 0.05144599f * lb);
    float mm = cbrtUnit(0.21190350f * lr + 0.68069953f * lg + 0.10739697f * lb);
    float s = cbrtUnit(0.08830246f * lr + 0.28171885f * lg + 0.62997869f * lb);
    float L = 0.21045426f * l + 0.79361779f * mm - 0.00407204f * s;
    float A = 1.97799850f * l - 2.42859221f * mm + 0.45059371f * s;
    float Bb = 0.02590404f * l + 0.78277177f * mm - 0.80867580f * s;
    float C = (float) Math.sqrt(A * A + Bb * Bb);
    if (C > 1e-5f) {
      float ua = A / C, ub = Bb / C;
      float cSkin = ua * 0.6428f + ub * 0.7660f;
      float cSkin2 = cSkin > 0f ? cSkin * cSkin : 0f;
      float wSkin = (cSkin2 * cSkin2 * cSkin2) * sstep(0.015f, 0.045f, C) * (1f - sstep(0.095f, 0.145f, C));

      float cRed = ua * 0.8801f + ub * 0.4748f;
      float cRed2 = cRed > 0f ? cRed * cRed : 0f;
      float wRed = (cRed2 * cRed2 * cRed2) * sstep(0.08f, 0.15f, C);

      float cGold = ua * 0.0286f + ub * 0.9996f;
      float cGold2 = cGold > 0f ? cGold * cGold : 0f;
      float wGold = (cGold2 * cGold2) * sstep(0.03f, 0.09f, C);

      float cLime = ua * (-0.6419f) + ub * 0.7668f;
      float cLime2 = cLime > 0f ? cLime * cLime : 0f;
      float wLime = (cLime2 * cLime2) * sstep(0.03f, 0.09f, C);

      float cBlue = ua * (-0.1874f) + ub * (-0.9823f);
      float cBlue2 = cBlue > 0f ? cBlue * cBlue : 0f;
      float wBlue = (cBlue2 * cBlue2) * sstep(0.04f, 0.11f, C);

      float shGuard =
          (profile == PROFILE_CINESTILL_800T ? 0.60f : 0.55f)
              + (profile == PROFILE_CINESTILL_800T ? 0.40f : 0.45f) * sstep(0.04f, 0.16f, L);
      float hlGuard = 1f - (profile == PROFILE_CINESTILL_800T ? 0.45f : 0.55f) * sstep(0.80f, 0.99f, L);
      float env = shGuard * hlGuard;

      float gainPerStep = profile == PROFILE_CINESTILL_800T ? 0.092f : 0.065f;
      float cScale = 1f + (gainPerStep * colorStep) * env;
      if (cScale < 0f) cScale = 0f;
      float cce =
          cceLevel == 2
              ? (profile == PROFILE_CINESTILL_800T ? 0.135f : 0.130f)
              : cceLevel == 1 ? 0.065f : 0f;
      if (cce > 0f) {
        float wCce = sstep(0.045f, 0.185f, C);
        L -= cce * wCce * (1f - 0.55f * wSkin) * L;
      }

      float rotDeg;
      if (profile == PROFILE_KODAK_800_V3) {
        L += 0.010f * wSkin * sstep(0.25f, 0.50f, L) * (1f - sstep(0.75f, 0.92f, L));
        cScale *= (1f + 0.06f * wGold - 0.07f * wLime);
        rotDeg = 2.5f * wSkin - 2.5f * wGold + 7.5f * wLime;
      } else if (profile == PROFILE_CINESTILL_800T) {
        cScale *= (1f + 0.10f * wRed + 0.08f * wGold + 0.10f * wBlue - 0.08f * wSkin);
        L -= (0.020f * wBlue + 0.018f * wLime) * L;
        rotDeg = -8.0f * wBlue + 11.0f * wLime - 2.0f * wGold;
      } else {
        cScale *= (1f + 0.10f * wRed - 0.11f * wSkin + 0.09f * wBlue - 0.04f * wLime);
        L -= (0.028f * wRed + 0.022f * wLime + 0.020f * wBlue) * L;
        rotDeg = -2.5f * wRed + 9.0f * wLime;
      }
      float rad = rotDeg * 0.0174532925f;
      float r2 = rad * rad;
      float cs = 1f - 0.5f * r2 * (1f - r2 * (1f / 12f));
      float sn = rad * (1f - r2 * (1f / 6f));
      float sa = A * cScale, sb = Bb * cScale;
      A = sa * cs - sb * sn;
      Bb = sa * sn + sb * cs;
    }
    if (profile == PROFILE_KODAK_800_V3) {
      float wPaper =
          sstep(0.78f, 0.90f, L) * (1f - sstep(0.94f, 0.995f, L)) * (1f - sstep(0.03f, 0.10f, C));
      Bb += 0.0025f * wPaper;
      A += 0.0006f * wPaper;
    } else if (profile == PROFILE_CINESTILL_800T) {
      float wShSplit = sstep(0.05f, 0.18f, L) * (1f - sstep(0.32f, 0.50f, L));
      A -= 0.0085f * wShSplit;
      Bb -= 0.0115f * wShSplit;
      float wHiSplit =
          sstep(0.68f, 0.84f, L) * (1f - sstep(0.94f, 0.995f, L)) * (1f - 0.5f * sstep(0.06f, 0.15f, C));
      A += 0.0035f * wHiSplit;
      Bb += 0.0105f * wHiSplit;
    }
    if (L < 0f) L = 0f;
    else if (L > 1f) L = 1f;

    float outR = 0f, outG = 0f, outB = 0f;
    for (int iter = 0; iter < 4; iter++) {
      float l2 = L + 0.39633778f * A + 0.21580376f * Bb;
      float m2 = L - 0.10556135f * A - 0.06385417f * Bb;
      float s2 = L - 0.08948418f * A - 1.29148555f * Bb;
      l2 = l2 * l2 * l2;
      m2 = m2 * m2 * m2;
      s2 = s2 * s2 * s2;
      outR = 4.07674166f * l2 - 3.30771159f * m2 + 0.23096993f * s2;
      outG = -1.26843800f * l2 + 2.60975740f * m2 - 0.34131940f * s2;
      outB = -0.00419609f * l2 - 0.70341861f * m2 + 1.70761470f * s2;
      float mx = Math.max(outR, Math.max(outG, outB));
      float mn = Math.min(outR, Math.min(outG, outB));
      if (mx <= 0.985f && mn >= -0.002f) break;
      A *= 0.90f;
      Bb *= 0.90f;
    }
    rgb[0] = srgbEncodeFast(outR);
    rgb[1] = srgbEncodeFast(outG);
    rgb[2] = srgbEncodeFast(outB);
  }

  static final int FV_MAX_RADIUS = 4;
  static final int STRIP = 64, HALO = 5, GF_R = 2, NR_TABLE = 256;
  /** Quarter-resolution guided-filter radii (in 4 x 4 blocks) for luma and for chroma. */
  static final int QR = 2, QRC = 3;
  /**
   * Variance of a 4 x 4-block luma code relative to (pixel model / 16), measured on the Redmi DNGs;
   * LOW_NR_K is the low-frequency k; CHROMA_EPS scales the chroma filter's epsilon.
   */
  static final double Q_NOISE = 1.9, LOW_NR_K = 3.0, CHROMA_EPS = 4.0;
  /**
   * Chroma (r - Y, b - Y) noise variance of a 4 x 4 block relative to the luma model / 16, used when
   * a frame has too few flat areas to measure it (Redmi DNGs: about 6 and 12). WIENER_T is the
   * threshold, in noise variances, below which chroma detail counts as noise.
   */
  static final double CHROMA_U_DEFAULT = 6, CHROMA_V_DEFAULT = 12, WIENER_T = 1.5;

  /**
   * Luma noise reduction. Epsilon of the guided filter is (k * sigma)^2, sigma being the predicted
   * luma-code noise; k = LUMA_NR_K * 2^(NR/8) for recipe NR -4..+4. NOISE_MIX converts the green
   * RAW noise profile to luma-code noise after demosaic and the colour matrix (measured on the
   * Redmi Note 13 Pro+ DNGs). The ISO fallback is that phone's profile scaled by ISO.
   */
  static final double LUMA_NR_K = 2.2, NOISE_MIX = 1.0;
  static final double NOISE_S_PER_ISO = 5.3e-8, NOISE_O_PER_ISO2 = 6.9e-15;
  /**
   * The Redmi's HAL noise profile is about 5.3x below the noise measured in its RAW frames (ISO
   * 2363 and 2742 DNGs, green pixels, all brightness levels). The frame's own measurement may move
   * the curve within [0.5, 1.6] x (shot) and [0.5, 12] x (read) of this baseline; the read term
   * is the least reliable part of the HAL profile (measured 7-9x above it).
   */
  static final double HAL_NOISE_SCALE = 5.3;
  /** Sharpening of the denoised base: amount = SHARPEN_BASE + SHARPEN_STEP * recipe Sharpness. */
  static final double SHARPEN_BASE = 0.45, SHARPEN_STEP = 0.1;
  /** Film grain never drops below this share of the recipe amplitude; GF_RESIDUAL ~ noise left in q. */
  static final double GRAIN_FLOOR = 0.5, GF_RESIDUAL = 0.3;
  static final int HIST_MIN = -24, HIST_PER_EV = 8, HIST_BINS = 36 * 8;

  /**
   * Grain extras (FujiRecipe.halation / bloom, 0..10), modelled as light transport in scene-linear
   * light before the film simulation, in the normalised form E' = (1 - a) E + a (K * E): uniform
   * areas are unchanged and light is only redistributed around structure. Halation is a second
   * exposure of mainly the red record (some green, no blue); bloom is neutral and wider. Kernel
   * sizes are Gaussian sigmas as fractions of the frame's long side, so the look does not depend
   * on resolution. Clipped RAW pixels lost their true brightness; for the glow only, blocks whose
   * green pixels clipped (light sources) send CLIP_BOOST times their clipped value into the glow
   * around them (an assumption, not a measurement); their own inside is left as recorded.
   */
  static final double HALATION_SIGMA = 0.006, BLOOM_SIGMA = 0.015;

  static final double HALATION_PER_STEP = 0.015, BLOOM_PER_STEP = 0.015;
  static final float HALATION_GREEN = 0.3f, CLIP_BOOST = 8f, CLIP_LEVEL = 0.97f;
  /**
   * Clarity (FujiRecipe.clarity, -5..+5): mid-band local contrast of the display-code luma, the
   * band between the 4 x 4 blocks and a Gaussian of CLARITY_SIGMA x long side, weighted to the
   * mid-tones. Fujifilm does not publish its Clarity; this is an approximation (minus Clarity is
   * often likened to a diffusion filter).
   */
  static final double CLARITY_PER_STEP = 0.12, CLARITY_SIGMA = 0.008;

  private RawDeveloper() {}

  public interface Sink {
    /** Rows y .. y + count - 1, packed ARGB, row stride = width. Called from worker threads. */
    void rows(int y, int count, int[] argb, int width);
  }

  public static final class Options {
    public double exposureEv;
    public long grainSeed = 1;
    public boolean grain = true;
    /** Meter the frame's face (RawFrame.faces) like Fujifilm's face detection; see FACE_KEY. */
    public boolean faceExposure = true;
    /** Test hooks: false = official LUT tone only; > 0 = fixed development gain instead of metering. */
    public boolean stillsTone = true;
    public double fixedGain;
    /** Scale factors for luma noise reduction and sharpening (1 = recipe, 0 = off). */
    public double lumaNr = 1, sharpen = 1;
    /** Desktop harness only: W x H planes receiving pre-NR luma codes and the modelled noise variance. */
    public float[] debugLuma, debugNoise, debugQuarter;
    public int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    /**
     * Maximum pixels processed in a single quarter-resolution pass. Frames larger than this (such
     * as 48 MP and 112 MP super-resolution merges) are developed in horizontal bands with exact
     * filter-support overlap so peak Java heap stays well within mobile limits without visible or
     * numerical seams.
     */
    public long maxBandPixels = 32_000_000L;
  }

  public static Map<String, Object> develop(
      RawFrame f, FujiRecipe recipe, CubeLut lut, Options o, Sink sink) throws Exception {
    long t0 = System.nanoTime();
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("engine", VERSION);
    final Job job = new Job(f, recipe, lut, o, stats);
    long t1 = System.nanoTime();
    int threads = Math.max(1, o.threads);
    final int stripThreads = f.width >= 8000 ? Math.min(threads, 4) : threads;
    final int stripH = f.width >= 8000 ? 16 : STRIP;
    boolean canBand =
        o.debugLuma == null
            && o.debugNoise == null
            && o.debugQuarter == null
            && o.maxBandPixels > 0
            && (long) f.width * f.height > o.maxBandPixels;
    long[] ns = new long[4];
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      long totalQuarterNs = 0, totalQuarterDenoiseMs = 0;
      if (!canBand) {
        long tq = System.nanoTime();
        job.prepareBand(0, f.height);
        job.quarter(pool, threads);
        totalQuarterNs = System.nanoTime() - tq;
        totalQuarterDenoiseMs = job.quarterDenoiseMs;
        AtomicInteger next = new AtomicInteger();
        int strips = (f.height + stripH - 1) / stripH;
        List<Worker> bandWorkers = new ArrayList<>(stripThreads);
        for (int t = 0; t < stripThreads; t++) bandWorkers.add(new Worker(job, stripH));
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < stripThreads; t++) {
          final Worker w = bandWorkers.get(t);
          tasks.add(
              () -> {
                for (int i = next.getAndIncrement(); i < strips; i = next.getAndIncrement())
                  w.strip(i * stripH, Math.min(f.height, i * stripH + stripH), sink);
                return null;
              });
        }
        for (Future<Void> fu : pool.invokeAll(tasks)) fu.get();
        for (Worker w : bandWorkers) {
          ns[0] += w.nsCfa;
          ns[1] += w.nsDemosaic;
          ns[2] += w.nsNoise;
          ns[3] += w.nsFinish;
        }
        bandWorkers.clear();
        job.releaseBand();
      } else {
        if (!job.firstVersion) job.lockGlobalChromaNoise();
        int glowRad =
            job.glow
                ? 3 * Math.max(job.haloA[0] > 0 || job.haloA[1] > 0 ? job.haloR : 0, job.bloomA > 0 ? job.bloomR : 0)
                : 0;
        int postRad = Math.max(2 * QRC + QR, job.clarityK != 0 ? 3 * job.clarityR : 0);
        int padRows = Math.max(64, (glowRad + 2 * QR + postRad + 4) * 4); // exact cascaded support
        long maxRows = Math.max(128L, o.maxBandPixels / Math.max(1, f.width));
        int bandOutRows =
            (int) Math.max(128L, ((maxRows - 2L * padRows) / stripH) * stripH);
        int bandCount = 0;
        for (int outY0 = 0; outY0 < f.height; outY0 += bandOutRows) {
          int outY1 = Math.min(f.height, outY0 + bandOutRows);
          int srcY0 = Math.max(0, (outY0 - padRows) & ~3);
          int srcY1 = outY1 >= f.height ? f.height : Math.min(f.height, (outY1 + padRows + 3) & ~3);
          long tq = System.nanoTime();
          job.prepareBand(srcY0, srcY1 - srcY0);
          job.quarter(pool, threads);
          totalQuarterNs += System.nanoTime() - tq;
          totalQuarterDenoiseMs += job.quarterDenoiseMs;
          // Allocate strip workers only AFTER quarterDenoise has freed its temporary planes, and
          // release them before the next band's quarter pass begins.
          final int localOut0 = outY0 - srcY0, localOut1 = outY1 - srcY0;
          final int strips = (localOut1 - localOut0 + stripH - 1) / stripH;
          AtomicInteger next = new AtomicInteger();
          List<Worker> bandWorkers = new ArrayList<>(stripThreads);
          for (int t = 0; t < stripThreads; t++) bandWorkers.add(new Worker(job, stripH));
          List<Callable<Void>> tasks = new ArrayList<>();
          for (int t = 0; t < stripThreads; t++) {
            final Worker w = bandWorkers.get(t);
            tasks.add(
                () -> {
                  for (int i = next.getAndIncrement(); i < strips; i = next.getAndIncrement()) {
                    int s0 = localOut0 + i * stripH;
                    w.strip(s0, Math.min(localOut1, s0 + stripH), sink);
                  }
                  return null;
                });
          }
          for (Future<Void> fu : pool.invokeAll(tasks)) fu.get();
          for (Worker w : bandWorkers) {
            ns[0] += w.nsCfa;
            ns[1] += w.nsDemosaic;
            ns[2] += w.nsNoise;
            ns[3] += w.nsFinish;
          }
          bandWorkers.clear();
          job.releaseBand();
          bandCount++;
        }
        stats.put("bands", bandCount);
      }
      stats.put("quarterMs", Math.round(totalQuarterNs / 1e6));
      stats.put("quarterDenoiseMs", totalQuarterDenoiseMs);
      if (!job.firstVersion)
        stats.put(
            "chromaNoise",
            new double[] {
              round(job.chromaNoiseU, 2), round(job.chromaNoiseV, 2), job.chromaNoiseBlocks
            });
    } finally {
      pool.shutdownNow();
    }
    long t2 = System.nanoTime();
    stats.put("setupMs", Math.round((t1 - t0) / 1e6));
    stats.put("developMs", Math.round((t2 - t1) / 1e6));
    stats.put("threads", threads);
    stats.put("stageCpuMs", new long[] {ns[0] / 1000000, ns[1] / 1000000, ns[2] / 1000000, ns[3] / 1000000});
    stats.put("stages", "cfa, demosaic+tone, noise+sharpen, lut+colour+grain");
    return stats;
  }

  /** Everything shared by all strips: calibration, tables, textures. */
  static final class Job {
    final RawFrame f;
    final CubeLut lut;
    final int W, H;
    final float[] black = new float[4], scale = new float[4], gainK = new float[4];
    final int[] colorK = new int[4], shadeK = new int[4];
    final float clipFactor;
    final float[] m = new float[9];
    final ToneTable tone;
    final boolean shading;
    final int[] sc0;
    final float[] sfx;
    final boolean post;
    final int qW;
    int bandY0, bandH, qH;
    final int[] qx0;
    final float[] qfx;
    float[] dY, pU, pV, kq;
    double[] lockedChromaNoise;
    /** Quarter-resolution codes and shading; released once the low-frequency pass is done. */
    float[] qY, qU, qV, qS;
    final float lowKeep, cKeep, epsLow, epsChroma;
    final float chroma, cceDepth, blueDepth, grainAmp;
    final int sensorProfile, recipeColor, recipeCce;
    /**
     * Acros filter (+Ye/+R/+G): after the LUT's own black-and-white conversion, each pixel's
     * equivalent grey is scaled by the filter's linear brightness ratio (filtered mix / the LUT's
     * own mix) and re-rendered on the LUT's grey curve. Neutrals keep the LUT's exact tone.
     */
    final boolean monoFilter;
    final float[] monoMix = new float[3], monoW = new float[3];
    float[] greyCurve, greyInverse, codeLinear;
    ToneTable linearCode;
    final float[] nrA, nrB;
    final float nrKeep, sharpen, epsFine;
    /**
     * Development.FIRST_VERSION: colour noise box radius (v0.2.0 rule), and whether the luma stage
     * runs at all (High ISO NR above -4, Sharpness other than -2, or Clarity); sRGB tables for the
     * OKLab colour effects.
     */
    final boolean firstVersion;

    final int nrRadius;
    boolean fvNoise;
    final float[] srgbDecode, srgbEncode;
    final float[] debugLuma, debugNoise, debugQuarter;
    final float[] grainA, grainB;
    final int grainOx, grainOy;
    final int[] mod997;
    /** Grain extras: per-channel keep factor and glow planes (quarter resolution) when enabled. */
    final boolean glow;
    final float[] glowKeep = new float[3], haloA = new float[3];
    final float bloomA, clarityK;
    final int[] clipRaw = new int[4];
    final int haloR, bloomR, clarityR;
    float[] qLin, qClip, band;
    float[][] glowQ;

    Job(RawFrame f, FujiRecipe r, CubeLut lut, Options o, Map<String, Object> stats) {
      this.f = f;
      this.lut = lut;
      // Sony's s709 film needs Sony's S-Log3 LUT; Fujifilm films need an F-Log2 table
      boolean sony = r.sony();
      if (sony != (lut.input != CubeLut.Input.FLOG2_FGAMUT))
        throw new IllegalArgumentException(
            r.name + " needs " + (sony ? "Sony's S-Log3 LUT" : "an F-Log2 LUT") + ", not " + lut.input.label);
      W = f.width;
      H = f.height;
      for (int k = 0; k < 4; k++) {
        int x = k & 1, y = k >> 1;
        black[k] = f.black[k];
        scale[k] = 1f / Math.max(1f, f.white - f.black[k]);
        colorK[k] = f.color(x, y);
        shadeK[k] = f.shadingChannel(x, y);
      }
      boolean fvInit = r.development == FujiRecipe.Development.FIRST_VERSION;
      sensorProfile = fvInit ? PROFILE_NONE : sensorProfileOf(r);
      recipeColor = r.color;
      recipeCce = r.colorChrome;
      // --- white balance: the phone's as-shot (auto) white, or a fixed Fujifilm mode's white point
      double[] neutral = f.neutral, whiteXy = null;
      FujiRecipe.WhiteBalance wbMode = r.wbMode;
      boolean cineDefaultWb =
          sensorProfile == PROFILE_CINESTILL_800T
              && wbMode == FujiRecipe.WhiteBalance.FLUORESCENT_3
              && r.wbShiftRed == -6
              && r.wbShiftBlue == -4;
      if (wbMode == FujiRecipe.WhiteBalance.AUTO) {
        stats.put("whiteBalance", "Auto (the phone's as-shot white)");
      } else {
        double targetCct =
            cineDefaultWb
                ? 3450.0
                : wbMode == FujiRecipe.WhiteBalance.KELVIN
                    ? (sensorProfile != PROFILE_NONE ? fujiKelvinToDngCct(r.kelvin) : r.kelvin)
                    : wbMode.cct;
        double targetDuv = cineDefaultWb ? 0.002 : wbMode == FujiRecipe.WhiteBalance.KELVIN ? 0 : wbMode.duv;
        whiteXy = ColorMath.whiteXy(targetCct, targetDuv);
        neutral =
            ColorMath.neutralForXy(
                whiteXy[0], whiteXy[1], f.colorMatrix1, f.colorMatrix2, f.calibration1, f.calibration2,
                f.illuminant1, f.illuminant2);
        stats.put("whiteBalance", r.whiteBalance + " (fixed: " + Math.round(targetCct) + " K, Duv " + targetDuv + ")");
        stats.put("whiteBalanceTargetXy", new double[] {round(whiteXy[0], 5), round(whiteXy[1], 5)});
        stats.put("whiteBalanceNeutral", roundAll(neutral, 5));
        double[] asShot = f.neutral;
        if (asShot != null && asShot.length == 3 && asShot[0] > 0 && asShot[1] > 0 && asShot[2] > 0)
          try {
            ColorMath.Result scene =
                ColorMath.cameraToRec2020(
                    asShot, f.colorMatrix1, f.colorMatrix2, f.forwardMatrix1, f.forwardMatrix2,
                    f.calibration1, f.calibration2, f.illuminant1, f.illuminant2);
            stats.put("asShotCct", Math.round(scene.cct));
          } catch (RuntimeException ignored) {
            // informational only
          }
      }
      double nR = neutral[0], nG = neutral[1], nB = neutral[2];
      if (!(nR > 0 && nG > 0 && nB > 0)) throw new IllegalArgumentException("Invalid neutral");
      int effShiftRed = cineDefaultWb ? -1 : r.wbShiftRed;
      int effShiftBlue = cineDefaultWb ? 1 : r.wbShiftBlue;
      double shiftR = Math.pow(2, WB_SHIFT_LOG2_PER_STEP * effShiftRed);
      double shiftB = Math.pow(2, WB_SHIFT_LOG2_PER_STEP * effShiftBlue);
      double gR = nG / nR * shiftR, gB = nG / nB * shiftB;
      for (int k = 0; k < 4; k++) gainK[k] = (float) (colorK[k] == 0 ? gR : colorK[k] == 2 ? gB : 1);
      clipFactor = (float) Math.min(1, Math.min(gR, gB));
      stats.put("wbGains", new double[] {round(gR, 4), 1, round(gB, 4)});
      stats.put("wbShiftSteps", new int[] {r.wbShiftRed, r.wbShiftBlue});
      stats.put("wbShiftLog2PerStep", WB_SHIFT_LOG2_PER_STEP);

      ColorMath.Result cm =
          ColorMath.cameraToRec2020(
              neutral,
              whiteXy,
              f.colorMatrix1,
              f.colorMatrix2,
              f.forwardMatrix1,
              f.forwardMatrix2,
              f.calibration1,
              f.calibration2,
              f.illuminant1,
              f.illuminant2);
      stats.put("sceneCct", Math.round(cm.cct));
      stats.put("sceneWhiteXy", new double[] {round(cm.whiteX, 5), round(cm.whiteY, 5)});
      stats.put("illuminantWeight1", round(cm.weight1, 4));
      stats.put("usedForwardMatrix", cm.usedForwardMatrix);
      stats.put("cameraToRec2020", roundAll(cm.cameraToRec2020, 5));

      shading = f.shading != null && f.shadingColumns >= 2 && f.shadingRows >= 2;
      sc0 = new int[W];
      sfx = new float[W];
      if (shading) {
        double span = Math.max(1, f.shadingWidth - 1);
        for (int x = 0; x < W; x++) {
          double u = (x - f.shadingLeft) / span * (f.shadingColumns - 1);
          u = Math.max(0, Math.min(f.shadingColumns - 1, u));
          int c0 = Math.min((int) u, f.shadingColumns - 2);
          sc0[x] = c0;
          sfx[x] = (float) (u - c0);
        }
      }
      stats.put(
          "lensShading",
          shading ? f.shadingColumns + "x" + f.shadingRows + " gain map applied" : "not available");

      // --- metering (centre-weighted log average, as fitted on Fujifilm JPEGs)
      double[] hist = new double[HIST_BINS];
      double[] meter = meter(cm.cameraToRec2020, hist);
      double logAverage = meter[0], rawClipped = meter[1];
      double key = sony ? SONY_KEY : METERING_KEY;
      double gain = key * Math.pow(2, o.exposureEv) / Math.max(1e-6, logAverage);
      double faceEv = faceExposureEv(cm.cameraToRec2020, logAverage, key, o, stats);
      if (faceEv != 0) gain *= Math.pow(2, faceEv);
      gain = Math.max(1 / 64.0, Math.min(512, gain));
      if (o.fixedGain > 0) gain = o.fixedGain;
      for (int i = 0; i < 9; i++) m[i] = (float) (cm.cameraToRec2020[i] * gain);
      monoFilter = r.monochrome() && r.monoFilter > 0 && r.monoFilter < MONO_FILTER_TRANSMISSION.length;
      if (monoFilter) {
        double[] w = monoWeights(lut), t = MONO_FILTER_TRANSMISSION[r.monoFilter], mix = new double[3];
        double sum = 0;
        for (int c = 0; c < 3; c++) {
          mix[c] = w[c] * t[c];
          sum += mix[c];
        }
        for (int c = 0; c < 3; c++) {
          mix[c] /= sum;
          monoMix[c] = (float) mix[c];
          monoW[c] = (float) w[c];
        }
        buildGreyTables(lut);
        stats.put("monoFilter", FujiRecipe.MONO_FILTERS[r.monoFilter]
            + " (approximation: filtered/unfiltered linear mix applied to the LUT's black-and-white rendering)");
        stats.put("monoLutWeights", roundAll(w, 4));
        stats.put("monoFilterMix", roundAll(mix, 4));
      }
      stats.put("meterLogAverage", round(logAverage, 6));
      stats.put("meterKey", key);
      stats.put("exposureEv", o.exposureEv);
      stats.put("developmentGain", round(gain, 4));
      stats.put("developmentGainEv", round(Math.log(gain) / Math.log(2), 3));
      stats.put("rawClippedFraction", round(rawClipped, 5));

      // --- dynamic range and the white point
      double gEv = Math.log(gain) / Math.log(2);
      double clipEv = Math.log(gain * clipFactor / 0.18) / Math.log(2);
      // D-Range Priority replaces DR and the tone curve (approximation, see DRP_* constants)
      boolean drp = r.dynamicRange.priority();
      double highlight = drp ? DRP_HIGHLIGHT : r.highlight, shadow = drp ? DRP_SHADOW : r.shadow;
      double extraEv = r.dynamicRange == FujiRecipe.DynamicRange.DRP_STRONG ? DRP_STRONG_EXTRA_EV : 0;
      ToneModel.Curve probe =
          sensorProfile != PROFILE_NONE
              ? ToneModel.calibratedCurve(0, highlight, shadow, o.stillsTone, 1, r.key)
              : ToneModel.curve(0, highlight, shadow, o.stillsTone, 1, !sony);
      double whiteEv = ToneModel.whiteEv(probe, lut);
      double above = fractionAbove(hist, whiteEv - gEv);
      int steps;
      switch (r.dynamicRange) {
        case DR200:
          steps = 1;
          break;
        case DR400:
        case DRP_WEAK:
        case DRP_STRONG:
          steps = 2;
          break;
        case DR100:
          steps = 0;
          break;
        default:
          steps = above > 0.01 ? 1 : 0;
      }
      stats.put(
          "dynamicRange",
          sony
              ? "Not used: Sony s709 has its own highlight roll-off"
              : drp
              ? r.dynamicRange.label + " (approximation: DR400 tone with Highlight " + FujiRecipe.signed(DRP_HIGHLIGHT)
                  + ", Shadow " + FujiRecipe.signed(DRP_SHADOW)
                  + (extraEv > 0 ? ", highlight range +" + extraEv + " EV" : "") + ")"
              : (r.dynamicRange == FujiRecipe.DynamicRange.AUTO ? "Auto: DR" : "DR") + (100 << steps));
      if (r.dynamicRange == FujiRecipe.DynamicRange.AUTO)
        stats.put("dynamicRangeAutoRule", "DR200 when >1% of the frame would render white at DR100 (approximation)");
      stats.put("sceneFractionAboveWhite", round(above, 5));
      stats.put("curveWhiteEv", round(whiteEv, 3));
      stats.put("rawClipEv", round(clipEv, 3));
      double shoulder = 1;
      if (o.stillsTone) {
        // The shoulder maps the RAW clip point to white when the RAW clips early; DR-P Strong also
        // stretches the curve's white over extraEv more scene range when the RAW has it.
        double wanted = whiteEv + extraEv;
        double target = clipEv < wanted ? Math.max(ToneModel.KNEE_EV + 0.75, clipEv) : wanted;
        if (target != whiteEv) shoulder = (whiteEv - ToneModel.KNEE_EV) / (target - ToneModel.KNEE_EV);
      }
      stats.put("shoulderScale", round(shoulder, 4));
      final ToneModel.Curve curve =
          sensorProfile != PROFILE_NONE
              ? ToneModel.calibratedCurve(steps, highlight, shadow, o.stillsTone, shoulder, r.key)
              : ToneModel.curve(steps, highlight, shadow, o.stillsTone, shoulder, !sony);
      tone =
          new ToneTable(
              x -> {
                double v = curve.apply(FLog2.encode(x));
                return v < 0 ? 0 : v > 1 ? 1 : v;
              });
      stats.put(
          "toneModel",
          sony
              ? ToneModel.VERSION + ": shoulder remap and tuning only (Sony s709 tone, no Fujifilm offsets)"
              : sensorProfile != PROFILE_NONE
                  ? ToneModel.VERSION + " + phone-sensor calibration (" + r.key + ")"
                  : ToneModel.VERSION);
      stats.put("toneOffsetsAtKnots", curve.knots());

      int iso = Math.max(50, f.iso);

      // --- luma noise model: RAW noise curve -> development gain -> lens shading -> tone curve
      double halS = NOISE_S_PER_ISO * iso, halO = NOISE_O_PER_ISO2 * (double) iso * iso;
      String noiseSource = "ISO fallback";
      if (f.noiseS != null && f.noiseO != null && f.noiseS.length == 4 && f.noiseO.length == 4) {
        double s = 0, oo = 0;
        int cnt = 0;
        for (int k = 0; k < 4; k++)
          if (colorK[k] == 1) {
            s += f.noiseS[k];
            oo += f.noiseO[k];
            cnt++;
          }
        if (cnt > 0 && s > 0) {
          halS = s / cnt;
          halO = Math.max(0, oo / cnt);
          noiseSource = "camera profile";
        }
      }
      int gk = 0;
      for (int k = 3; k >= 0; k--) if (colorK[k] == 1) gk = k;
      double[] measured = measureRawNoise(f, gk);
      double baseS = HAL_NOISE_SCALE * halS, baseO = HAL_NOISE_SCALE * halO;
      double nS = baseS, nO = baseO;
      if (measured != null) {
        nS = Math.max(0.5 * baseS, Math.min(1.6 * baseS, measured[0]));
        nO = Math.max(0.5 * baseO, Math.min(12 * baseO, measured[1]));
        noiseSource += " x" + HAL_NOISE_SCALE + ", adjusted by frame measurement (" + (int) measured[2] + " bins)";
      } else noiseSource += " x" + HAL_NOISE_SCALE;
      int nrStep = Math.max(-4, Math.min(4, r.noiseReduction));
      firstVersion = r.development == FujiRecipe.Development.FIRST_VERSION;
      // First version: no luma NR at -4 (as v0.2.0); each step up adds 1/8 of Clean's fine luma NR
      double lumaShare = firstVersion ? (nrStep + 4) / 8.0 : 1;
      double kNr = Math.max(0, LUMA_NR_K * Math.pow(2, 0.125 * nrStep) * o.lumaNr * lumaShare);
      nrKeep = (float) Math.max(0, Math.min(0.6, 0.2 - 0.05 * nrStep));
      epsFine = (float) (kNr * kNr);
      // First version: none at Sharpness -2 (v0.2.0 never sharpened); +-0.1 per step, softer below
      sharpen =
          (float)
              ((firstVersion
                      ? Math.max(-0.2, Math.min(1.2, SHARPEN_STEP * (r.sharpness + 2)))
                      : Math.max(0, Math.min(1.2, SHARPEN_BASE + SHARPEN_STEP * r.sharpness)))
                  * o.sharpen);
      int isoNr = Math.max(50, f.iso);
      int baseRadius = isoNr <= 200 ? 1 : isoNr <= 800 ? 2 : isoNr <= 3200 ? 3 : 4;
      nrRadius =
          !firstVersion
              ? 0
              : r.noiseReduction <= -4
                  ? baseRadius
                  : Math.min(FV_MAX_RADIUS, baseRadius + Math.max(0, r.noiseReduction + 4) / 3);
      nrA = new float[NR_TABLE + 1];
      nrB = new float[NR_TABLE + 1];
      noiseTables(curve, gain, nS, nO, NOISE_MIX * NOISE_MIX, nrA, nrB);
      double kLow = Math.max(0, LOW_NR_K * Math.pow(2, 0.125 * nrStep) * o.lumaNr);
      epsLow = (float) (kLow * kLow * Q_NOISE / 16);
      epsChroma = (float) (CHROMA_EPS * Q_NOISE / 16 * Math.pow(2, 0.25 * nrStep));
      lowKeep = (float) Math.max(0.05, Math.min(0.3, 0.1 - 0.02 * nrStep));
      cKeep = (float) Math.max(0, Math.min(0.5, 0.2 - 0.025 * nrStep));
      qW = (W + 3) / 4;
      bandY0 = 0;
      bandH = H;
      qH = (H + 3) / 4;
      qx0 = new int[W];
      qfx = new float[W];
      for (int x = 0; x < W; x++) {
        float u = (x - 1.5f) * 0.25f;
        int x0 = (int) Math.floor(u);
        if (x0 < 0) {
          qx0[x] = 0;
          qfx[x] = 0;
        } else if (x0 >= qW - 1) {
          qx0[x] = qW - 2;
          qfx[x] = 1;
        } else {
          qx0[x] = x0;
          qfx[x] = u - x0;
        }
      }
      stats.put("lowNrK", round(kLow, 3));
      stats.put("lowNrKeep", round(lowKeep, 3));
      stats.put("chromaKeep", round(cKeep, 3));
      double sigmaMid = NOISE_MIX * midGreyNoise(curve, gain, nS, nO);
      debugLuma = o.debugLuma;
      debugNoise = o.debugNoise;
      debugQuarter = o.debugQuarter;
      stats.put("noiseModel", noiseSource);
      stats.put("noiseShotRead", new double[] {nS, nO});
      stats.put("noiseProfileHal", new double[] {halS, halO});
      if (measured != null) stats.put("noiseMeasured", new double[] {measured[0], measured[1]});
      stats.put("lumaNrK", round(kNr, 3));
      stats.put("lumaNrKeep", round(nrKeep, 3));
      stats.put("sharpenAmount", round(sharpen, 3));
      stats.put("noiseMidGreyCode", round(sigmaMid, 5));

      boolean mono = r.monochrome();
      chroma = mono ? 1f : (float) (1 + ToneModel.COLOR_KAPPA * r.color);
      cceDepth = mono ? 0 : r.colorChrome == 2 ? CCE_STRONG : r.colorChrome == 1 ? CCE_STRONG / 2 : 0;
      blueDepth = mono ? 0 : fxBlueDepth(r.colorChromeBlue);
      post = chroma != 1f || cceDepth > 0;
      stats.put("colorChromaScale", round(chroma, 4));
      stats.put("colorChromeDepth", cceDepth);
      stats.put("colorChromeBlueDepth", blueDepth);
      if (mono)
        stats.put("colorEffects", "Color, Color Chrome Effect and FX Blue are not applied to a black-and-white film");

      float amp = !o.grain || r.grainRoughness == 0 ? 0 : r.grainRoughness == 2 ? GRAIN_STRONG : GRAIN_WEAK;
      if (r.grainLarge) amp *= 1.15f;
      // sensor noise left after NR already reads as grain: top up to the recipe amplitude only
      float cMid = (float) toneCode(curve, 0.18);
      float[] lo = new float[3], hi = new float[3];
      lut.sample(cMid - 0.02f, cMid - 0.02f, cMid - 0.02f, lo);
      lut.sample(cMid + 0.02f, cMid + 0.02f, cMid + 0.02f, hi);
      double lutSlope = Math.max(0, (hi[1] - lo[1]) / 0.04);
      double residual =
          kNr > 0 ? Math.sqrt(nrKeep * nrKeep + Math.pow((1 - nrKeep) * GF_RESIDUAL, 2)) : 1;
      double noiseOut = lutSlope * sigmaMid * residual;
      double ampEff =
          amp <= 0
              ? 0
              : firstVersion
                  ? amp // v0.2.0: the recipe amount, whatever the sensor noise
                  : Math.sqrt(Math.max(GRAIN_FLOOR * GRAIN_FLOOR * amp * amp, amp * amp - noiseOut * noiseOut));
      grainAmp = (float) ampEff;
      float sigma = r.grainLarge ? 1.1f : 0.55f;
      float[][] tex = grainTextures(sigma);
      grainA = tex[0];
      grainB = tex[1];
      long hseed = (o.grainSeed + 0x5DEECE66DL) * 0x9E3779B97F4A7C15L;
      grainOx = (int) ((hseed >>> 20) & 1023);
      grainOy = (int) ((hseed >>> 40) & 1023);
      mod997 = new int[Math.max(W, H) + 1024];
      for (int i = 0; i < mod997.length; i++) mod997[i] = i % 997;
      stats.put("noiseMidGreyOutput", round(noiseOut, 5));
      stats.put("grainAmplitudeRecipe", amp);
      stats.put("grainAmplitude", round(ampEff, 5));
      stats.put("grainSeed", o.grainSeed);

      int halSteps = Math.max(0, Math.min(10, r.halation)), bloomSteps = Math.max(0, Math.min(10, r.bloom));
      double aH = HALATION_PER_STEP * halSteps;
      float baseBloom = (float) (BLOOM_PER_STEP * bloomSteps);
      if (sensorProfile == PROFILE_KODAK_800_V3 && o.stillsTone && bloomSteps == 0)
        baseBloom = 0.036f; // Portra 800 v3 gentle emulsion highlight diffusion (Clarity -3 / 10% CineBloom)
      else if (sensorProfile == PROFILE_CINESTILL_800T && o.stillsTone) {
        aH = Math.max(aH, 0.085); // Remjet-free Vision3 500T red-orange pressure-plate halation
        if (bloomSteps == 0) baseBloom = 0.028f; // Soft cinema night diffusion (Clarity -5)
      }
      bloomA = baseBloom;
      haloA[0] = (float) aH;
      haloA[1] = (float) (aH * HALATION_GREEN);
      haloA[2] = 0;
      for (int c = 0; c < 3; c++) glowKeep[c] = (1 - bloomA) * (1 - haloA[c]);
      glow = aH > 0 || bloomA > 0;
      double longSide = Math.max(W, H) / 4.0; // in quarter-resolution blocks
      double haloSigma =
          (sensorProfile == PROFILE_CINESTILL_800T && o.stillsTone && halSteps == 0)
              ? 0.0095
              : HALATION_SIGMA;
      haloR = boxRadius(haloSigma * longSide);
      bloomR = boxRadius(BLOOM_SIGMA * longSide);
      clarityR = boxRadius(CLARITY_SIGMA * longSide);
      for (int k = 0; k < 4; k++) clipRaw[k] = (int) Math.ceil(black[k] + CLIP_LEVEL * (f.white - black[k]));
      int claritySteps = Math.max(-5, Math.min(5, r.clarity));
      clarityK = (float) (CLARITY_PER_STEP * claritySteps);
      stats.put("halation", halSteps);
      stats.put("bloom", bloomSteps);
      stats.put("clarity", claritySteps);
      if (glow) {
        stats.put("halationRed", round(aH, 4));
        stats.put("bloomShare", round(bloomA, 4));
        stats.put("glowRadiiQuarterPx", new int[] {haloR, bloomR});
        stats.put(
            "glowModel",
            "normalised redistribution in scene-linear light; clipped light sources glow as "
                + CLIP_BOOST + "x their clipped value (assumption)");
      }
      if (claritySteps != 0) stats.put("clarityModel", "mid-band local contrast, approximation; k=" + round(clarityK, 3));
      if (sensorProfile == PROFILE_KODAK_800_V3)
        stats.put(
            "sensorCalibration",
            "Phone CMOS -> Kodak Portra 800 v3: Fuji-RAF Kelvin curve (6600K -> 5693K DNG), soft highlight shoulder, open shadow toe, OKLab Color/CCE, golden-amber/skin/sage-foliage calibration");
      else if (sensorProfile == PROFILE_LEICA_X)
        stats.put(
            "sensorCalibration",
            "Phone CMOS -> Leica X: Summilux midtone/black-toe contrast, specular roll-off, OKLab Color +4/CCE pigment density, Leica crimson/skin-guard/forest-green/cobalt calibration");
      else if (sensorProfile == PROFILE_CINESTILL_800T)
        stats.put(
            "sensorCalibration",
            "Phone CMOS -> CineStill 800T (Vision3 500T remjet-free): 3450K tungsten balance, scene-linear red-orange halation around bright lights, cyan-teal shadow / warm-amber highlight split, OKLab cinema colour");
      fvNoise = firstVersion && (epsFine > 0 || sharpen != 0 || clarityK != 0);
      if (firstVersion && post) {
        srgbDecode = new float[4097];
        srgbEncode = new float[2049];
        for (int i = 0; i <= 4096; i++) {
          double c = i / 4096.0;
          srgbDecode[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i <= 2048; i++) {
          double u = i / 2048.0, lin = u * u;
          srgbEncode[i] = (float) (lin <= 0.0031308 ? 12.92 * lin : 1.055 * Math.pow(lin, 1 / 2.4) - 0.055);
        }
      } else {
        srgbDecode = null;
        srgbEncode = null;
      }
      if (firstVersion) {
        stats.put(
            "development",
            "First version (Grain v0.2.0): colour noise box radius " + nrRadius + ", luma NR share "
                + round(lumaShare, 3) + ", sharpen " + round(sharpen, 3)
                + ", full recipe grain, Color Chrome / Color in OKLab");
        stats.put("chromaNoiseRadius", nrRadius);
      } else stats.put("development", "Clean");

      stats.put("lut", lut.title.isEmpty() ? r.filmSimulation : lut.title);
      stats.put("lutGamut", lut.gamut);
      stats.put("lutModel", lut.model);
      stats.put("lutInput", lut.input.label);
    }

    void prepareBand(int srcY0, int srcH) {
      this.bandY0 = srcY0;
      this.bandH = srcH;
      this.qH = (srcH + 3) / 4;
      int qn = qW * qH;
      qS = new float[qn];
      if (glow) {
        qLin = new float[3 * qn];
        qClip = new float[qn];
      } else {
        qY = new float[qn];
        qU = new float[qn];
        qV = new float[qn];
      }
    }

    void releaseBand() {
      qY = qU = qV = qS = null;
      dY = pU = pV = kq = null;
      qLin = qClip = band = colSum = null;
      glowQ = null;
    }

    /** Pre-measures global chroma noise across the whole frame using one 5-block row strip (~1 MB). */
    void lockGlobalChromaNoise() {
      int fullQH = (H + 3) / 4;
      int bw = qW / 5, bh = fullQH / 5, cnt = 0;
      if (bw <= 0 || bh <= 0) return;
      float[] ru = new float[bw * bh], rv = new float[bw * bh];
      float[] acc = new float[3 * qW], cntCh = new float[3];
      float[][] sh = new float[4][qW];
      float[] tmpRow = new float[Math.max(2, shading ? f.shadingColumns : 2)];
      float[] stripY = new float[5 * qW], stripU = new float[5 * qW];
      float[] stripV = new float[5 * qW], stripS = new float[5 * qW];
      final short[] data = f.data;
      for (int by = 0; by < bh; by++) {
        for (int r = 0; r < 5; r++) {
          int qy = by * 5 + r;
          int y0 = qy * 4, y1 = Math.min(H, y0 + 4), cy = Math.min(H - 1, y0 + 2);
          for (int ch = 0; ch < 4; ch++) shadingBlocks(ch, cy, tmpRow, sh[ch]);
          java.util.Arrays.fill(acc, 0);
          cntCh[0] = cntCh[1] = cntCh[2] = 0;
          for (int y = y0; y < y1; y++) {
            int row = y * f.rowStride, kr = (y & 1) << 1;
            int c0 = colorK[kr], c1 = colorK[kr | 1];
            float b0 = black[kr], b1 = black[kr | 1];
            float s0 = scale[kr] * gainK[kr], s1 = scale[kr | 1] * gainK[kr | 1];
            for (int bx = 0, x = 0; bx < qW; bx++) {
              int xe = Math.min(W, x + 4);
              float e = 0, od = 0;
              for (; x + 1 < xe; x += 2) {
                e += (data[row + x] & 0xffff) - b0;
                od += (data[row + x + 1] & 0xffff) - b1;
              }
              acc[3 * bx + c0] += e * s0;
              acc[3 * bx + c1] += od * s1;
            }
            cntCh[c0] += 2;
            cntCh[c1] += 2;
          }
          float iR = 1f / Math.max(1, cntCh[0]);
          float iG = 1f / Math.max(1, cntCh[1]);
          float iB = 1f / Math.max(1, cntCh[2]);
          int ro = r * qW;
          for (int bx = 0; bx < qW; bx++) {
            float shG = 0.5f * (sh[1][bx] + sh[2][bx]);
            float R = acc[3 * bx] * iR * sh[0][bx];
            float G = acc[3 * bx + 1] * iG * shG;
            float B = acc[3 * bx + 2] * iB * sh[3][bx];
            float t = shG * clipFactor;
            if (G > 0.9f * t) {
              float w = (G - 0.9f * t) / (0.08f * t);
              if (w > 1) w = 1;
              if (R > G) R += w * (G - R);
              if (B > G) B += w * (G - B);
            }
            float c0 = tone.apply(m[0] * R + m[1] * G + m[2] * B);
            float c1 = tone.apply(m[3] * R + m[4] * G + m[5] * B);
            float c2 = tone.apply(m[6] * R + m[7] * G + m[8] * B);
            float yq = 0.25f * (c0 + 2 * c1 + c2);
            stripY[ro + bx] = yq;
            stripU[ro + bx] = c0 - yq;
            stripV[ro + bx] = c2 - yq;
            stripS[ro + bx] = shG;
          }
        }
        for (int bx = 0; bx < bw; bx++) {
          double sy = 0, syy = 0, su = 0, suu = 0, sv = 0, svv = 0, sm = 0;
          for (int r = 0; r < 5; r++)
            for (int x = bx * 5, i = r * qW + x; x < bx * 5 + 5; x++, i++) {
              sy += stripY[i];
              syy += stripY[i] * stripY[i];
              su += stripU[i];
              suu += stripU[i] * stripU[i];
              sv += stripV[i];
              svv += stripV[i] * stripV[i];
              sm += noiseVar(stripY[i], stripS[i]);
            }
          double mean = sy / 25, model = sm / 25 / 16;
          if (model <= 1e-14 || mean < 0.05 || mean > 0.85) continue;
          double vy = syy / 25 - mean * mean;
          if (vy > 2.5 * Q_NOISE * model) continue;
          ru[cnt] = (float) ((suu / 25 - su * su / 625) / model);
          rv[cnt] = (float) ((svv / 25 - sv * sv / 625) / model);
          cnt++;
        }
      }
      if (cnt < 400) {
        lockedChromaNoise = new double[] {CHROMA_U_DEFAULT, CHROMA_V_DEFAULT, cnt};
        return;
      }
      float[] u = java.util.Arrays.copyOf(ru, cnt), v = java.util.Arrays.copyOf(rv, cnt);
      java.util.Arrays.sort(u);
      java.util.Arrays.sort(v);
      double mu = Math.max(0.5, Math.min(40, u[cnt / 2]));
      double mv = Math.max(0.5, Math.min(60, v[cnt / 2]));
      lockedChromaNoise = new double[] {mu, mv, cnt};
    }

    /** Quarter-resolution pre-pass in parallel, then its global noise reduction. */
    void quarter(ExecutorService pool, int threads) throws Exception {
      List<Callable<Void>> tasks = new ArrayList<>();
      int per = (qH + threads - 1) / threads;
      for (int t = 0; t < threads; t++) {
        final int a = t * per, b = Math.min(qH, a + per);
        if (a >= b) break;
        tasks.add(
            () -> {
              quarterRows(a, b);
              return null;
            });
      }
      for (Future<Void> fu : pool.invokeAll(tasks)) fu.get();
      long t = System.nanoTime();
      if (glow) {
        buildGlow();
        quarterCodes();
      }
      if (firstVersion) quarterFirstVersion();
      else quarterDenoise();
      quarterDenoiseMs = Math.round((System.nanoTime() - t) / 1e6);
    }

    long quarterDenoiseMs;

    /**
     * First version: no low-frequency noise reduction (chroma kept as is, luma uncorrected); only
     * the Clarity band, from the quarter-resolution luma.
     */
    void quarterFirstVersion() {
      int n = qW * qH;
      dY = new float[n];
      pU = new float[n];
      pV = new float[n];
      kq = new float[n];
      java.util.Arrays.fill(kq, 1f);
      if (clarityK != 0) {
        if (colSum == null) colSum = new float[qW];
        band = new float[n];
        gauss(qY, band, clarityR, new float[n]);
        for (int i = 0; i < n; i++) band[i] = qY[i] - band[i];
      }
      qY = qU = qV = qS = null;
    }

    float decode(float c) {
      if (c <= 0) return 0;
      if (c >= 1) return 1;
      float p = c * 4096;
      int i = (int) p;
      if (i >= 4096) i = 4095;
      return srgbDecode[i] + (p - i) * (srgbDecode[i + 1] - srgbDecode[i]);
    }

    float encode(float lin) {
      if (lin <= 0) return 0;
      if (lin >= 1) return 1;
      float p = (float) Math.sqrt(lin) * 2048;
      int i = (int) p;
      if (i >= 2048) i = 2047;
      return srgbEncode[i] + (p - i) * (srgbEncode[i + 1] - srgbEncode[i]);
    }

    /** Codes of each 4 x 4 RAW block: mean R, G, B -> highlight rule -> matrix -> tone. */
    void quarterRows(int by0, int by1) {
      final short[] data = f.data;
      float[] acc = new float[3 * qW], cnt = new float[3];
      int[] clipCount = glow ? new int[qW] : null;
      float[][] sh = new float[4][qW];
      float[] tmpRow = new float[Math.max(2, shading ? f.shadingColumns : 2)];
      for (int by = by0; by < by1; by++) {
        int y0 = by * 4, y1 = Math.min(bandH, y0 + 4), cy = Math.min(bandH - 1, y0 + 2);
        for (int ch = 0; ch < 4; ch++) shadingBlocks(ch, bandY0 + cy, tmpRow, sh[ch]);
        java.util.Arrays.fill(acc, 0);
        cnt[0] = cnt[1] = cnt[2] = 0;
        for (int y = y0; y < y1; y++) {
          int row = (bandY0 + y) * f.rowStride, kr = (y & 1) << 1;
          int c0 = colorK[kr], c1 = colorK[kr | 1];
          float b0 = black[kr], b1 = black[kr | 1];
          float s0 = scale[kr] * gainK[kr], s1 = scale[kr | 1] * gainK[kr | 1];
          for (int bx = 0, x = 0; bx < qW; bx++) {
            int xe = Math.min(W, x + 4);
            float e = 0, od = 0;
            for (; x + 1 < xe; x += 2) {
              e += (data[row + x] & 0xffff) - b0;
              od += (data[row + x + 1] & 0xffff) - b1;
            }
            acc[3 * bx + c0] += e * s0;
            acc[3 * bx + c1] += od * s1;
          }
          cnt[c0] += 2;
          cnt[c1] += 2;
        }
        float iR = 1f / Math.max(1, cnt[0]), iG = 1f / Math.max(1, cnt[1]), iB = 1f / Math.max(1, cnt[2]);
        if (glow) {
          java.util.Arrays.fill(clipCount, 0);
          // green sites only: a light source saturates green too, a saturated red object does not
          for (int y = y0; y < y1; y++) {
            int row = (bandY0 + y) * f.rowStride, kr = (y & 1) << 1;
            int t0 = colorK[kr] == 1 ? clipRaw[kr] : Integer.MAX_VALUE;
            int t1 = colorK[kr | 1] == 1 ? clipRaw[kr | 1] : Integer.MAX_VALUE;
            for (int x = 0; x + 1 < W; x += 2) {
              if ((data[row + x] & 0xffff) >= t0) clipCount[x >> 2]++;
              if ((data[row + x + 1] & 0xffff) >= t1) clipCount[(x + 1) >> 2]++;
            }
          }
          float inv = 1f / Math.max(1, (y1 - y0) * 2);
          for (int bx = 0; bx < qW; bx++) qClip[by * qW + bx] = clipCount[bx] * inv;
        }
        for (int bx = 0; bx < qW; bx++) {
          float shG = 0.5f * (sh[1][bx] + sh[2][bx]);
          float R = acc[3 * bx] * iR * sh[0][bx];
          float G = acc[3 * bx + 1] * iG * shG;
          float B = acc[3 * bx + 2] * iB * sh[3][bx];
          float t = shG * clipFactor;
          if (G > 0.9f * t) {
            float w = (G - 0.9f * t) / (0.08f * t);
            if (w > 1) w = 1;
            if (R > G) R += w * (G - R);
            if (B > G) B += w * (G - B);
          }
          int i = by * qW + bx;
          if (glow) {
            qLin[3 * i] = m[0] * R + m[1] * G + m[2] * B;
            qLin[3 * i + 1] = m[3] * R + m[4] * G + m[5] * B;
            qLin[3 * i + 2] = m[6] * R + m[7] * G + m[8] * B;
            qS[i] = shG;
            continue;
          }
          float c0 = tone.apply(m[0] * R + m[1] * G + m[2] * B);
          float c1 = tone.apply(m[3] * R + m[4] * G + m[5] * B);
          float c2 = tone.apply(m[6] * R + m[7] * G + m[8] * B);
          float yq = 0.25f * (c0 + 2 * c1 + c2);
          qY[i] = yq;
          qU[i] = c0 - yq;
          qV[i] = c2 - yq;
          qS[i] = shG;
        }
      }
    }

    /**
     * Glow planes at quarter resolution: the source is the scene-linear block value plus, for
     * clipped RAW pixels, the light they lost (CLIP_BOOST); halation blurs it with the small kernel
     * into red (and some green), bloom with the wide kernel into all channels. Three box passes
     * approximate a Gaussian.
     */
    void buildGlow() {
      int n = qW * qH;
      if (colSum == null) colSum = new float[qW];
      float[] src = new float[n], blur = new float[n], box = new float[n], x = new float[n];
      float boost = CLIP_BOOST - 1;
      boolean clipped = false;
      for (int i = 0; i < n; i++) {
        x[i] = boost * qClip[i] * qS[i]; // light the clipped pixels lost, in units of the clip colour
        clipped |= x[i] > 0;
      }
      // that extra light only reaches the surroundings: max(0, K * X - X), so the inside of a
      // clipped source keeps its colour and its edge is not darkened
      float[] xh = null, xb = null;
      if (clipped && haloA[0] > 0) {
        xh = new float[n];
        gauss(x, xh, haloR, box);
        for (int i = 0; i < n; i++) xh[i] = Math.max(0, xh[i] - x[i]);
      }
      if (clipped && bloomA > 0) {
        xb = new float[n];
        gauss(x, xb, bloomR, box);
        for (int i = 0; i < n; i++) xb[i] = Math.max(0, xb[i] - x[i]);
      }
      x = null;
      // inside clipped sources the light given away is put back (weight keepIn = min(1, 2 x clipped
      // share)): the film stays saturated there
      float[] remHalo = null;
      if (sensorProfile == PROFILE_CINESTILL_800T && haloA[0] > 0) {
        float[] rem = new float[n], remBlur = new float[n];
        boolean hasBright = false;
        for (int i = 0; i < n; i++) {
          float yLin =
              0.2627f * Math.max(0f, qLin[3 * i])
                  + 0.6780f * Math.max(0f, qLin[3 * i + 1])
                  + 0.0593f * Math.max(0f, qLin[3 * i + 2]);
          if (yLin > 0.50f) {
            rem[i] = (yLin - 0.50f) * 6.5f;
            hasBright = true;
          }
        }
        if (hasBright) {
          gauss(rem, remBlur, haloR, box);
          remHalo = new float[n];
          for (int i = 0; i < n; i++) remHalo[i] = Math.max(0f, remBlur[i] - rem[i]);
        }
      }
      glowQ = new float[3][];
      for (int c = 0; c < 3; c++) {
        float clip = (m[3 * c] + m[3 * c + 1] + m[3 * c + 2]) * clipFactor;
        for (int i = 0; i < n; i++) src[i] = Math.max(0, qLin[3 * i + c]);
        float[] g = new float[n];
        if (haloA[c] > 0) {
          gauss(src, blur, haloR, box);
          float w = (1 - bloomA) * haloA[c];
          float remScale = c == 0 ? 1.0f : c == 1 ? 0.26f : 0f;
          for (int i = 0; i < n; i++) {
            float keepIn = Math.min(1, 2 * qClip[i]);
            float extra = (xh == null ? 0 : clip * xh[i]) + (remHalo == null ? 0 : remScale * remHalo[i]);
            g[i] += w * (blur[i] + extra + keepIn * Math.max(0, src[i] - blur[i]));
          }
        }
        if (bloomA > 0) {
          gauss(src, blur, bloomR, box);
          for (int i = 0; i < n; i++) {
            float keepIn = Math.min(1, 2 * qClip[i]);
            g[i] += bloomA * (blur[i] + (xb == null ? 0 : clip * xb[i]) + keepIn * Math.max(0, src[i] - blur[i]));
          }
        }
        glowQ[c] = g;
      }
    }

    /** Quarter-resolution codes once the glow is known (same steps as quarterRows). */
    void quarterCodes() {
      int n = qW * qH;
      qY = new float[n];
      qU = new float[n];
      qV = new float[n];
      float kR = glowKeep[0], kG = glowKeep[1], kB = glowKeep[2];
      float[] gR = glowQ[0], gG = glowQ[1], gB = glowQ[2];
      for (int i = 0; i < n; i++) {
        float c0 = tone.apply(kR * qLin[3 * i] + gR[i]);
        float c1 = tone.apply(kG * qLin[3 * i + 1] + gG[i]);
        float c2 = tone.apply(kB * qLin[3 * i + 2] + gB[i]);
        float yq = 0.25f * (c0 + 2 * c1 + c2);
        qY[i] = yq;
        qU[i] = c0 - yq;
        qV[i] = c2 - yq;
      }
      qLin = qClip = null;
    }

    /** Three edge-replicated box means of radius r: a Gaussian of sigma^2 = r (r + 1). */
    void gauss(float[] in, float[] out, int r, float[] tmp) {
      boxMean(in, out, r, tmp);
      boxMean(out, out, r, tmp);
      boxMean(out, out, r, tmp);
    }

    /** Gain of one shading channel at the centres (4 bx + 2, y) of the quarter-resolution blocks. */
    void shadingBlocks(int ch, int y, float[] tmp, float[] out) {
      if (!shading) {
        java.util.Arrays.fill(out, 1f);
        return;
      }
      int cols = f.shadingColumns, rows = f.shadingRows;
      double v = (y - f.shadingTop) / (double) Math.max(1, f.shadingHeight - 1) * (rows - 1);
      v = Math.max(0, Math.min(rows - 1, v));
      int r0 = Math.min((int) v, rows - 2);
      float fr = (float) (v - r0);
      float[] map = f.shading;
      for (int c = 0; c < cols; c++) {
        float a = map[(r0 * cols + c) * 4 + ch], b = map[((r0 + 1) * cols + c) * 4 + ch];
        tmp[c] = a + fr * (b - a);
      }
      for (int bx = 0; bx < out.length; bx++) {
        int x = Math.min(W - 1, 4 * bx + 2), c0 = sc0[x];
        out[bx] = tmp[c0] + sfx[x] * (tmp[c0 + 1] - tmp[c0]);
      }
    }

    float noiseVar(float code, float s) {
      float ti = code * NR_TABLE;
      int t = ti <= 0 ? 0 : ti >= NR_TABLE ? NR_TABLE : (int) ti;
      return nrA[t] * s + nrB[t] * s * s;
    }

    /**
     * Low-frequency noise reduction on the quarter-resolution planes: a self-guided filter on luma
     * (epsilon from the noise curve), then chroma filtered with the denoised luma as guide, so colour
     * blotches go while colour edges that coincide with luminance edges stay put. Leaves the
     * per-pixel corrections dY (denoised - raw luma) and pU/pV (denoised chroma - kept share).
     */
    float[] colSum;

    void quarterDenoise() {
      int n = qW * qH;
      colSum = new float[qW];
      float[] m1 = new float[n], m2 = new float[n], t1 = new float[n], box = new float[n];
      for (int i = 0; i < n; i++) t1[i] = qY[i] * qY[i];
      boxMean(qY, m1, QR, box);
      boxMean(t1, m2, QR, box);
      for (int i = 0; i < n; i++) {
        float mean = m1[i], var = Math.max(0, m2[i] - mean * mean);
        float eps = epsLow * noiseVar(mean, qS[i]);
        float ai = var / (var + eps + 1e-12f);
        m1[i] = ai;
        m2[i] = (1 - ai) * mean;
      }
      boxMean(m1, m1, QR, box);
      boxMean(m2, m2, QR, box);
      dY = new float[n];
      float[] yd = t1;
      for (int i = 0; i < n; i++) {
        float q = m1[i] * qY[i] + m2[i];
        yd[i] = q + lowKeep * (qY[i] - q);
        dY[i] = yd[i] - qY[i];
      }
      if (clarityK != 0) {
        band = new float[n];
        gauss(yd, band, clarityR, box);
        for (int i = 0; i < n; i++) band[i] = yd[i] - band[i];
      }
      if (debugQuarter != null)
        for (int i = 0; i < n; i++) {
          debugQuarter[i] = qY[i];
          debugQuarter[n + i] = noiseVar(qY[i], qS[i]) / 16;
          debugQuarter[2 * n + i] = qU[i];
          debugQuarter[3 * n + i] = qV[i];
        }
      double[] cn = lockedChromaNoise != null ? lockedChromaNoise : chromaNoise();
      chromaNoiseU = cn[0];
      chromaNoiseV = cn[1];
      chromaNoiseBlocks = (int) cn[2];
      qY = null;
      pU = new float[n];
      pV = new float[n];
      float[] mY = new float[n], mYY = new float[n], tmp = new float[n];
      boxMean(yd, mY, QRC, box);
      for (int i = 0; i < n; i++) tmp[i] = yd[i] * yd[i];
      boxMean(tmp, mYY, QRC, box);
      for (int ch = 0; ch < 2; ch++) {
        float[] p = ch == 0 ? qU : qV, out = ch == 0 ? pU : pV;
        boxMean(p, m1, QRC, box);
        for (int i = 0; i < n; i++) tmp[i] = yd[i] * p[i];
        boxMean(tmp, m2, QRC, box);
        for (int i = 0; i < n; i++) {
          float mPi = m1[i];
          float varI = Math.max(0, mYY[i] - mY[i] * mY[i]), cov = m2[i] - mY[i] * mPi;
          float eps = epsChroma * noiseVar(mY[i], qS[i]);
          float ai = cov / (varI + eps + 1e-12f);
          m1[i] = ai;
          m2[i] = mPi - ai * mY[i];
        }
        boxMean(m1, m1, QRC, box);
        boxMean(m2, m2, QRC, box);
        for (int i = 0; i < n; i++) out[i] = m1[i] * yd[i] + m2[i]; // luma-guided chroma
      }
      yd = t1 = mYY = null;
      // Local Wiener step: chroma structure the luma cannot explain is kept where its energy is
      // well above the frame's chroma noise (colour detail), and dropped where it is not (blotches).
      float[] eU = m1, eV = m2;
      for (int i = 0; i < n; i++) {
        float r = qU[i] - pU[i];
        tmp[i] = r * r;
      }
      boxMean(tmp, eU, QR, box);
      for (int i = 0; i < n; i++) {
        float r = qV[i] - pV[i];
        tmp[i] = r * r;
      }
      boxMean(tmp, eV, QR, box);
      tmp = box = null;
      float cu = (float) chromaNoiseU, cv = (float) chromaNoiseV, t2 = (float) (2 * WIENER_T);
      for (int i = 0; i < n; i++) {
        float mv = noiseVar(mY[i], qS[i]) * (1f / 16f) + 1e-12f;
        float z = eU[i] / (cu * mv) + eV[i] / (cv * mv);
        float w = z > t2 ? 1 - t2 / z : 0;
        float k = cKeep + (1 - cKeep) * w;
        mY[i] = k;
        float u = pU[i] + w * (qU[i] - pU[i]), v = pV[i] + w * (qV[i] - pV[i]);
        pU[i] = u - k * qU[i];
        pV[i] = v - k * qV[i];
      }
      kq = mY;
      qU = qV = qS = null;
    }

    double chromaNoiseU = CHROMA_U_DEFAULT, chromaNoiseV = CHROMA_V_DEFAULT;
    int chromaNoiseBlocks;

    /**
     * Chroma noise of this frame relative to the luma model / 16: the median variance of r - Y and
     * b - Y over 5 x 5 quarter-resolution blocks whose luma is flat (variance within 2.5x of the
     * model), so only noise is measured. Falls back to the Redmi defaults with too few flat blocks.
     */
    double[] chromaNoise() {
      int bw = qW / 5, bh = qH / 5, cnt = 0;
      float[] ru = new float[bw * bh], rv = new float[bw * bh];
      for (int by = 0; by < bh; by++)
        for (int bx = 0; bx < bw; bx++) {
          double sy = 0, syy = 0, su = 0, suu = 0, sv = 0, svv = 0, sm = 0;
          for (int y = by * 5; y < by * 5 + 5; y++)
            for (int x = bx * 5, i = y * qW + x; x < bx * 5 + 5; x++, i++) {
              sy += qY[i];
              syy += qY[i] * qY[i];
              su += qU[i];
              suu += qU[i] * qU[i];
              sv += qV[i];
              svv += qV[i] * qV[i];
              sm += noiseVar(qY[i], qS[i]);
            }
          double mean = sy / 25, model = sm / 25 / 16;
          if (model <= 1e-14 || mean < 0.05 || mean > 0.85) continue;
          double vy = syy / 25 - mean * mean;
          if (vy > 2.5 * Q_NOISE * model) continue;
          ru[cnt] = (float) ((suu / 25 - su * su / 625) / model);
          rv[cnt] = (float) ((svv / 25 - sv * sv / 625) / model);
          cnt++;
        }
      if (cnt < 400) return new double[] {CHROMA_U_DEFAULT, CHROMA_V_DEFAULT, cnt};
      float[] u = java.util.Arrays.copyOf(ru, cnt), v = java.util.Arrays.copyOf(rv, cnt);
      java.util.Arrays.sort(u);
      java.util.Arrays.sort(v);
      double mu = Math.max(0.5, Math.min(40, u[cnt / 2])), mv = Math.max(0.5, Math.min(60, v[cnt / 2]));
      return new double[] {mu, mv, cnt};
    }

    /** Edge-replicated (2r+1)^2 box mean of a quarter-resolution plane. */
    void boxMean(float[] in, float[] out, int r, float[] tmp) {
      float inv = 1f / (2 * r + 1);
      for (int y = 0; y < qH; y++) {
        int o = y * qW;
        float s = 0;
        for (int k = -r; k <= r; k++) s += in[o + Math.max(0, Math.min(qW - 1, k))];
        for (int x = 0; x < qW; x++) {
          tmp[o + x] = s * inv;
          s += in[o + Math.min(qW - 1, x + r + 1)] - in[o + Math.max(0, x - r)];
        }
      }
      float[] col = colSum;
      java.util.Arrays.fill(col, 0);
      for (int k = -r; k <= r; k++) {
        int o = Math.max(0, Math.min(qH - 1, k)) * qW;
        for (int x = 0; x < qW; x++) col[x] += tmp[o + x];
      }
      for (int y = 0; y < qH; y++) {
        int o = y * qW, add = Math.min(qH - 1, y + r + 1) * qW, sub = Math.max(0, y - r) * qW;
        for (int x = 0; x < qW; x++) {
          out[o + x] = col[x] * inv;
          col[x] += tmp[add + x] - tmp[sub + x];
        }
      }
    }

    static final int GREY_N = 4096;

    /** The LUT's grey response (codes 0..1), its inverse, and F-Log2 decode/encode tables. */
    void buildGreyTables(CubeLut l) {
      int n = GREY_N;
      greyCurve = new float[n];
      codeLinear = new float[n];
      float[] out = new float[3];
      for (int i = 0; i < n; i++) {
        float c = i / (float) (n - 1);
        l.sample(c, c, c, out);
        greyCurve[i] = i == 0 ? out[1] : Math.max(greyCurve[i - 1], out[1]);
        codeLinear[i] = (float) Math.max(0, FLog2.decode(c));
      }
      greyInverse = new float[n];
      int k = 0;
      for (int i = 0; i < n; i++) {
        float y = i / (float) (n - 1);
        while (k < n - 1 && greyCurve[k + 1] < y) k++;
        float c;
        if (y <= greyCurve[0]) c = 0;
        else if (k >= n - 1) c = 1;
        else {
          float a = greyCurve[k], b = greyCurve[k + 1];
          c = (k + (b > a ? (y - a) / (b - a) : 0)) / (n - 1);
        }
        greyInverse[i] = Math.min(1, Math.max(0, c));
      }
      linearCode =
          new ToneTable(
              x -> {
                double v = FLog2.encode(Math.max(0, x));
                return v < 0 ? 0 : v > 1 ? 1 : v;
              });
    }

    static float table(float[] t, float v) {
      float p = (v <= 0 ? 0 : v >= 1 ? 1 : v) * (t.length - 1);
      int i = (int) p;
      if (i >= t.length - 1) return t[t.length - 1];
      float f = p - i;
      return t[i] + f * (t[i + 1] - t[i]);
    }

    /** Acros filter for one pixel: LUT input codes and the LUT's black-and-white output y. */
    float filterMono(float c0, float c1, float c2, float y) {
      float lr = table(codeLinear, c0), lg = table(codeLinear, c1), lb = table(codeLinear, c2);
      float den = monoW[0] * lr + monoW[1] * lg + monoW[2] * lb;
      if (!(den > 1e-7f)) return y;
      float rho = (monoMix[0] * lr + monoMix[1] * lg + monoMix[2] * lb) / den;
      if (Math.abs(rho - 1) < 1e-4f) return y;
      float le = table(codeLinear, table(greyInverse, y));
      return table(greyCurve, linearCode.apply(le * rho));
    }

    /** Gain of one shading channel along row y into out[0..W). */
    void shadingRow(int ch, int y, float[] tmp, float[] out) {
      if (!shading) {
        java.util.Arrays.fill(out, 0, W, 1f);
        return;
      }
      int cols = f.shadingColumns, rows = f.shadingRows;
      double v = (y - f.shadingTop) / (double) Math.max(1, f.shadingHeight - 1) * (rows - 1);
      v = Math.max(0, Math.min(rows - 1, v));
      int r0 = Math.min((int) v, rows - 2);
      float fr = (float) (v - r0);
      float[] map = f.shading;
      for (int c = 0; c < cols; c++) {
        float a = map[(r0 * cols + c) * 4 + ch], b = map[((r0 + 1) * cols + c) * 4 + ch];
        tmp[c] = a + fr * (b - a);
      }
      for (int x = 0; x < W; x++) {
        int c0 = sc0[x];
        out[x] = tmp[c0] + sfx[x] * (tmp[c0 + 1] - tmp[c0]);
      }
    }

    float shadingAt(int ch, int x, int y) {
      if (!shading) return 1f;
      int cols = f.shadingColumns, rows = f.shadingRows;
      double v = (y - f.shadingTop) / (double) Math.max(1, f.shadingHeight - 1) * (rows - 1);
      v = Math.max(0, Math.min(rows - 1, v));
      int r0 = Math.min((int) v, rows - 2);
      float fr = (float) (v - r0);
      int c0 = sc0[x];
      float fx = sfx[x];
      float[] map = f.shading;
      float a = map[(r0 * cols + c0) * 4 + ch], b = map[(r0 * cols + c0 + 1) * 4 + ch];
      float c = map[((r0 + 1) * cols + c0) * 4 + ch], d = map[((r0 + 1) * cols + c0 + 1) * 4 + ch];
      float top = a + fx * (b - a), bottom = c + fx * (d - c);
      return top + fr * (bottom - top);
    }

    /** {log-average luminance, clipped fraction} on a sparse grid of 2x2 quads; fills a log2 histogram. */
    double[] meter(double[] cam, double[] hist) {
      double sumW = 0, sumL = 0;
      long n = 0, clipped = 0;
      float[] q = new float[3];
      double cx = W / 2.0, cy = H / 2.0;
      float sat = f.white - 0.02f * (f.white - Math.min(Math.min(f.black[0], f.black[1]), Math.min(f.black[2], f.black[3])));
      for (int y = 0; y + 1 < H; y += 8) {
        for (int x = 0; x + 1 < W; x += 8) {
          q[0] = q[1] = q[2] = 0;
          boolean clip = false;
          float gShade = 1;
          for (int k = 0; k < 4; k++) {
            int xx = x + (k & 1), yy = y + (k >> 1);
            int raw = f.data[yy * f.rowStride + xx] & 0xffff;
            if (raw >= sat) clip = true;
            float s = shadingAt(shadeK[k], xx, yy);
            if (colorK[k] == 1) gShade = s;
            float v = (raw - black[k]) * scale[k] * s * gainK[k];
            q[colorK[k]] += colorK[k] == 1 ? 0.5f * v : v;
          }
          float t = gShade * clipFactor;
          if (clip) for (int c = 0; c < 3; c++) q[c] = Math.min(q[c], t);
          double R = cam[0] * q[0] + cam[1] * q[1] + cam[2] * q[2];
          double G = cam[3] * q[0] + cam[4] * q[1] + cam[5] * q[2];
          double B = cam[6] * q[0] + cam[7] * q[1] + cam[8] * q[2];
          double Y = 0.2627 * R + 0.6780 * G + 0.0593 * B;
          double rr = Math.hypot((x - cx) / cx, (y - cy) / cy);
          double w = 1 + 2 * Math.exp(-(rr * rr) / (2 * 0.35 * 0.35));
          sumW += w;
          sumL += w * Math.log(Math.max(Y, 1e-5));
          int bin = (int) ((Math.log(Math.max(Y, 1e-9)) / Math.log(2) - HIST_MIN) * HIST_PER_EV);
          hist[bin < 0 ? 0 : bin >= HIST_BINS ? HIST_BINS - 1 : bin]++;
          n++;
          if (clip) clipped++;
        }
      }
      return new double[] {Math.exp(sumL / Math.max(1e-9, sumW)), n == 0 ? 0 : clipped / (double) n};
    }

    /**
     * Face exposure: the EV added to the scene metering so that the metered face sits at {@link
     * #FACE_KEY} (limited), or 0 when it is off or no usable face was found. Records why in stats.
     */
    double faceExposureEv(double[] cam, double logAverage, double key, Options o, Map<String, Object> stats) {
      int count = f.faces == null ? 0 : f.faces.length / 4;
      if (!o.faceExposure) {
        stats.put("faceExposure", "Off");
        return 0;
      }
      int k = Faces.pick(f.faces, f.faceScores, W, H);
      if (k < 0) {
        stats.put(
            "faceExposure",
            count == 0
                ? "On: no face found"
                : "On: no usable face (" + count + " too small or low confidence)");
        return 0;
      }
      int l = Math.max(0, f.faces[4 * k]), t = Math.max(0, f.faces[4 * k + 1]);
      int r = Math.min(W, f.faces[4 * k + 2]), b = Math.min(H, f.faces[4 * k + 3]);
      double level = faceLevel(cam, l, t, r, b);
      if (!(level > 0)) {
        stats.put("faceExposure", "On: face too small to measure");
        return 0;
      }
      double ev =
          Math.log((FACE_KEY / level) / (METERING_KEY / Math.max(1e-6, logAverage))) / Math.log(2);
      double used = Math.max(-FACE_MAX_DARKEN_EV, Math.min(FACE_MAX_BRIGHTEN_EV, ev));
      stats.put("faceExposure", "On: face metered");
      stats.put("faceSource", f.faceSource);
      stats.put("faceCount", count);
      stats.put("faceRect", new int[] {l, t, r, b});
      if (f.faceScores != null && k < f.faceScores.length) stats.put("faceScore", f.faceScores[k]);
      stats.put("faceMeter", round(level, 6));
      stats.put("faceKey", round(FACE_KEY * key / METERING_KEY, 4));
      stats.put("faceExposureEv", round(used, 3));
      if (used != ev) stats.put("faceExposureWanted", round(ev, 3));
      return used;
    }

    /**
     * Scene-linear luminance (before the development gain, as {@link #meter}) at the {@link
     * #FACE_PERCENTILE} of a face's inner box: 20..80 % of its width, 15..75 % of its height.
     * Returns 0 if the box holds too few samples.
     */
    double faceLevel(double[] cam, int l, int t, int r, int b) {
      int w = r - l, h = b - t;
      int x0 = (l + (int) (0.2 * w)) & ~1, x1 = Math.min(W - 1, r - (int) (0.2 * w));
      int y0 = (t + (int) (0.15 * h)) & ~1, y1 = Math.min(H - 1, t + (int) (0.75 * h));
      if (x1 - x0 < 4 || y1 - y0 < 4) return 0;
      int step = Math.max(2, (Math.min(x1 - x0, y1 - y0) / 48) & ~1);
      double[] logs = new double[((x1 - x0) / step + 1) * ((y1 - y0) / step + 1)];
      int n = 0;
      float[] q = new float[3];
      float sat = f.white - 0.02f * (f.white - Math.min(Math.min(f.black[0], f.black[1]), Math.min(f.black[2], f.black[3])));
      for (int y = y0; y < y1; y += step) {
        for (int x = x0; x < x1; x += step) {
          q[0] = q[1] = q[2] = 0;
          boolean clip = false;
          float gShade = 1;
          for (int k = 0; k < 4; k++) {
            int xx = x + (k & 1), yy = y + (k >> 1);
            int raw = f.data[yy * f.rowStride + xx] & 0xffff;
            if (raw >= sat) clip = true;
            float s = shadingAt(shadeK[k], xx, yy);
            if (colorK[k] == 1) gShade = s;
            float v = (raw - black[k]) * scale[k] * s * gainK[k];
            q[colorK[k]] += colorK[k] == 1 ? 0.5f * v : v;
          }
          float c = gShade * clipFactor;
          if (clip) for (int i = 0; i < 3; i++) q[i] = Math.min(q[i], c);
          double R = cam[0] * q[0] + cam[1] * q[1] + cam[2] * q[2];
          double G = cam[3] * q[0] + cam[4] * q[1] + cam[5] * q[2];
          double B = cam[6] * q[0] + cam[7] * q[1] + cam[8] * q[2];
          logs[n++] = Math.log(Math.max(0.2627 * R + 0.6780 * G + 0.0593 * B, 1e-5));
        }
      }
      if (n < 16) return 0;
      Arrays.sort(logs, 0, n);
      return Math.exp(logs[(int) Math.round(FACE_PERCENTILE * (n - 1))]);
    }
  }

  /** Per-thread buffers and the strip pipeline. */
  static final class Worker {
    final Job j;
    final int W, Wp;
    final float[] cfa, cr, cg, cb, tu, tv, yb, aa, bb, sg, gRow, aRow, bRow, tmp;
    final int[] argb;
    final float[] lutOut = new float[3];
    long nsCfa, nsDemosaic, nsNoise, nsFinish;

    Worker(Job j, int maxStripRows) {
      this.j = j;
      W = j.W;
      Wp = W + 4;
      int rowsIn = maxStripRows + 2 * HALO + 4, rowsMid = maxStripRows + 2 * HALO;
      cfa = new float[rowsIn * Wp];
      cr = new float[rowsMid * W];
      cg = new float[rowsMid * W];
      cb = new float[rowsMid * W];
      tu = new float[rowsMid * W];
      tv = new float[rowsMid * W];
      yb = new float[rowsMid * W];
      aa = new float[rowsMid * W];
      bb = new float[rowsMid * W];
      sg = new float[rowsMid * W];
      gRow = new float[W];
      aRow = new float[W];
      bRow = new float[W];
      tmp = new float[Math.max(2, j.shading ? j.f.shadingColumns : 2)];
      argb = new int[maxStripRows * W];
    }

    int reflect(int y, int n) {
      if (y < 0) y = -y;
      if (y >= n) y = 2 * n - 2 - y;
      return Math.max(0, Math.min(n - 1, y));
    }

    void strip(int y0, int y1, Sink sink) {
      final int n = y1 - y0, rowsIn = n + 2 * HALO + 4, rowsMid = n + 2 * HALO;
      stripY0 = y0;
      long t0 = System.nanoTime();
      for (int r = 0; r < rowsIn; r++) cfaRow(r, reflect(y0 - HALO - 2 + r, j.bandH));
      long t1 = System.nanoTime();
      for (int r = 0; r < rowsMid; r++) demosaicRow(r, reflect(y0 - HALO + r, j.bandH));
      long t2 = System.nanoTime();
      if (j.firstVersion) {
        if (j.nrRadius > 0) chromaBlur(rowsMid, j.nrRadius);
        if (j.fvNoise) noise(rowsMid);
      } else noise(rowsMid);
      long t3 = System.nanoTime();
      for (int r = 0; r < n; r++) finishRow(r, j.bandY0 + y0 + r);
      long t4 = System.nanoTime();
      nsCfa += t1 - t0;
      nsDemosaic += t2 - t1;
      nsNoise += t3 - t2;
      nsFinish += t4 - t3;
      sink.rows(j.bandY0 + y0, n, argb, W);
    }

    /** 1) one CFA row: black, scale, shading, white balance. */
    void cfaRow(int r, int y) {
      final RawFrame f = j.f;
      int gy = j.bandY0 + y;
      int chEven = j.shadeK[(y & 1) << 1], chOdd = j.shadeK[((y & 1) << 1) | 1];
      j.shadingRow(chEven, gy, tmp, aRow);
      j.shadingRow(chOdd, gy, tmp, bRow);
      int k0 = (y & 1) << 1;
      float b0 = j.black[k0], b1 = j.black[k0 + 1];
      float s0 = j.scale[k0] * j.gainK[k0], s1 = j.scale[k0 + 1] * j.gainK[k0 + 1];
      int src = gy * f.rowStride, dst = r * Wp + 2;
      short[] data = f.data;
      float[] c = cfa, ar = aRow, br = bRow;
      for (int x = 0; x < W; x += 2) {
        c[dst + x] = ((data[src + x] & 0xffff) - b0) * s0 * ar[x];
        c[dst + x + 1] = ((data[src + x + 1] & 0xffff) - b1) * s1 * br[x + 1];
      }
      int row = r * Wp;
      c[row] = c[row + 4];
      c[row + 1] = c[row + 3];
      c[row + W + 2] = c[row + W];
      c[row + W + 3] = c[row + W - 1];
    }

    /** 2) demosaic + highlight handling + colour matrix + exposure + F-Log2/tone, one row of codes. */
    void demosaicRow(int r, int yy) {
      final float[] m = j.m, c = cfa;
      final float clipF = j.clipFactor;
      final ToneTable tone = j.tone;
      int cRow = (r + 2) * Wp + 2;
      int gch = j.shadeK[((yy & 1) << 1)] == 1 || j.shadeK[((yy & 1) << 1)] == 2
          ? j.shadeK[(yy & 1) << 1] : j.shadeK[((yy & 1) << 1) | 1];
      j.shadingRow(gch, j.bandY0 + yy, tmp, gRow);
      int out = r * W;
      int k0 = (yy & 1) << 1;
      int cEven = j.colorK[k0], cOdd = j.colorK[k0 | 1];
      final float[][] gq = j.glowQ;
      final float[] gR = gq == null ? null : gq[0], gG = gq == null ? null : gq[1], gB = gq == null ? null : gq[2];
      final float kR = j.glowKeep[0], kG = j.glowKeep[1], kB = j.glowKeep[2];
      final int[] qx0 = j.qx0;
      final float[] qfx = j.qfx;
      int q0 = 0, q1 = 0;
      float fy = 0;
      if (gq != null) {
        float v = (yy - 1.5f) * 0.25f;
        int y0q = (int) Math.floor(v);
        if (y0q < 0) {
          y0q = 0;
          fy = 0;
        } else if (y0q >= j.qH - 1) {
          y0q = j.qH - 2;
          fy = 1;
        } else fy = v - y0q;
        q0 = y0q * j.qW;
        q1 = q0 + j.qW;
      }
      for (int x = 0; x < W; x++) {
        int i = cRow + x;
        int col = (x & 1) == 0 ? cEven : cOdd;
        float p0 = c[i];
        float R, G, B;
        float n4 = c[i - 1] + c[i + 1] + c[i - Wp] + c[i + Wp];
        float far = c[i - 2] + c[i + 2] + c[i - 2 * Wp] + c[i + 2 * Wp];
        float diag = c[i - Wp - 1] + c[i - Wp + 1] + c[i + Wp - 1] + c[i + Wp + 1];
        if (col == 1) {
          G = p0;
          float h2 = c[i - 2] + c[i + 2], v2 = c[i - 2 * Wp] + c[i + 2 * Wp];
          float hv = (5 * p0 + 4 * (c[i - 1] + c[i + 1]) - diag - h2 + 0.5f * v2) * 0.125f;
          float vv = (5 * p0 + 4 * (c[i - Wp] + c[i + Wp]) - diag - v2 + 0.5f * h2) * 0.125f;
          int hc = (x & 1) == 0 ? cOdd : cEven;
          if (hc == 0) {
            R = hv;
            B = vv;
          } else {
            B = hv;
            R = vv;
          }
        } else {
          G = (4 * p0 + 2 * n4 - far) * 0.125f;
          float other = (6 * p0 + 2 * diag - 1.5f * far) * 0.125f;
          if (col == 0) {
            R = p0;
            B = other;
          } else {
            B = p0;
            R = other;
          }
        }
        // green-saturated highlights: pull R and B toward G so clipped areas stay neutral
        float t = gRow[x] * clipF;
        if (G > 0.9f * t) {
          float w = (G - 0.9f * t) / (0.08f * t);
          if (w > 1) w = 1;
          if (R > G) R += w * (G - R);
          if (B > G) B += w * (G - B);
        }
        float lr = m[0] * R + m[1] * G + m[2] * B;
        float lg = m[3] * R + m[4] * G + m[5] * B;
        float lb = m[6] * R + m[7] * G + m[8] * B;
        if (gq != null) {
          int i0 = q0 + qx0[x], i1 = q1 + qx0[x];
          float fx = qfx[x];
          float a0 = gR[i0] + fx * (gR[i0 + 1] - gR[i0]), a1 = gR[i1] + fx * (gR[i1 + 1] - gR[i1]);
          lr = kR * lr + a0 + fy * (a1 - a0);
          a0 = gG[i0] + fx * (gG[i0 + 1] - gG[i0]);
          a1 = gG[i1] + fx * (gG[i1 + 1] - gG[i1]);
          lg = kG * lg + a0 + fy * (a1 - a0);
          a0 = gB[i0] + fx * (gB[i0 + 1] - gB[i0]);
          a1 = gB[i1] + fx * (gB[i1 + 1] - gB[i1]);
          lb = kB * lb + a0 + fy * (a1 - a0);
        }
        cr[out + x] = tone.apply(lr);
        cg[out + x] = tone.apply(lg);
        cb[out + x] = tone.apply(lb);
      }
      System.arraycopy(gRow, 0, sg, out, W);
    }

    /** Horizontal box sums (radius rad, edge-replicated) of p and p*p, or of p and q. */
    void boxRows(float[] p, float[] q, boolean square, int r0, int r1, int rad, float[] op, float[] oq) {
      for (int r = r0; r < r1; r++) {
        int o = r * W;
        float s1 = 0, s2 = 0;
        for (int k = -rad; k <= rad; k++) {
          int x = o + Math.max(0, Math.min(W - 1, k));
          float a = p[x], b = square ? a * a : q[x];
          s1 += a;
          s2 += b;
        }
        for (int x = 0; x < W; x++) {
          op[o + x] = s1;
          oq[o + x] = s2;
          int xa = o + Math.min(W - 1, x + rad + 1), xr = o + Math.max(0, x - rad);
          float pa = p[xa], pr = p[xr];
          s1 += pa - pr;
          s2 += square ? pa * pa - pr * pr : q[xa] - q[xr];
        }
      }
    }

    /**
     * 3) noise reduction and sharpening in the log (code) domain. Pixel scale: a self-guided filter
     * (He et al.) whose epsilon follows the measured noise curve through the development gain, the
     * lens-shading gain and the tone curve, so flat areas are smoothed in proportion to the noise
     * actually present while edges and texture above the noise floor are kept; a share of the
     * residual is added back (recipe NR) and the denoised base is sharpened (recipe Sharpness).
     * Low frequencies: the quarter-resolution corrections (luma and chroma) are interpolated in.
     */
    void noise(int rowsMid) {
      final int R = GF_R, total = rowsMid * W;
      final float inv = 1f / ((2 * R + 1) * (2 * R + 1));
      final float[] Y = yb, h1 = tu, h2 = tv, nA = j.nrA, nB = j.nrB;
      final float epsF = j.epsFine;
      for (int i = 0; i < total; i++) Y[i] = 0.25f * (cr[i] + 2 * cg[i] + cb[i]);
      boxRows(Y, null, true, 0, rowsMid, R, h1, h2);
      for (int r = R; r < rowsMid - R; r++) {
        int o = r * W;
        for (int x = 0; x < W; x++) {
          float s1 = 0, s2 = 0;
          for (int k = o - R * W + x, e = o + R * W + x; k <= e; k += W) {
            s1 += h1[k];
            s2 += h2[k];
          }
          float mean = s1 * inv, var = s2 * inv - mean * mean;
          if (var < 0) var = 0;
          float sh = sg[o + x];
          float ti = mean * NR_TABLE;
          int t = ti <= 0 ? 0 : ti >= NR_TABLE ? NR_TABLE : (int) ti;
          float eps = epsF * (nA[t] * sh + nB[t] * sh * sh);
          float a = var / (var + eps + 1e-12f);
          aa[o + x] = a;
          bb[o + x] = (1 - a) * mean;
        }
      }
      boxRows(aa, bb, false, R, rowsMid - R, R, h1, h2);
      for (int r = 2 * R; r < rowsMid - 2 * R; r++) {
        int o = r * W;
        for (int x = 0; x < W; x++) {
          float s1 = 0, s2 = 0;
          for (int k = o - R * W + x, e = o + R * W + x; k <= e; k += W) {
            s1 += h1[k];
            s2 += h2[k];
          }
          aa[o + x] = s1 * inv * Y[o + x] + s2 * inv; // denoised base q
        }
      }
      final float lambda = j.sharpen, keep = j.nrKeep;
      final float[] bandQ = j.band;
      final float clarityK = j.clarityK;
      final float[] dYq = j.dY, pUq = j.pU, pVq = j.pV, kq = j.kq, qfx = j.qfx;
      final int[] qx0 = j.qx0;
      final int qW = j.qW;
      float[] dbgY = j.debugLuma, dbgE = j.debugNoise;
      for (int r = HALO; r < rowsMid - HALO; r++) {
        int o = r * W, up = o - W, dn = o + W;
        float v = (stripY0 + r - HALO - 1.5f) * 0.25f;
        int y0q = (int) Math.floor(v);
        float fy;
        if (y0q < 0) {
          y0q = 0;
          fy = 0;
        } else if (y0q >= j.qH - 1) {
          y0q = j.qH - 2;
          fy = 1;
        } else fy = v - y0q;
        int q0 = y0q * qW, q1 = q0 + qW;
        for (int x = 0; x < W; x++) {
          int xl = x == 0 ? 0 : x - 1, xr = x == W - 1 ? x : x + 1;
          float q = aa[o + x];
          float blur =
              (4 * q + 2 * (aa[o + xl] + aa[o + xr] + aa[up + x] + aa[dn + x])
                      + aa[up + xl] + aa[up + xr] + aa[dn + xl] + aa[dn + xr])
                  * (1f / 16f);
          float y = Y[o + x];
          int i0 = q0 + qx0[x], i1 = q1 + qx0[x];
          float fx = qfx[x];
          float t0 = dYq[i0] + fx * (dYq[i0 + 1] - dYq[i0]), t1 = dYq[i1] + fx * (dYq[i1 + 1] - dYq[i1]);
          float yo = q + lambda * (q - blur) + keep * (y - q) + t0 + fy * (t1 - t0);
          if (bandQ != null) {
            float b0 = bandQ[i0] + fx * (bandQ[i0 + 1] - bandQ[i0]);
            float b1 = bandQ[i1] + fx * (bandQ[i1 + 1] - bandQ[i1]);
            float wv = 4 * yo * (1 - yo);
            if (wv > 0) yo += clarityK * wv * (b0 + fy * (b1 - b0));
          }
          t0 = kq[i0] + fx * (kq[i0 + 1] - kq[i0]);
          t1 = kq[i1] + fx * (kq[i1 + 1] - kq[i1]);
          float kk = t0 + fy * (t1 - t0);
          t0 = pUq[i0] + fx * (pUq[i0 + 1] - pUq[i0]);
          t1 = pUq[i1] + fx * (pUq[i1 + 1] - pUq[i1]);
          float uo = kk * (cr[o + x] - y) + t0 + fy * (t1 - t0);
          t0 = pVq[i0] + fx * (pVq[i0 + 1] - pVq[i0]);
          t1 = pVq[i1] + fx * (pVq[i1 + 1] - pVq[i1]);
          float vo = kk * (cb[o + x] - y) + t0 + fy * (t1 - t0);
          float rr = yo + uo, bb2 = yo + vo;
          cr[o + x] = rr;
          cb[o + x] = bb2;
          cg[o + x] = (4 * yo - rr - bb2) * 0.5f;
        }
      }
      if (dbgY != null) {
        int yStart = stripY0;
        for (int r = HALO; r < rowsMid - HALO; r++) {
          int o = r * W, g = (yStart + r - HALO) * W;
          for (int x = 0; x < W; x++) {
            dbgY[g + x] = Y[o + x];
            float ti = Y[o + x] * NR_TABLE;
            int t = ti <= 0 ? 0 : ti >= NR_TABLE ? NR_TABLE : (int) ti;
            float sh = sg[o + x];
            dbgE[g + x] = nA[t] * sh + nB[t] * sh * sh;
          }
        }
      }
    }

    int stripY0;

    /**
     * First version (v0.2.0), unchanged: box-blur the opponent codes (r - Y, b - Y), Y = (r + 2g +
     * b) / 4 kept, over the strip's own rows (rad <= HALO).
     */
    void chromaBlur(int rowsMid, int rad) {
      float inv = 1f / (2 * rad + 1);
      for (int r = 0; r < rowsMid; r++) {
        int o = r * W;
        float su = 0, sv = 0;
        for (int k = -rad; k <= rad; k++) {
          int x = Math.max(0, Math.min(W - 1, k));
          float Y = 0.25f * (cr[o + x] + 2 * cg[o + x] + cb[o + x]);
          su += cr[o + x] - Y;
          sv += cb[o + x] - Y;
        }
        for (int x = 0; x < W; x++) {
          tu[o + x] = su * inv;
          tv[o + x] = sv * inv;
          int xa = Math.min(W - 1, x + rad + 1), xr = Math.max(0, x - rad);
          float Ya = 0.25f * (cr[o + xa] + 2 * cg[o + xa] + cb[o + xa]);
          float Yr = 0.25f * (cr[o + xr] + 2 * cg[o + xr] + cb[o + xr]);
          su += (cr[o + xa] - Ya) - (cr[o + xr] - Yr);
          sv += (cb[o + xa] - Ya) - (cb[o + xr] - Yr);
        }
      }
      for (int r = HALO; r < rowsMid - HALO; r++) {
        int o = r * W;
        for (int x = 0; x < W; x++) {
          float su = 0, sv = 0;
          for (int k = -rad; k <= rad; k++) {
            int q = (r + k) * W + x;
            su += tu[q];
            sv += tv[q];
          }
          float Y = 0.25f * (cr[o + x] + 2 * cg[o + x] + cb[o + x]);
          float rr = Y + su * inv, bb2 = Y + sv * inv;
          cr[o + x] = rr;
          cb[o + x] = bb2;
          cg[o + x] = (4 * Y - rr - bb2) * 0.5f;
        }
      }
    }

    /** 4) LUT, colour effects, grain -> ARGB for one output row. */
    void finishRow(int r, int y) {
      final float amp = j.grainAmp;
      final float[] ga = j.grainA, gb = j.grainB;
      final boolean post = j.post, cce = j.cceDepth > 0, mono = j.monoFilter;
      final boolean fv = j.firstVersion;
      final int sp = j.sensorProfile, rColor = j.recipeColor, rCce = j.recipeCce;
      final float chroma = j.chroma, cceDepth = j.cceDepth, blueDepth = j.blueDepth;
      int src = (r + HALO) * W, dst = r * W;
      int rowA = ((y + j.grainOy) & 1023) << 10, rowB = j.mod997[y + j.grainOy] * 997;
      int ox = j.grainOx;
      for (int x = 0; x < W; x++) {
        j.lut.sample(cr[src + x], cg[src + x], cb[src + x], lutOut);
        if (blueDepth > 0) fxBlue(lutOut, blueDepth); // both developments (v0.3.3)
        float vr = lutOut[0], vg = lutOut[1], vb = lutOut[2];
        if (mono) vr = vg = vb = j.filterMono(cr[src + x], cg[src + x], cb[src + x], vg);
        if (sp != PROFILE_NONE && !mono) {
          applySensorCalibration(lutOut, sp, rColor, rCce);
          vr = lutOut[0];
          vg = lutOut[1];
          vb = lutOut[2];
        } else if (post && fv) {
          // First version (v0.2.0), unchanged: Color Chrome Effect / Color in OKLab
          float lr = j.decode(vr), lg = j.decode(vg), lb = j.decode(vb);
          float l = (float) Math.cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb);
          float mm = (float) Math.cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb);
          float s = (float) Math.cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb);
          float L = 0.2104542553f * l + 0.7936177850f * mm - 0.0040720468f * s;
          float A = 1.9779984951f * l - 2.4285922050f * mm + 0.4505937099f * s;
          float Bb = 0.0259040371f * l + 0.7827717662f * mm - 0.8086757660f * s;
          if (cce) {
            float C = (float) Math.sqrt(A * A + Bb * Bb);
            float w = C <= FV_CCE_C0 ? 0 : C >= FV_CCE_C1 ? 1 : (C - FV_CCE_C0) / (FV_CCE_C1 - FV_CCE_C0);
            w = w * w * (3 - 2 * w);
            float dL = cceDepth * w;
            L -= dL * L;
          }
          A *= chroma;
          Bb *= chroma;
          float l2 = L + 0.3963377774f * A + 0.2158037573f * Bb;
          float m2 = L - 0.1055613458f * A - 0.0638541728f * Bb;
          float s2 = L - 0.0894841775f * A - 1.2914855480f * Bb;
          l2 = l2 * l2 * l2;
          m2 = m2 * m2 * m2;
          s2 = s2 * s2 * s2;
          vr = j.encode(4.0767416621f * l2 - 3.3077115913f * m2 + 0.2309699292f * s2);
          vg = j.encode(-1.2684380046f * l2 + 2.6097574011f * m2 - 0.3413193965f * s2);
          vb = j.encode(-0.0041960863f * l2 - 0.7034186147f * m2 + 1.7076147010f * s2);
        } else if (post) {
          // display-code opponent space (BT.709 luma): cheap stand-in for a perceptual space
          float Yd = 0.2126f * vr + 0.7152f * vg + 0.0722f * vb;
          float u = vb - Yd, v = vr - Yd;
          if (cce) {
            float C = (float) Math.sqrt(u * u + v * v);
            float w = C <= CCE_C0 ? 0 : C >= CCE_C1 ? 1 : (C - CCE_C0) / (CCE_C1 - CCE_C0);
            w = w * w * (3 - 2 * w);
            float dL = cceDepth * w;
            Yd -= dL * Yd;
          }
          u *= chroma;
          v *= chroma;
          vr = Yd + v;
          vb = Yd + u;
          vg = (Yd - 0.2126f * vr - 0.0722f * vb) * (1f / 0.7152f);
        }
        int xx = x + ox;
        float nz = (ga[rowA + (xx & 1023)] + gb[rowB + j.mod997[xx]]) * 0.70710678f;
        float d = nz * (0.5f / 255f);
        if (amp > 0) {
          float lum = 0.25f * (vr + 2 * vg + vb);
          float wl = 4 * lum * (1 - lum);
          if (wl < 0) wl = 0;
          d += amp * (float) Math.sqrt(wl) * nz;
        }
        int ir = (int) ((vr + d) * 255f + 0.5f), ig = (int) ((vg + d) * 255f + 0.5f), ib = (int) ((vb + d) * 255f + 0.5f);
        ir = ir < 0 ? 0 : ir > 255 ? 255 : ir;
        ig = ig < 0 ? 0 : ig > 255 ? 255 : ig;
        ib = ib < 0 ? 0 : ib > 255 ? 255 : ib;
        argb[dst + x] = 0xff000000 | (ir << 16) | (ig << 8) | ib;
      }
    }
  }

  /** FX Blue depth for a recipe level: Off 0, Weak half of Strong (as measured), Strong. */
  public static float fxBlueDepth(int level) {
    return level >= 2 ? FXB_STRONG : level == 1 ? FXB_STRONG / 2 : 0;
  }

  /**
   * Color Chrome FX Blue on one display-code RGB colour, in place (see FXB_STRONG): blues darken
   * and get relatively richer at the same hue; neutrals, skin, reds, yellows and greens are not
   * touched.
   */
  public static void fxBlue(float[] rgb, float depth) {
    float r = rgb[0], g = rgb[1], b = rgb[2];
    float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    float cu = b - y, cv = r - y;
    float c = (float) Math.sqrt(cu * cu + cv * cv);
    if (depth <= 0 || y <= 0 || c <= FXB_C0) return;
    float hb = (cu * BLUE_U + cv * BLUE_V) / c;
    if (hb <= 0) return;
    float t = c >= FXB_C1 ? 1 : (c - FXB_C0) / (FXB_C1 - FXB_C0);
    hb *= hb;
    float d = depth * t * t * (3 - 2 * t) * hb * hb * y;
    rgb[0] = r > d ? r - d : 0;
    rgb[1] = g > d ? g - d : 0;
    rgb[2] = b > d ? b - d : 0;
  }

  /** Fraction of histogram samples whose log2 luminance is above the threshold. */
  static double fractionAbove(double[] hist, double log2Threshold) {
    double total = 0, above = 0;
    int start = (int) Math.ceil((log2Threshold - HIST_MIN) * HIST_PER_EV);
    for (int i = 0; i < hist.length; i++) {
      total += hist[i];
      if (i >= start) above += hist[i];
    }
    return total == 0 ? 0 : above / total;
  }

  /** The code value the strip pipeline produces for scene-linear X (same clamp as the tone table). */
  static double toneCode(ToneModel.Curve curve, double x) {
    double v = curve.apply(FLog2.encode(x));
    return v < 0 ? 0 : v > 1 ? 1 : v;
  }

  /**
   * Noise of luma code c at lens-shading gain s is scale * (a[t] * s + b[t] * s^2), t = c * NR_TABLE:
   * shot (nS * x) and read (nO) noise of the RAW value x = X / (gain * s), carried through the
   * tone curve's slope dc/dlnX.
   */
  static void noiseTables(
      ToneModel.Curve curve, double gain, double nS, double nO, double scale, float[] a, float[] b) {
    final int ns = 2048;
    final double l0 = Math.log(1e-6), l1 = Math.log(64), dl = (l1 - l0) / (ns - 1);
    double[] cs = new double[ns];
    for (int i = 0; i < ns; i++) cs[i] = toneCode(curve, Math.exp(l0 + i * dl));
    int i = 1;
    for (int t = 0; t <= NR_TABLE; t++) {
      double c = t / (double) NR_TABLE;
      while (i < ns - 1 && cs[i] < c) i++;
      double c0 = cs[i - 1], c1 = cs[i];
      double fr = c1 > c0 ? Math.max(0, Math.min(1, (c - c0) / (c1 - c0))) : 0;
      double x = Math.exp(l0 + (i - 1 + fr) * dl);
      double slope = (c1 - c0) / dl, s2 = slope * slope;
      a[t] = (float) (scale * s2 * nS * gain / x);
      b[t] = (float) (scale * s2 * nO * gain * gain / (x * x));
    }
  }

  /**
   * Measures the RAW noise curve var = S * x + O (x = normalised, black-subtracted green) on the frame
   * itself: 8 x 8 blocks of same-colour horizontal differences on one green CFA phase, a low
   * quantile of the block variance per half-stop brightness bin (texture only adds variance), then
   * weighted least squares. Returns {S, O, bins} or null when too few clean bins exist.
   */
  public static double[] measureRawNoise(RawFrame f, int gk) {
    final int bw = 8, bh = 8, nb = 22;
    int gx = gk & 1, gy = gk >> 1;
    double black = f.black[gk], range = Math.max(1, f.white - black), sat = black + 0.98 * range;
    int cols = (f.width - gx + 1) / 2, rows = (f.height - gy + 1) / 2;
    int nbx = (cols - 1) / bw, nby = rows / bh;
    if (nbx < 1 || nby < 1) return null;
    float[] mus = new float[nbx * nby], vs = new float[nbx * nby];
    int[] bins = new int[nbx * nby], count = new int[nb];
    int n = 0;
    short[] data = f.data;
    final int m = bh * bw;
    for (int by = 0; by < nby; by++)
      for (int bx = 0; bx < nbx; bx++) {
        double sum = 0, sd = 0, sdd = 0;
        boolean clip = false;
        for (int i = 0; i < bh; i++) {
          int base = (gy + 2 * (by * bh + i)) * f.rowStride + gx + 2 * bx * bw;
          int prev = data[base] & 0xffff;
          sum += prev;
          for (int jj = 1; jj <= bw; jj++) {
            int v = data[base + 2 * jj] & 0xffff;
            if (v >= sat) clip = true;
            sum += v;
            double d = v - prev;
            sd += d;
            sdd += d * d;
            prev = v;
          }
        }
        if (clip) continue;
        double mu = (sum / (bh * (bw + 1)) - black) / range;
        if (!(mu > 0.0005)) continue;
        double var = (sdd - sd * sd / m) / (m - 1) / 2 / (range * range);
        int bin = (int) Math.floor((Math.log(mu) / Math.log(2) + 11) * 2);
        if (bin < 0 || bin >= nb) continue;
        mus[n] = (float) mu;
        vs[n] = (float) var;
        bins[n] = bin;
        count[bin]++;
        n++;
      }
    int[] start = new int[nb + 1];
    for (int b = 0; b < nb; b++) start[b + 1] = start[b] + count[b];
    float[] sv = new float[n], sm = new float[n];
    int[] fill = start.clone();
    for (int i = 0; i < n; i++) {
      int b = bins[i];
      sv[fill[b]] = vs[i];
      sm[fill[b]] = mus[i];
      fill[b]++;
    }
    double w0 = 0, wx = 0, wy = 0, wxx = 0, wxy = 0, lo = 99, hi = -99;
    int used = 0;
    for (int b = 0; b < nb; b++) {
      int c = count[b];
      if (c < 24) continue;
      java.util.Arrays.sort(sv, start[b], start[b + 1]);
      java.util.Arrays.sort(sm, start[b], start[b + 1]);
      // 25th percentile of a chi-square with 63 dof is ~0.88 of its mean
      double y = sv[start[b] + c / 4] / 0.88, x = sm[start[b] + c / 2];
      if (!(y > 0)) continue;
      double w = c / (y * y);
      w0 += w;
      wx += w * x;
      wy += w * y;
      wxx += w * x * x;
      wxy += w * x * y;
      lo = Math.min(lo, b);
      hi = Math.max(hi, b);
      used++;
    }
    if (used < 3 || hi - lo < 3) return null;
    double det = w0 * wxx - wx * wx;
    double sS = det > 0 ? (w0 * wxy - wx * wy) / det : 0, sO = det > 0 ? (wxx * wy - wx * wxy) / det : 0;
    if (sO < 0) {
      sO = 0;
      sS = wxy / wxx;
    }
    if (!(sS > 0)) return null;
    return new double[] {sS, sO, used};
  }

  /** Predicted luma-code noise sigma at scene mid grey (X = 0.18) on the optical axis. */
  static double midGreyNoise(ToneModel.Curve curve, double gain, double nS, double nO) {
    double x = 0.18, d = 0.05;
    double slope = (toneCode(curve, x * Math.exp(d)) - toneCode(curve, x * Math.exp(-d))) / (2 * d);
    return Math.abs(slope) * Math.sqrt(nS * gain / x + nO * gain * gain / (x * x));
  }

  private static float cachedSigma = -1;
  private static float[] cachedA, cachedB;

  /** Grain textures are seed-independent (per photo they are offset), so they are built once. */
  static synchronized float[][] grainTextures(float sigma) {
    if (cachedSigma != sigma || cachedA == null) {
      cachedA = noise(1024, sigma, 1);
      cachedB = noise(997, sigma, 38);
      cachedSigma = sigma;
    }
    return new float[][] {cachedA, cachedB};
  }

  /** Tileable unit-variance Gaussian noise, blurred to the requested grain size. */
  static float[] noise(int size, float sigma, long seed) {
    float[] a = new float[size * size];
    long s = seed * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
    for (int i = 0; i < a.length; i += 2) {
      s ^= s << 13;
      s ^= s >>> 7;
      s ^= s << 17;
      double u1 = ((s >>> 11) + 1) * 0x1.0p-53;
      s ^= s << 13;
      s ^= s >>> 7;
      s ^= s << 17;
      double u2 = (s >>> 11) * 0x1.0p-53;
      double rr = Math.sqrt(-2 * Math.log(u1));
      a[i] = (float) (rr * Math.cos(2 * Math.PI * u2));
      if (i + 1 < a.length) a[i + 1] = (float) (rr * Math.sin(2 * Math.PI * u2));
    }
    int rad = Math.max(1, (int) Math.ceil(sigma * 3));
    float[] k = new float[2 * rad + 1];
    float ks = 0;
    for (int i = -rad; i <= rad; i++) ks += k[i + rad] = (float) Math.exp(-(i * i) / (2.0 * sigma * sigma));
    for (int i = 0; i < k.length; i++) k[i] /= ks;
    float[] b = new float[a.length];
    for (int y = 0; y < size; y++)
      for (int x = 0; x < size; x++) {
        float v = 0;
        for (int i = -rad; i <= rad; i++) v += k[i + rad] * a[y * size + Math.floorMod(x + i, size)];
        b[y * size + x] = v;
      }
    double sum = 0, sq = 0;
    for (int y = 0; y < size; y++)
      for (int x = 0; x < size; x++) {
        float v = 0;
        for (int i = -rad; i <= rad; i++) v += k[i + rad] * b[Math.floorMod(y + i, size) * size + x];
        a[y * size + x] = v;
        sum += v;
        sq += (double) v * v;
      }
    double mean = sum / a.length, sd = Math.sqrt(Math.max(1e-12, sq / a.length - mean * mean));
    for (int i = 0; i < a.length; i++) a[i] = (float) ((a[i] - mean) / sd);
    return a;
  }

  /** Radius of three box passes whose combined Gaussian has the given sigma (at least 1). */
  /**
   * Channel weights of a black-and-white LUT, probed around grey in scene-linear F-Gamut: the change
   * of its output per relative change of each input channel at 9 %, 18 % and 36 % grey. Falls back to
   * BT.2020 luminance weights for a LUT that does not respond like a monochrome mix.
   */
  static double[] monoWeights(CubeLut lut) {
    double[] w = new double[3];
    float[] out = new float[3];
    double eps = 0.25;
    for (double grey : new double[] {0.09, 0.18, 0.36})
      for (int c = 0; c < 3; c++) {
        double[] lo = {grey, grey, grey}, hi = {grey, grey, grey};
        lo[c] = grey / (1 + eps);
        hi[c] = grey * (1 + eps);
        lut.sample((float) FLog2.encode(hi[0]), (float) FLog2.encode(hi[1]), (float) FLog2.encode(hi[2]), out);
        double up = out[1];
        lut.sample((float) FLog2.encode(lo[0]), (float) FLog2.encode(lo[1]), (float) FLog2.encode(lo[2]), out);
        w[c] += up - out[1];
      }
    double sum = w[0] + w[1] + w[2];
    if (!(w[0] > 0 && w[1] > 0 && w[2] >= 0 && sum > 0)) return new double[] {0.2627, 0.6780, 0.0593};
    return new double[] {w[0] / sum, w[1] / sum, w[2] / sum};
  }

  static int boxRadius(double sigma) {
    return Math.max(1, (int) Math.round((-1 + Math.sqrt(1 + 4 * sigma * sigma)) / 2));
  }

  static double round(double v, int digits) {
    double p = Math.pow(10, digits);
    return Math.round(v * p) / p;
  }

  static double[] roundAll(double[] v, int digits) {
    double[] o = new double[v.length];
    for (int i = 0; i < v.length; i++) o[i] = round(v[i], digits);
    return o;
  }
}
