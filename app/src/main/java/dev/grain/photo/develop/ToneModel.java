package dev.grain.photo.develop;

/**
 * Still-photo tone behaviour that Fujifilm's F-Log2 video LUTs do not contain, as offsets added to
 * the F-Log2 code value before the LUT. Fitted on public Fujifilm RAF + in-camera JPEG pairs
 * (fuji-on-redmi/fit_stills_model2.py: censored least squares, per-image exposure and WB free,
 * leave-one-out checked):
 *
 * <ul>
 *   <li>DR100 base: piecewise linear on knots 1 EV apart around 18 % grey, fitted on 11 default
 *       shots. Main effect: a much shorter highlight shoulder than the video LUT (stills reach white
 *       about 4 EV over grey instead of 6+) and slightly lifted shadows. Leave-one-out mean dE2000
 *       2.01 to 1.96 in unclipped areas (with the unconstrained deep-shadow knots).
 *   <li>Highlight / Shadow tone: one gain per menu step on fixed smooth shapes, fitted on 7 tweaked
 *       shots; consistent across leave-one-out folds (H -0.011..-0.016, S +0.003..+0.011). Note the
 *       data says a negative Highlight value brightens the top of the range relative to DR100.
 *   <li>DR200: small fitted modifier (weakly determined); DR400 assumes twice DR200 (not measured).
 *   <li>Color: OKLab chroma scale per step; weakly determined (folds -0.05..+0.04, joint +0.011).
 *   <li>Shoulder remap: engineering rule, not a Fujifilm measurement. When the phone RAW clips
 *       below the curve's white point, the shoulder is expanded so clipped areas still reach white
 *       instead of flat grey.
 * </ul>
 *
 * <p>Sony's s709 (v0.3.5) uses neither the fitted Fujifilm offsets nor the DR200 modifier: its
 * curve is Sony's LUT itself. Only the shoulder remap (s709 reaches white about 6 EV over grey,
 * the phone's RAW usually clips 2-3.5 EV over grey) and the Highlight / Shadow tuning apply.
 */
public final class ToneModel {
  public static final String VERSION = "stills-v2";
  public static final int KNOT_MIN_EV = -8, KNOTS = 16;
  public static final double KNEE_EV = 1.0;
  static final double[] KX = new double[KNOTS];

  /**
   * Fitted knots from -4 EV up. Below -4 EV the fit was not monotonic (deep-shadow noise, flare and
   * black-level differences dominate: fitted -8..-5 EV = .076 .110 .096 .056), so those knots hold
   * the -4 EV value: the official LUT's shadow separation is kept, with the fitted small lift.
   */
  static final double[] BASE_DR100 = {
    0.0147, 0.0147, 0.0147, 0.0147, 0.0147, 0.0039, 0.0000, -0.0037,
    0.0000, 0.0018, 0.0088, 0.0282, 0.1326, 0.2345, 0.3357, 0.4369
  };
  public static final double HIGHLIGHT_PER_STEP = -0.01446,
      SHADOW_PER_STEP = 0.00823,
      DR200_MODIFIER = -0.02247,
      COLOR_KAPPA = 0.01057;

  static {
    for (int i = 0; i < KNOTS; i++) KX[i] = FLog2.encode(0.18 * Math.pow(2, KNOT_MIN_EV + i));
  }

  private ToneModel() {}

  static double smoothstep(double a, double b, double x) {
    double t = (x - a) / (b - a);
    t = t < 0 ? 0 : t > 1 ? 1 : t;
    return t * t * (3 - 2 * t);
  }

  static double base(double x) {
    if (x <= KX[0]) return BASE_DR100[0];
    if (x >= KX[KNOTS - 1]) return BASE_DR100[KNOTS - 1];
    int i = 0;
    while (x > KX[i + 1]) i++;
    double t = (x - KX[i]) / (KX[i + 1] - KX[i]);
    return BASE_DR100[i] + t * (BASE_DR100[i + 1] - BASE_DR100[i]);
  }

  static double ev(double x) {
    return Math.log(Math.max(1e-6, FLog2.decode(x)) / 0.18) / Math.log(2);
  }

  public static final class Curve {
    public final int drSteps;
    public final double highlight, shadow, shoulderScale;
    /** stills: Grain's stills tone at all (false = the LUT's own tone, a test hook). */
    public final boolean stills;
    /** The fitted Fujifilm DR100 offsets (false for Sony s709: shoulder and tuning only). */
    public final boolean fujifilm;
    /** Optional phone-sensor-to-simulation tone calibration profile ("" for baseline recipes). */
    public final String profile;

    Curve(int drSteps, double highlight, double shadow, boolean stills, double shoulderScale,
        boolean fujifilm) {
      this(drSteps, highlight, shadow, stills, shoulderScale, fujifilm, "");
    }

    Curve(int drSteps, double highlight, double shadow, boolean stills, double shoulderScale,
        boolean fujifilm, String profile) {
      this.drSteps = drSteps;
      this.highlight = highlight;
      this.shadow = shadow;
      this.stills = stills;
      this.shoulderScale = shoulderScale;
      this.fujifilm = fujifilm;
      this.profile = profile == null ? "" : profile;
    }

    /** F-Log2 code in, LUT input code out. */
    public double apply(double x) {
      if (!stills) return x;
      double e = ev(x);
      if (shoulderScale != 1 && e > KNEE_EV) {
        e = KNEE_EV + (e - KNEE_EV) * shoulderScale;
        x = FLog2.encode(0.18 * Math.pow(2, e));
      }
      double d = fujifilm ? base(x) : 0;
      if ("kodak-800-v3".equals(profile)) {
        d += highlight * 0.0165 * smoothstep(-0.1, 3.0, e);
        d -= drSteps * 0.0110 * smoothstep(0.6, 3.8, e);
        d -= shadow * 0.0140 * smoothstep(0.15, 3.0, -e);
        d += 0.0080 * smoothstep(0.2, 2.2, -e) * (1.0 - smoothstep(3.2, 5.0, -e));
        return x + d;
      }
      if ("leica-x".equals(profile)) {
        d += highlight * 0.0160 * smoothstep(0.0, 2.8, e);
        d -= drSteps * 0.0080 * smoothstep(0.6, 3.6, e);
        d += 0.0075 * smoothstep(-0.3, 1.3, e) * (1.0 - smoothstep(1.5, 3.2, e));
        d -= 0.0105 * smoothstep(0.5, 3.4, -e);
        d -= shadow * 0.0130 * smoothstep(0.2, 3.0, -e);
        return x + d;
      }
      d += highlight * HIGHLIGHT_PER_STEP * smoothstep(0, 2.5, e);
      d -= shadow * SHADOW_PER_STEP * smoothstep(0, 3, -e);
      d -= drSteps * DR200_MODIFIER * smoothstep(0.5, 4, e);
      return x + d;
    }

    /** Total offset at each knot, for receipts. */
    public double[] knots() {
      double[] o = new double[KNOTS];
      for (int i = 0; i < KNOTS; i++) o[i] = Math.round((apply(KX[i]) - KX[i]) * 1e4) / 1e4;
      return o;
    }
  }

  public static Curve curve(int drSteps, double highlight, double shadow, boolean stills, double shoulderScale) {
    return new Curve(drSteps, highlight, shadow, stills, shoulderScale, true);
  }

  /** As {@link #curve(int, double, double, boolean, double)}; fujifilm = false for Sony s709. */
  public static Curve curve(
      int drSteps, double highlight, double shadow, boolean stills, double shoulderScale, boolean fujifilm) {
    return new Curve(drSteps, highlight, shadow, stills, shoulderScale, fujifilm);
  }

  /** Phone-sensor-calibrated stills curve for dedicated film/optical simulations. */
  public static Curve calibratedCurve(
      int drSteps, double highlight, double shadow, boolean stills, double shoulderScale, String profile) {
    return new Curve(drSteps, highlight, shadow, stills, shoulderScale, true, profile);
  }

  /** Scene EV (re 18 % grey) where the curve + LUT first renders a neutral at >= 0.985. */
  public static double whiteEv(Curve c, CubeLut lut) {
    float[] out = new float[3];
    for (double e = 0; e <= 9; e += 1 / 32.0) {
      float v = (float) Math.max(0, Math.min(1, c.apply(FLog2.encode(0.18 * Math.pow(2, e)))));
      lut.sample(v, v, v, out);
      if (out[1] >= 0.985f) return e;
    }
    return 9;
  }
}
