package dev.grain.photo.develop;

/**
 * Thumbnail-only preview of a film simulation: an sRGB reference picture is linearised, scaled so
 * its log-average luminance sits at mid-grey, moved to Rec.2020 / F-Gamut, F-Log2 encoded and run
 * through Fujifilm's official LUT. The reference is a finished JPEG, so this is only a guide to how
 * the simulations differ from each other. Photos are always developed from RAW.
 */
public final class LutPreview {
  private LutPreview() {}

  /** Linear BT.709 to linear BT.2020 (both D65). */
  static final float[] REC709_TO_REC2020 = {
    0.6274040f, 0.3292820f, 0.0433136f,
    0.0690970f, 0.9195400f, 0.0113612f,
    0.0163916f, 0.0880132f, 0.8955950f
  };

  /** Converts packed ARGB pixels in place. */
  public static void apply(int[] argb, CubeLut lut) {
    float[] lin = new float[256];
    for (int i = 0; i < 256; i++) {
      double c = i / 255.0;
      lin[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
    }
    double sumLog = 0;
    int n = 0;
    for (int i = 0; i < argb.length; i += 7) {
      int p = argb[i];
      double y =
          0.2126 * lin[(p >> 16) & 255] + 0.7152 * lin[(p >> 8) & 255] + 0.0722 * lin[p & 255];
      sumLog += Math.log(Math.max(1e-4, y));
      n++;
    }
    double gain = n == 0 ? 1 : 0.18 / Math.exp(sumLog / n);
    gain = Math.max(0.25, Math.min(8, gain));
    float[] m = REC709_TO_REC2020, out = new float[3];
    float g = (float) gain;
    for (int i = 0; i < argb.length; i++) {
      int p = argb[i];
      float r = lin[(p >> 16) & 255] * g, gg = lin[(p >> 8) & 255] * g, b = lin[p & 255] * g;
      float r2 = m[0] * r + m[1] * gg + m[2] * b;
      float g2 = m[3] * r + m[4] * gg + m[5] * b;
      float b2 = m[6] * r + m[7] * gg + m[8] * b;
      lut.sample(encode(r2), encode(g2), encode(b2), out);
      argb[i] = (p & 0xff000000) | (byte8(out[0]) << 16) | (byte8(out[1]) << 8) | byte8(out[2]);
    }
  }

  private static float encode(float v) {
    return (float) FLog2.encode(Math.max(0, v));
  }

  private static int byte8(float v) {
    int c = Math.round(v * 255f);
    return c < 0 ? 0 : c > 255 ? 255 : c;
  }
}
