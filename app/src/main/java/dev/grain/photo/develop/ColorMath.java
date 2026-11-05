package dev.grain.photo.develop;

/**
 * DNG-style colour calibration (Adobe DNG specification, "Mapping Camera Color Space to CIE XYZ
 * Space"), fed by the matrices the phone's camera HAL publishes with RAW captures: ColorMatrix
 * (Camera2 SENSOR_COLOR_TRANSFORM), ForwardMatrix (SENSOR_FORWARD_MATRIX), CameraCalibration
 * (SENSOR_CALIBRATION_TRANSFORM) for two reference illuminants, plus the per-shot as-shot neutral.
 *
 * <p>Output: one 3x3 matrix taking white-balanced camera RGB (camera value * neutralG / neutral) to
 * linear ITU-R BT.2020 / F-Gamut RGB with a D65 white, where a neutral surface maps to equal RGB.
 * All matrices are row-major double[9].
 */
public final class ColorMath {
  public static final double[] D50 = {0.96422, 1.0, 0.82521}, D65 = {0.95047, 1.0, 1.08883};
  public static final double[] XYZ_TO_REC2020 = {
    1.7166511880, -0.3556707838, -0.2533662814,
    -0.6666843518, 1.6164812366, 0.0157685458,
    0.0176398574, -0.0427706133, 0.9421031212
  };
  private static final double[] BRADFORD = {
    0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296
  };

  private ColorMath() {}

  public static final class Result {
    public final double[] cameraToRec2020;
    public final double cct, whiteX, whiteY, weight1;
    public final boolean usedForwardMatrix;

    Result(double[] m, double cct, double x, double y, double weight1, boolean fm) {
      cameraToRec2020 = m;
      this.cct = cct;
      whiteX = x;
      whiteY = y;
      this.weight1 = weight1;
      usedForwardMatrix = fm;
    }
  }

  public static double[] identity() {
    return new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
  }

  public static double[] mul(double[] a, double[] b) {
    double[] c = new double[9];
    for (int r = 0; r < 3; r++)
      for (int k = 0; k < 3; k++) {
        double s = 0;
        for (int j = 0; j < 3; j++) s += a[r * 3 + j] * b[j * 3 + k];
        c[r * 3 + k] = s;
      }
    return c;
  }

  public static double[] mul(double[] m, double[] v, boolean vector) {
    return new double[] {
      m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
      m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
      m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
    };
  }

  public static double[] diag(double a, double b, double c) {
    return new double[] {a, 0, 0, 0, b, 0, 0, 0, c};
  }

  public static double[] inv(double[] m) {
    double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    double det = a * A + b * B + c * C;
    if (Math.abs(det) < 1e-12) throw new IllegalArgumentException("Singular colour matrix");
    double s = 1.0 / det;
    return new double[] {
      A * s, -(b * i - c * h) * s, (b * f - c * e) * s,
      B * s, (a * i - c * g) * s, -(a * f - c * d) * s,
      C * s, -(a * h - b * g) * s, (a * e - b * d) * s
    };
  }

  static double[] lerp(double[] m1, double[] m2, double w1) {
    if (m1 == null) return m2;
    if (m2 == null) return m1;
    double[] o = new double[9];
    for (int k = 0; k < 9; k++) o[k] = w1 * m1[k] + (1 - w1) * m2[k];
    return o;
  }

  /** Bradford chromatic adaptation between two XYZ white points. */
  public static double[] bradford(double[] srcWhite, double[] dstWhite) {
    double[] s = mul(BRADFORD, srcWhite, true), d = mul(BRADFORD, dstWhite, true);
    return mul(inv(BRADFORD), mul(diag(d[0] / s[0], d[1] / s[1], d[2] / s[2]), BRADFORD));
  }

  /** Correlated colour temperature of an EXIF/DNG LightSource code (Adobe DNG SDK convention). */
  public static double illuminantCct(int code) {
    switch (code) {
      case 3: // tungsten
      case 17: // standard light A
        return 2850;
      case 24: // ISO studio tungsten
        return 3200;
      case 23: // D50
        return 5000;
      case 1: // daylight
      case 4: // flash
      case 9: // fine weather
      case 18: // standard light B
      case 20: // D55
        return 5500;
      case 10: // cloudy
      case 19: // standard light C
      case 21: // D65
        return 6500;
      case 11: // shade
      case 22: // D75
        return 7500;
      case 12: // daylight fluorescent
        return (5700 + 7100) * 0.5;
      case 13: // day white fluorescent
        return (4600 + 5500) * 0.5;
      case 2: // fluorescent
      case 14: // cool white fluorescent
        return (3800 + 4500) * 0.5;
      case 15: // white fluorescent
        return (3250 + 3800) * 0.5;
      case 16: // warm white fluorescent
        return (2600 + 3250) * 0.5;
      default:
        return 5000;
    }
  }

  /** McCamy's approximation, clamped to the range used for matrix interpolation. */
  public static double cctFromXy(double x, double y) {
    double n = (x - 0.3320) / (0.1858 - y);
    double t = 449 * n * n * n + 3525 * n * n + 6823.3 * n + 5520.33;
    return Math.max(1500, Math.min(15000, t));
  }

  static double weight1(double cct, double cct1, double cct2) {
    if (cct1 == cct2) return 1;
    double w = (1 / cct - 1 / cct2) / (1 / cct1 - 1 / cct2);
    return Math.max(0, Math.min(1, w));
  }

  /**
   * @param neutral as-shot neutral in camera native space (3 values, any scale)
   * @param cm1 XYZ->camera for illuminant 1 (required)
   * @param cm2 XYZ->camera for illuminant 2 (may be null)
   * @param fm1 forward matrix 1 (may be null)
   * @param fm2 forward matrix 2 (may be null)
   * @param cc1 camera calibration 1 (null = identity)
   * @param cc2 camera calibration 2 (null = identity)
   */
  public static Result cameraToRec2020(
      double[] neutral,
      double[] cm1,
      double[] cm2,
      double[] fm1,
      double[] fm2,
      double[] cc1,
      double[] cc2,
      int illuminant1,
      int illuminant2) {
    if (cm1 == null) throw new IllegalArgumentException("Colour matrix missing");
    if (cc1 == null) cc1 = identity();
    if (cc2 == null) cc2 = cc1;
    if (cm2 == null) cm2 = cm1;
    double t1 = illuminantCct(illuminant1), t2 = illuminantCct(illuminant2);
    double[] n = neutral.clone();
    double x = 0.3457, y = 0.3585, w1 = 1;
    for (int it = 0; it < 40; it++) {
      double cct = cctFromXy(x, y);
      w1 = weight1(cct, t1, t2);
      double[] xyzToCamera = mul(lerp(cc1, cc2, w1), lerp(cm1, cm2, w1));
      double[] xyz = mul(inv(xyzToCamera), n, true);
      double s = xyz[0] + xyz[1] + xyz[2];
      if (!(s > 0)) break;
      double nx = xyz[0] / s, ny = xyz[1] / s;
      boolean done = Math.abs(nx - x) < 1e-7 && Math.abs(ny - y) < 1e-7;
      x = nx;
      y = ny;
      if (done) break;
    }
    double cct = cctFromXy(x, y);
    w1 = weight1(cct, t1, t2);
    double[] cc = lerp(cc1, cc2, w1);
    double[] wbToCamera = diag(n[0] / n[1], 1, n[2] / n[1]); // undo our green-normalised WB
    double[] toXyzD50;
    boolean usedFm = fm1 != null;
    if (usedFm) {
      double[] fm = lerp(fm1, fm2 == null ? fm1 : fm2, w1);
      double[] ref = mul(inv(cc), n, true);
      double[] d = diag(1 / ref[0], 1 / ref[1], 1 / ref[2]);
      // DNG: CameraToXYZ_D50 = FM * D * Inverse(AB * CC), AB = identity on Android
      toXyzD50 = mul(fm, mul(d, mul(inv(cc), wbToCamera)));
      // FM maps (1,1,1) to the D50 white; scale so a neutral has Y = neutralG-normalised level
      double[] white = mul(toXyzD50, new double[] {1, 1, 1}, true);
      double k = 1 / white[1];
      for (int i = 0; i < 9; i++) toXyzD50[i] *= k;
      double[] cat = bradford(D50, D65);
      double[] m = mul(XYZ_TO_REC2020, mul(cat, toXyzD50));
      return new Result(normalise(m), cct, x, y, w1, true);
    }
    double[] xyzToCamera = mul(cc, lerp(cm1, cm2, w1));
    double[] camToXyz = mul(inv(xyzToCamera), wbToCamera);
    double[] white = mul(camToXyz, new double[] {1, 1, 1}, true);
    double k = 1 / white[1];
    for (int i = 0; i < 9; i++) camToXyz[i] *= k;
    double[] srcWhite = {white[0] * k, 1, white[2] * k};
    double[] m = mul(XYZ_TO_REC2020, mul(bradford(srcWhite, D65), camToXyz));
    return new Result(normalise(m), cct, x, y, w1, false);
  }

  /** Remove residual rounding so that camera (1,1,1) maps exactly to Rec.2020 (1,1,1). */
  static double[] normalise(double[] m) {
    double[] o = m.clone();
    for (int r = 0; r < 3; r++) {
      double s = o[r * 3] + o[r * 3 + 1] + o[r * 3 + 2];
      if (Math.abs(s) > 1e-9) for (int c = 0; c < 3; c++) o[r * 3 + c] /= s;
    }
    return o;
  }
}
