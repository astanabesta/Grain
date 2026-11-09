package dev.grain.photo.develop;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Editor-style tuning for the built-in iPhone recipes, in the spirit of the gallery editor the
 * look was matched against: every slider runs -100..+100 with 0 = the published look.
 *
 * <p>Semantics follow the editor the profile was tuned with (Mi/HyperOS gallery + Lightroom-style
 * conventions), applied in OKLab so luminance edits do not shift hue and colour edits do not shift
 * lightness:
 *
 * <ul>
 *   <li><b>Exposure</b> — true linear-light gain (EV), applied before the perceptual stage;
 *       brightens everything and can clip whites, like exposure does.
 *   <li><b>Brightness</b> — mid-tone-weighted lightness shift; both endpoints are protected.
 *   <li><b>Lightness</b> — broad airy lift reaching shadows..upper mids; highlights protected.
 *   <li><b>Contrast</b> — smooth S-curve pivoted at L≈0.45; black and white points are fixed.
 *   <li><b>Shadows / Highlights</b> — the extreme dark / bright ends of the range.
 *   <li><b>Dark range / Light range</b> — the broader lower / upper halves (nested zones).
 *   <li><b>Whites / Blacks</b> — the two anchor ends of the tone curve.
 *   <li><b>Fade</b> — lifts the black floor without compressing whites.
 *   <li><b>Tone curve</b> — free knots through a monotone cubic (ends included), evaluated last.
 *   <li><b>Saturation</b> — uniform chroma multiplier: every colour moves together, skin included.
 *   <li><b>Vibrance</b> — <i>not</i> saturation: gain falls as a colour's own chroma rises, and
 *       human skin hues are protected — muted colours wake up first, skin stays natural.
 *   <li><b>Warmth</b> — moves along the blue↔yellow axis (OKLab b), tapered off pure white.
 *   <li><b>Tint</b> — moves along the green↔magenta axis (OKLab a), tapered off pure white.
 *   <li><b>Sharpness / Noise reduction / Clarity</b> — scale the recipe's develop-time amounts.
 * </ul>
 */
public final class AppleTune {
  public static final int MIN = -100, MAX = 100;

  // Light
  public int exposure, brightness, lightness, contrast, highlights, shadows;
  public int lightRange, darkRange, whites, blacks, fade;
  // Color
  public int saturation, vibrance, warmth, tint;
  // Detail
  public int sharpness, noiseReduction, clarity;

  /** Free tone-curve knots, x ascendent, endpoints (0,y0)..(1,yn) included; empty = identity. */
  public float[] curveX = new float[0], curveY = new float[0];

  public boolean isDefault() {
    return exposure == 0 && brightness == 0 && lightness == 0 && contrast == 0
        && highlights == 0 && shadows == 0 && lightRange == 0 && darkRange == 0
        && whites == 0 && blacks == 0 && fade == 0 && saturation == 0 && vibrance == 0
        && warmth == 0 && tint == 0 && sharpness == 0 && noiseReduction == 0 && clarity == 0
        && curveX.length == 0;
  }

  public AppleTune copy() {
    AppleTune c = new AppleTune();
    c.exposure = exposure; c.brightness = brightness; c.lightness = lightness;
    c.contrast = contrast; c.highlights = highlights; c.shadows = shadows;
    c.lightRange = lightRange; c.darkRange = darkRange; c.whites = whites;
    c.blacks = blacks; c.fade = fade; c.saturation = saturation; c.vibrance = vibrance;
    c.warmth = warmth; c.tint = tint; c.sharpness = sharpness;
    c.noiseReduction = noiseReduction; c.clarity = clarity;
    c.curveX = curveX.clone(); c.curveY = curveY.clone();
    return c;
  }

  public boolean sameSettings(AppleTune o) {
    if (o == null) return false;
    if (exposure != o.exposure || brightness != o.brightness || lightness != o.lightness
        || contrast != o.contrast || highlights != o.highlights || shadows != o.shadows
        || lightRange != o.lightRange || darkRange != o.darkRange || whites != o.whites
        || blacks != o.blacks || fade != o.fade || saturation != o.saturation
        || vibrance != o.vibrance || warmth != o.warmth || tint != o.tint
        || sharpness != o.sharpness || noiseReduction != o.noiseReduction || clarity != o.clarity)
      return false;
    if (curveX.length != o.curveX.length) return false;
    for (int i = 0; i < curveX.length; i++)
      if (curveX[i] != o.curveX[i] || curveY[i] != o.curveY[i]) return false;
    return true;
  }

  public JSONObject write() throws Exception {
    JSONObject j =
        new JSONObject()
            .put("version", 1)
            .put("exposure", exposure)
            .put("brightness", brightness)
            .put("lightness", lightness)
            .put("contrast", contrast)
            .put("highlights", highlights)
            .put("shadows", shadows)
            .put("lightRange", lightRange)
            .put("darkRange", darkRange)
            .put("whites", whites)
            .put("blacks", blacks)
            .put("fade", fade)
            .put("saturation", saturation)
            .put("vibrance", vibrance)
            .put("warmth", warmth)
            .put("tint", tint)
            .put("sharpness", sharpness)
            .put("noiseReduction", noiseReduction)
            .put("clarity", clarity);
    if (curveX.length > 0) {
      JSONArray xs = new JSONArray(), ys = new JSONArray();
      for (int i = 0; i < curveX.length; i++) {
        xs.put(curveX[i]);
        ys.put(curveY[i]);
      }
      j.put("curveX", xs).put("curveY", ys);
    }
    return j;
  }

  /** Reads a saved tune; missing or unknown fields keep 0 (the neutral position). */
  public static AppleTune read(JSONObject j) {
    AppleTune t = new AppleTune();
    t.exposure = clampInt(j.optInt("exposure", 0));
    t.brightness = clampInt(j.optInt("brightness", 0));
    t.lightness = clampInt(j.optInt("lightness", 0));
    t.contrast = clampInt(j.optInt("contrast", 0));
    t.highlights = clampInt(j.optInt("highlights", 0));
    t.shadows = clampInt(j.optInt("shadows", 0));
    t.lightRange = clampInt(j.optInt("lightRange", 0));
    t.darkRange = clampInt(j.optInt("darkRange", 0));
    t.whites = clampInt(j.optInt("whites", 0));
    t.blacks = clampInt(j.optInt("blacks", 0));
    t.fade = clampInt(j.optInt("fade", 0));
    t.saturation = clampInt(j.optInt("saturation", 0));
    t.vibrance = clampInt(j.optInt("vibrance", 0));
    t.warmth = clampInt(j.optInt("warmth", 0));
    t.tint = clampInt(j.optInt("tint", 0));
    t.sharpness = clampInt(j.optInt("sharpness", 0));
    t.noiseReduction = clampInt(j.optInt("noiseReduction", 0));
    t.clarity = clampInt(j.optInt("clarity", 0));
    JSONArray xs = j.optJSONArray("curveX"), ys = j.optJSONArray("curveY");
    if (xs != null && ys != null && xs.length() == ys.length() && xs.length() >= 2) {
      int n = xs.length();
      float[] cx = new float[n], cy = new float[n];
      boolean ok = true;
      for (int i = 0; i < n; i++) {
        cx[i] = (float) xs.optDouble(i, 0);
        cy[i] = (float) ys.optDouble(i, 0);
        if (Float.isNaN(cx[i]) || Float.isNaN(cy[i])) { ok = false; break; }
        cx[i] = clamp01(cx[i]);
        cy[i] = clamp01(cy[i]);
        if (i > 0 && cx[i] <= cx[i - 1] + 0.01f) ok = false;
      }
      if (ok && cx[0] == 0 && cx[n - 1] == 1f) {
        t.curveX = cx;
        t.curveY = cy;
      }
    }
    return t;
  }

  static int clampInt(int v) {
    return Math.max(MIN, Math.min(MAX, v));
  }

  static float clamp01(float x) {
    return x < 0f ? 0f : x > 1f ? 1f : x;
  }

  static float sstep(float e0, float e1, float x) {
    float t = clamp01((x - e0) / (e1 - e0));
    return t * t * (3f - 2f * t);
  }

  /**
   * Monotone-cubic (Fritsch–Carlson) evaluation of the user's curve knots at {@code x}. Knots are
   * x-ascending with endpoints included; straight identity when no knots are stored.
   */
  public static float curveAt(float[] xs, float[] ys, float x) {
    int n = xs.length;
    if (n < 2) return clamp01(x);
    x = clamp01(x);
    if (x <= xs[0]) return ys[0];
    if (x >= xs[n - 1]) return ys[n - 1];
    float[] m = new float[n];
    for (int i = 0; i < n - 1; i++) {
      float h = xs[i + 1] - xs[i];
      m[i] = h > 1e-6f ? (ys[i + 1] - ys[i]) / h : 0f;
    }
    float[] d = new float[n];
    d[0] = m[0];
    d[n - 1] = m[n - 2];
    for (int i = 1; i < n - 1; i++) {
      if (m[i - 1] * m[i] <= 0f) d[i] = 0f;
      else {
        float w1 = 2f * (xs[i + 1] - xs[i]) + (xs[i] - xs[i - 1]);
        float w2 = (xs[i + 1] - xs[i]) + 2f * (xs[i] - xs[i - 1]);
        d[i] = (w1 + w2) / (w1 / m[i - 1] + w2 / m[i]);
      }
    }
    int i = 0;
    while (i < n - 2 && x > xs[i + 1]) i++;
    float h = xs[i + 1] - xs[i];
    float t = (x - xs[i]) / h, t2 = t * t, t3 = t2 * t;
    float h00 = 2f * t3 - 3f * t2 + 1f, h10 = t3 - 2f * t2 + t;
    float h01 = -2f * t3 + 3f * t2, h11 = t3 - t2;
    float y = h00 * ys[i] + h10 * h * d[i] + h01 * ys[i + 1] + h11 * h * d[i + 1];
    return clamp01(y);
  }

  /** Precomputed per-develop application: tone LUT + linear gains + colour scalars. */
  public static final class Plan {
    /** 513-entry luminance map on OKLab L (brightness..fade, then the free curve). */
    private final float[] tone = new float[513];
    /** Linear-light exposure gain (EV), applied to linear P3 before OKLab. */
    public final float expoGain;
    /** Uniform chroma multiplier (saturation). */
    public final float satK;
    /** Vibrance: full-rate = vibK; per-pixel gain = 1 + vibK*(1 - min(C/0.13,1))*(1-0.85*wSkin). */
    public final float vibK;
    /** Warmth/tint: OKLab b/a axis offsets. */
    public final float warmB, tintA;
    /** Develop-time scales (1 = recipe amount). */
    public final float sharpenMul, nrMul, clarityMul;
    public final boolean hasCurve;

    public Plan(AppleTune t) {
      expoGain = (float) Math.pow(2.0, 1.3 * t.exposure / 100.0);
      satK = Math.max(0f, 1f + 0.9f * (t.saturation / 100f));
      vibK = 0.95f * (t.vibrance / 100f);
      warmB = 0.016f * (t.warmth / 100f);
      tintA = 0.014f * (t.tint / 100f);
      sharpenMul = 1f + 0.75f * (t.sharpness / 100f);
      nrMul = t.noiseReduction >= 0
          ? 1f + 0.90f * (t.noiseReduction / 100f)
          : 1f + 0.75f * (t.noiseReduction / 100f);
      clarityMul = 1f + t.clarity / 100f;
      hasCurve = t.curveX.length >= 2;
      for (int i = 0; i < tone.length; i++) {
        float x = i / 512f;
        x += t.brightness / 100f * 0.30f * (4f * x * (1f - x));
        x += t.lightness / 100f * 0.26f * (1f - 0.35f * x) * (1f - sstep(0.68f, 0.97f, x));
        x += t.contrast / 100f * 0.55f * (x - 0.45f) * (float) Math.pow(4f * x * (1f - x), 0.8);
        x += t.shadows / 100f * 0.28f * (1f - sstep(0.22f, 0.48f, x));
        x += t.darkRange / 100f * 0.20f * (1f - sstep(0.40f, 0.64f, x));
        x += t.lightRange / 100f * 0.20f * sstep(0.36f, 0.60f, x);
        x += t.highlights / 100f * 0.28f * sstep(0.60f, 0.86f, x);
        x += t.whites / 100f * 0.26f * sstep(0.80f, 1.00f, x);
        x += t.blacks / 100f * 0.26f * (1f - sstep(0.00f, 0.20f, x));
        x += t.fade / 100f * 0.16f * (1f - x) * (1f - x);
        if (hasCurve) x = curveAt(t.curveX, t.curveY, x);
        tone[i] = clamp01(x);
      }
      // Enforce non-decreasing map so no edit can solarize or band the tone path.
      for (int i = 1; i < tone.length; i++) if (tone[i] < tone[i - 1]) tone[i] = tone[i - 1];
    }

    /** Tone-mapped OKLab L for {@code L} in [0,1]. */
    public float toneAt(float L) {
      float p = clamp01(L) * 512f;
      int i = (int) p;
      float f = p - i;
      int i1 = Math.min(i + 1, 512);
      return tone[i] + (tone[i1] - tone[i]) * f;
    }

    /** Vibrance gain at chroma C with skin-sector weight wSkin (0..1). */
    public float vibranceGain(float C, float wSkin) {
      return 1f + vibK * (1f - Math.min(C / 0.13f, 1f)) * (1f - 0.85f * wSkin);
    }
  }
}
