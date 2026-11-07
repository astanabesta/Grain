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
    apply(argb, lut, null);
  }

  /** Converts packed ARGB pixels in place, applying sensor-calibrated recipe character when present. */
  public static void apply(int[] argb, CubeLut lut, FujiRecipe recipe) {
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
    int sp = RawDeveloper.sensorProfileOf(recipe);
    int steps =
        recipe == null
            ? 0
            : recipe.dynamicRange == FujiRecipe.DynamicRange.DR400
                ? 2
                : recipe.dynamicRange == FujiRecipe.DynamicRange.DR200 ? 1 : 0;
    ToneModel.Curve curve =
        sp != RawDeveloper.PROFILE_NONE
            ? ToneModel.calibratedCurve(steps, recipe.highlight, recipe.shadow, true, 1.0, recipe.key)
            : null;
    float wbR = sp == RawDeveloper.PROFILE_KODAK_800_V3 ? 1.07f : sp == RawDeveloper.PROFILE_LEICA_X ? 1.03f : 1f;
    float wbB = sp == RawDeveloper.PROFILE_KODAK_800_V3 ? 0.90f : sp == RawDeveloper.PROFILE_LEICA_X ? 0.95f : 1f;
    float blueDepth = sp != RawDeveloper.PROFILE_NONE ? RawDeveloper.fxBlueDepth(recipe.colorChromeBlue) : 0f;
    float[] m = REC709_TO_REC2020, out = new float[3];
    float g = (float) gain;
    for (int i = 0; i < argb.length; i++) {
      int p = argb[i];
      float r = lin[(p >> 16) & 255] * g * wbR;
      float gg = lin[(p >> 8) & 255] * g;
      float b = lin[p & 255] * g * wbB;
      float r2 = m[0] * r + m[1] * gg + m[2] * b;
      float g2 = m[3] * r + m[4] * gg + m[5] * b;
      float b2 = m[6] * r + m[7] * gg + m[8] * b;
      float cr = encode(r2), cg = encode(g2), cb = encode(b2);
      if (curve != null) {
        cr = clamp01((float) curve.apply(cr));
        cg = clamp01((float) curve.apply(cg));
        cb = clamp01((float) curve.apply(cb));
      }
      lut.sample(cr, cg, cb, out);
      if (blueDepth > 0f) RawDeveloper.fxBlue(out, blueDepth);
      if (sp != RawDeveloper.PROFILE_NONE)
        RawDeveloper.applySensorCalibration(out, sp, recipe.color, recipe.colorChrome);
      argb[i] = (p & 0xff000000) | (byte8(out[0]) << 16) | (byte8(out[1]) << 8) | byte8(out[2]);
    }
  }

  private static float clamp01(float v) {
    return v < 0f ? 0f : v > 1f ? 1f : v;
  }

  private static float encode(float v) {
    return (float) FLog2.encode(Math.max(0, v));
  }

  private static int byte8(float v) {
    int c = Math.round(v * 255f);
    return c < 0 ? 0 : c > 255 ? 255 : c;
  }
}
