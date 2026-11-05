package dev.grain.photo.develop;

import java.util.function.DoubleUnaryOperator;

/**
 * Fast table for a smooth 1-D function of scene-linear light, here F-Log2 encoding followed by the
 * recipe tone offsets. Below 2^-12 the table is linearly spaced (this covers F-Log2's linear segment
 * and small negative noise values); above it the index comes from the float exponent and the top 8
 * mantissa bits, i.e. 256 linear segments per stop up to 2^7. Interpolation error is about 2e-7.
 */
public final class ToneTable {
  static final float XA0 = -0.0106f, XA1 = 1f / 4096f;
  static final int NA = 1024, E_MIN = -12, E_MAX = 6;
  static final int NB = (E_MAX - E_MIN + 1) * 256 + 1;
  private final float[] a = new float[NA + 1], b = new float[NB];

  public ToneTable(DoubleUnaryOperator f) {
    for (int i = 0; i <= NA; i++) a[i] = (float) f.applyAsDouble(XA0 + (XA1 - XA0) * (double) i / NA);
    for (int i = 0; i < NB; i++) {
      int e = E_MIN + i / 256, j = i % 256;
      b[i] = (float) f.applyAsDouble(Math.scalb(1.0 + j / 256.0, e));
    }
  }

  public float apply(float x) {
    if (x < XA1) {
      if (!(x > XA0)) return a[0];
      float p = (x - XA0) * (NA / (XA1 - XA0));
      int i = (int) p;
      if (i >= NA) i = NA - 1;
      float t = p - i;
      return a[i] + t * (a[i + 1] - a[i]);
    }
    int bits = Float.floatToRawIntBits(x);
    int e = ((bits >>> 23) & 0xff) - 127;
    if (e > E_MAX) return b[NB - 1];
    int m = bits & 0x7fffff;
    int i = (e - E_MIN) * 256 + (m >>> 15);
    float t = (m & 0x7fff) * (1f / 32768f);
    return b[i] + t * (b[i + 1] - b[i]);
  }
}
