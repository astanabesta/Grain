package dev.grain.photo.develop;

/**
 * Applies a colour LUT (or a built-in iPhone 16 Pro Smart HDR 5 Photographic Style) to a small
 * sRGB bitmap so the film sheet can show what each film does to a reference picture.
 */
public final class LutPreview {
  private LutPreview() {}

  public static void apply(int[] argb, CubeLut lut) {
    apply(argb, lut, null);
  }

  public static void apply(int[] argb, CubeLut lut, FujiRecipe recipe) {
    AppleRender.Style appleStyle =
        recipe != null ? AppleRender.Style.fromSimulation(recipe.filmSimulation) : null;
    if (appleStyle != null) {
      applyApplePreview(argb, appleStyle, recipe);
      return;
    }
    if (lut == null) return;

    int sp = RawDeveloper.sensorProfileOf(recipe);
    ToneModel.Curve tone =
        sp != RawDeveloper.PROFILE_NONE
            ? ToneModel.calibratedCurve(
                recipe.dynamicRange == FujiRecipe.DynamicRange.DR400 ? 2 : 0,
                recipe.highlight,
                recipe.shadow,
                true,
                1,
                recipe.key)
            : ToneModel.curve(0, 0, 0, true, 1, lut.input == CubeLut.Input.FLOG2_FGAMUT);
    double rWb = 1.0, bWb = 1.0;
    if (sp == RawDeveloper.PROFILE_KODAK_800_V3) {
      rWb = 1.07;
      bWb = 0.89;
    } else if (sp == RawDeveloper.PROFILE_LEICA_X) {
      rWb = 1.03;
      bWb = 0.94;
    } else if (sp == RawDeveloper.PROFILE_CINESTILL_800T) {
      rWb = 0.86;
      bWb = 1.18;
    }
    float blueDepth = recipe != null ? RawDeveloper.fxBlueDepth(recipe.colorChromeBlue) : 0f;
    float[] out = new float[3];
    for (int i = 0; i < argb.length; i++) {
      int p = argb[i];
      double r = srgbLinear(((p >> 16) & 255) / 255.0) * rWb;
      double g = srgbLinear(((p >> 8) & 255) / 255.0);
      double b = srgbLinear((p & 255) / 255.0) * bWb;
      double r2 = (0.627404 * r + 0.329283 * g + 0.043313 * b) * RawDeveloper.METERING_KEY / 0.18;
      double g2 = (0.069097 * r + 0.919540 * g + 0.011362 * b) * RawDeveloper.METERING_KEY / 0.18;
      double b2 = (0.016391 * r + 0.088013 * g + 0.895595 * b) * RawDeveloper.METERING_KEY / 0.18;
      lut.sample(code(tone, r2), code(tone, g2), code(tone, b2), out);
      if (blueDepth > 0) RawDeveloper.fxBlue(out, blueDepth);
      if (sp != RawDeveloper.PROFILE_NONE) {
        RawDeveloper.applySensorCalibration(out, sp, recipe.color, recipe.colorChrome);
      }
      argb[i] = 0xff000000 | (u8(out[0]) << 16) | (u8(out[1]) << 8) | u8(out[2]);
    }
  }

  private static void applyApplePreview(
      int[] argb, AppleRender.Style style, FujiRecipe recipe) {
    AppleRender.ToneTable table =
        new AppleRender.ToneTable(style, 1.35, recipe.highlight, recipe.shadow);
    float[] m = AppleRender.REC709_TO_P3;
    float scale = (float) (AppleRender.METERING_KEY / 0.18);
    float rWb = (float) style.wbRedMul, bWb = (float) style.wbBlueMul;
    float[] out = new float[3];
    float invKey = (float) (1.0 / AppleRender.METERING_KEY);
    float invLn2 = (float) (1.0 / Math.log(2.0));
    for (int i = 0; i < argb.length; i++) {
      int p = argb[i];
      float r = (float) srgbLinear(((p >> 16) & 255) / 255.0) * rWb * scale;
      float g = (float) srgbLinear(((p >> 8) & 255) / 255.0) * scale;
      float b = (float) srgbLinear((p & 255) / 255.0) * bWb * scale;
      float pr = m[0] * r + m[1] * g + m[2] * b;
      float pg = m[3] * r + m[4] * g + m[5] * b;
      float pb = m[6] * r + m[7] * g + m[8] * b;
      float norm = AppleRender.sceneNormDisplayP3(pr, pg, pb);
      float envEv = 0.55f * ((float) Math.log(Math.max(1e-5f, norm) * invKey) * invLn2);
      float gLtm =
          (float) Math.pow(2.0, AppleRender.localToneDeltaEv(envEv, style, recipe.highlight, recipe.shadow));
      AppleRender.applyRgbTone(pr * gLtm, pg * gLtm, pb * gLtm, table, out);
      AppleRender.gradeDisplayP3(out, style, recipe.color, false);
      argb[i] = 0xff000000 | (u8(out[0]) << 16) | (u8(out[1]) << 8) | u8(out[2]);
    }
  }

  private static float code(ToneModel.Curve tone, double linear) {
    return (float) tone.apply(FLog2.encode(linear));
  }

  private static double srgbLinear(double c) {
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  private static int u8(float v) {
    int n = Math.round(v * 255f);
    return n < 0 ? 0 : Math.min(255, n);
  }
}
