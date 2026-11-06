package dev.grain.photo.develop;

/**
 * Sony S-Log3 with S-Gamut3.Cine or S-Gamut3, as defined in Sony's "Technical Summary for
 * S-Gamut3.Cine/S-Log3 and S-Gamut3/S-Log3": the input that Sony's official s709 LUT expects.
 * Scene-linear reflectance in (0.18 = 18 % grey), full-range code value out (0..1; 18 % grey =
 * 420/1023, black = 95/1023).
 *
 * <p>Grain's scene-linear space is ITU-R BT.2020 = F-Gamut with a D65 white (see {@link ColorMath}).
 * Both S-Gamuts also use D65, so going to them is a plain 3x3 matrix with no white adaptation. The
 * matrices are built from Sony's published primaries; for S-Gamut3.Cine the result equals Sony's
 * published S-Gamut3.Cine to XYZ matrix (checked in the unit tests).
 */
public final class SLog3 {
  /** 18 % grey code value: 420 of 1023. */
  public static final double GREY_CODE = 420.0 / 1023.0;

  private static final double CUT_IN = 0.01125, CUT_CODE = 171.2102946929;

  private SLog3() {}

  public static double encode(double x) {
    return x >= CUT_IN
        ? (420.0 + Math.log10((x + 0.01) / (0.18 + 0.01)) * 261.5) / 1023.0
        : (x * (CUT_CODE - 95.0) / CUT_IN + 95.0) / 1023.0;
  }

  public static double decode(double y) {
    return y >= CUT_CODE / 1023.0
        ? Math.pow(10.0, (y * 1023.0 - 420.0) / 261.5) * (0.18 + 0.01) - 0.01
        : (y * 1023.0 - 95.0) * CUT_IN / (CUT_CODE - 95.0);
  }

  /** Sony's chromaticities (x, y) of the red, green and blue primaries. */
  static final double[] SGAMUT3_CINE_PRIMARIES = {0.766, 0.275, 0.225, 0.800, 0.089, -0.087};

  static final double[] SGAMUT3_PRIMARIES = {0.730, 0.280, 0.140, 0.855, 0.100, -0.050};
  /** D65 as chromaticity, as Sony and ITU-R BT.2020 define it. */
  static final double WHITE_X = 0.3127, WHITE_Y = 0.3290;

  /** Linear S-Gamut3.Cine / S-Gamut3 to CIE XYZ (D65), from the primaries. */
  public static final double[] SGAMUT3_CINE_TO_XYZ = toXyz(SGAMUT3_CINE_PRIMARIES),
      SGAMUT3_TO_XYZ = toXyz(SGAMUT3_PRIMARIES);

  /** Linear BT.2020 / F-Gamut to linear S-Gamut3.Cine / S-Gamut3 (all D65). Row-major. */
  public static final double[] REC2020_TO_SGAMUT3_CINE =
          ColorMath.mul(ColorMath.inv(SGAMUT3_CINE_TO_XYZ), ColorMath.inv(ColorMath.XYZ_TO_REC2020)),
      REC2020_TO_SGAMUT3 =
          ColorMath.mul(ColorMath.inv(SGAMUT3_TO_XYZ), ColorMath.inv(ColorMath.XYZ_TO_REC2020));

  /** Normalised primary matrix: RGB with R = G = B = 1 maps to the D65 white (Y = 1). */
  static double[] toXyz(double[] p) {
    double[] m = new double[9];
    for (int c = 0; c < 3; c++) {
      double x = p[2 * c], y = p[2 * c + 1];
      m[c] = x / y;
      m[3 + c] = 1;
      m[6 + c] = (1 - x - y) / y;
    }
    double[] white = {WHITE_X / WHITE_Y, 1, (1 - WHITE_X - WHITE_Y) / WHITE_Y};
    double[] s = ColorMath.mul(ColorMath.inv(m), white, true);
    for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) m[3 * r + c] *= s[c];
    return m;
  }
}
