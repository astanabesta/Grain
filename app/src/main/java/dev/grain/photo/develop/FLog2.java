package dev.grain.photo.develop;

/**
 * Fujifilm F-Log2 transfer function, from the F-Log2 Data Sheet Ver.1.1 published by Fujifilm.
 * Scene-linear reflectance in (0.18 = 18 % grey), code value out (0..1).
 */
public final class FLog2 {
  public static final double A = 5.555556,
      B = 0.064829,
      C = 0.245281,
      D = 0.384316,
      E = 8.799461,
      F = 0.092864,
      CUT1 = 0.000889,
      CUT2 = 0.100686685370811;

  private FLog2() {}

  public static double encode(double in) {
    return in >= CUT1 ? C * Math.log10(A * in + B) + D : E * in + F;
  }

  public static double decode(double out) {
    return out >= CUT2 ? Math.pow(10.0, (out - D) / C) / A - B / A : (out - F) / E;
  }
}
