package dev.grain.photo;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.TonemapCurve;
import dev.grain.photo.develop.FujiRecipe;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Recipe {
  public static final Recipe[] ALL = {
    new Recipe(
        0,
        "Daylight",
        400,
        "Sun-warmed color. A gentle shoulder. An ordinary afternoon, kept.",
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    new Recipe(
        1,
        "Coast",
        200,
        "Airy blues and restrained warmth. A little salt in the air.",
        CaptureRequest.CONTROL_AWB_MODE_AUTO),
    new Recipe(
        2,
        "Golden",
        800,
        "A honeyed response, without turning every shadow orange.",
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    new Recipe(
        3,
        "Afterhours",
        1600,
        "A deeper response for the other side of the day.",
        CaptureRequest.CONTROL_AWB_MODE_AUTO),
    new Recipe(
        4,
        FujiRecipe.REGGIES_PORTRA,
        "Reggie Ballesteros’ published Fujifilm recipe: Classic Chrome, warm and gently muted."
            + " Developed from this phone’s RAW with Fujifilm’s official Classic Chrome LUT.")
  };
  public final int id, referenceIso, whiteBalance, edgeMode, noiseMode;
  public final float exposureBias;
  public final String name, description;
  /** Non-null for recipes developed from RAW with an official Fujifilm LUT. */
  public final FujiRecipe fuji;
  public final boolean raw;

  private Recipe(int id, String name, int iso, String description, int wb) {
    this.id = id;
    this.name = name;
    referenceIso = iso;
    this.description = description;
    whiteBalance = wb;
    edgeMode = CaptureRequest.EDGE_MODE_OFF;
    noiseMode =
        id == 3
            ? CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            : id == 1
                ? CaptureRequest.NOISE_REDUCTION_MODE_FAST
                : CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL;
    exposureBias = id == 0 || id == 2 ? -.3f : 0f;
    fuji = null;
    raw = false;
  }

  /** RAW-developed Fujifilm recipe. The ISP only shapes the preview and the reference JPEG. */
  private Recipe(int id, FujiRecipe fuji, String description) {
    this.id = id;
    this.fuji = fuji;
    raw = true;
    name = fuji.name;
    referenceIso = 0;
    this.description = description;
    whiteBalance = CaptureRequest.CONTROL_AWB_MODE_AUTO;
    edgeMode = CaptureRequest.EDGE_MODE_FAST;
    noiseMode = CaptureRequest.NOISE_REDUCTION_MODE_FAST;
    // RAW headroom: DR-Auto captures 1/3 EV under; development meters the scene itself.
    exposureBias =
        fuji.dynamicRange == FujiRecipe.DynamicRange.DR400
            ? -2f
            : fuji.dynamicRange == FujiRecipe.DynamicRange.DR200
                ? -1f
                : fuji.dynamicRange == FujiRecipe.DynamicRange.AUTO ? -1f / 3 : 0f;
  }

  public static int rawRecipeCount() {
    int n = 0;
    for (Recipe r : ALL) if (r.raw) n++;
    return n;
  }

  public TonemapCurve curve(int strength, int points) {
    return new TonemapCurve(
        CurveMath.channel(id, 0, strength, points),
        CurveMath.channel(id, 1, strength, points),
        CurveMath.channel(id, 2, strength, points));
  }

  public JSONObject json(int strength) throws Exception {
    if (raw) {
      JSONObject settings = new JSONObject();
      for (String[] row : fuji.settings()) settings.put(row[0], row[1]);
      return new JSONObject()
          .put("id", id)
          .put("name", name)
          .put("version", 1)
          .put("kind", "RAW development + official Fujifilm F-Log2 LUT")
          .put("author", fuji.author)
          .put("source", fuji.source)
          .put("filmSimulationLut", fuji.filmSimulation)
          .put("settings", settings)
          .put("captureEvBias", exposureBias);
    }
    return new JSONObject()
        .put("id", id)
        .put("name", name)
        .put("version", 2)
        .put("curveSampling", "sRGB-spaced linear-input knots")
        .put("curveStrength", strength)
        .put("requestedAwbMode", whiteBalance)
        .put("preferredEdgeMode", edgeMode)
        .put("preferredNoiseReductionMode", noiseMode)
        .put("exposureBias", exposureBias)
        .put(
            "note",
            "Experimental ISP tone response. ISO number is inspiration, not a forced sensor ISO or"
                + " a claim of film-stock equivalence.");
  }

  public static JSONArray curveJson(TonemapCurve c) throws Exception {
    JSONArray rgb = new JSONArray();
    for (int ch = 0; ch < 3; ch++) {
      JSONArray a = new JSONArray();
      for (int p = 0; p < c.getPointCount(ch); p++) {
        android.graphics.PointF v = c.getPoint(ch, p);
        a.put(new JSONArray().put(v.x).put(v.y));
      }
      rgb.put(a);
    }
    return rgb;
  }
}
