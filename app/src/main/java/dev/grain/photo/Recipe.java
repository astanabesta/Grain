package dev.grain.photo;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.TonemapCurve;
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
        CaptureRequest.CONTROL_AWB_MODE_AUTO)
  };
  public final int id, referenceIso, whiteBalance, edgeMode, noiseMode;
  public final float exposureBias;
  public final String name, description;

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
  }

  public TonemapCurve curve(int strength, int points) {
    return new TonemapCurve(
        CurveMath.channel(id, 0, strength, points),
        CurveMath.channel(id, 1, strength, points),
        CurveMath.channel(id, 2, strength, points));
  }

  public JSONObject json(int strength) throws Exception {
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
