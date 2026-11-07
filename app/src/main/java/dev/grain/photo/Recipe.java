package dev.grain.photo;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.TonemapCurve;
import dev.grain.photo.develop.FujiRecipe;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Recipe {
  public static final Recipe[] ALL = build();

  private static Recipe[] build() {
    Recipe[] fixed = {
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
              + " Developed from this phone’s RAW with Fujifilm’s official Classic Chrome LUT.",
          false)
    };
    // Each Fujifilm film simulation on its own (ids stay fixed: never reorder, only append).
    String[][] sims = FujiRecipe.SIMULATIONS;
    // Published recipes after the simulations (ids 17..23; append only).
    FujiRecipe[] recipes = {
      FujiRecipe.KODAK_GOLD_200, FujiRecipe.KODACHROME_64, FujiRecipe.PACIFIC_BLUES,
      FujiRecipe.VIBRANT_ARIZONA, FujiRecipe.CINESTILL_800T, FujiRecipe.KODAK_TRI_X_400,
      FujiRecipe.REGGIES_SUPERIA
    };
    String[] looks = {
      "Ritchie Roesch’s published recipe: golden warmth, nostalgic yellows and reds.",
      "Ritchie Roesch’s published recipe: a crisp slide-film look, firm shadows, distinct reds and blues.",
      "Ritchie Roesch’s published recipe: a stylised summer palette, blue-teal with warm neutrals and"
          + " deep shadows.",
      "Ritchie Roesch’s published recipe: bright, warm and pastel-leaning, a sun-washed cinematic feel.",
      "Ritchie Roesch’s published recipe for artificial light: cool night ambience against coloured"
          + " lights, soft and grainy.",
      "Anders Lindborg’s recipe (Ritchie Roesch’s X100V version): gritty, textured black and white with"
          + " strong shadows.",
      "Reggie Ballesteros’ published Fujifilm recipe: Classic Negative, moodier, grainier and more"
          + " contrasty than his Portra, made for rich skin tones in directional light."
    };
    Recipe[] all = new Recipe[fixed.length + sims.length + recipes.length + 4];
    System.arraycopy(fixed, 0, all, 0, fixed.length);
    for (int i = 0; i < sims.length; i++) {
      int id = fixed.length + i;
      all[id] =
          new Recipe(
              id,
              FujiRecipe.standard(sims[i][0], sims[i][1]),
              sims[i][2]
                  + " Fujifilm’s official LUT at standard settings, developed from this phone’s RAW.",
              true);
    }
    for (int i = 0; i < recipes.length; i++) {
      int id = fixed.length + sims.length + i;
      all[id] =
          new Recipe(
              id,
              recipes[i],
              looks[i]
                  + " Developed from this phone’s RAW with Fujifilm’s official "
                  + recipes[i].filmLabel
                  + " LUT.",
              false);
    }
    // Sony's s709 (id 24): always shown; without Sony's LUT it explains where to get it
    int sonyId = fixed.length + sims.length + recipes.length;
    all[sonyId] =
        new Recipe(
            sonyId,
            FujiRecipe.SONY_S709,
            "Sony’s cinema look, developed alongside VENICE: subtle colours, smooth gradation and a"
                + " softer, low-contrast tone curve close to film. Sony’s official s709 LUT,"
                + " developed from this phone’s RAW through S-Log3 / S-Gamut3.Cine.",
            true);
    // Append-only recipes after id 24 so existing persisted recipe ids never shift.
    int brightKodakId = sonyId + 1;
    all[brightKodakId] =
        new Recipe(
            brightKodakId,
            FujiRecipe.BRIGHT_KODAK,
            "Ritchie Roesch’s published recipe: sun-drenched warmth, open shadows and bold Kodak"
                + " colour. Developed from this phone’s RAW with Fujifilm’s official Classic"
                + " Chrome LUT.",
            false);
    int kodak800v3Id = brightKodakId + 1;
    all[kodak800v3Id] =
        new Recipe(
            kodak800v3Id,
            FujiRecipe.KODAK_800_V3,
            "Ritchie Roesch’s Kodak Portra 800 v3: warm golden amber, creamy pastel highlight"
                + " roll-off, luminous open shadows and 800-speed grain, calibrated for phone"
                + " sensors through Fujifilm’s Classic Chrome LUT.",
            false);
    int leicaXId = kodak800v3Id + 1;
    all[leicaXId] =
        new Recipe(
            leicaXId,
            FujiRecipe.LEICA_X,
            "Carl Cruz’s Leica X: rich Leica Color vermilion, grounded forest greens, polarized"
                + " cobalt blues and crisp Summilux black-toe micro-contrast, calibrated for phone"
                + " sensors through Fujifilm’s Classic Chrome LUT.",
            false);
    return all;
  }

  /**
   * The order films are listed in: the ISP looks, the published recipes in FujiRecipe.PUBLISHED
   * order (Reggie's Portra and Reggie's Superia first), then the plain film simulations. Ids are
   * unchanged; a recipe missing from PUBLISHED still appears, after the listed ones.
   */
  public static final Recipe[] DISPLAY_ORDER = displayOrder();

  private static Recipe[] displayOrder() {
    Recipe[] out = new Recipe[ALL.length];
    boolean[] placed = new boolean[ALL.length];
    int n = 0;
    for (Recipe r : ALL)
      if (!r.raw) {
        out[n++] = r;
        placed[r.id] = true;
      }
    for (FujiRecipe p : FujiRecipe.PUBLISHED)
      for (Recipe r : ALL)
        if (!placed[r.id] && r.raw && !r.standard && r.fuji.key.equals(p.key)) {
          out[n++] = r;
          placed[r.id] = true;
        }
    for (Recipe r : ALL)
      if (!placed[r.id] && r.raw && !r.standard) {
        out[n++] = r;
        placed[r.id] = true;
      }
    for (Recipe r : ALL) if (!placed[r.id]) out[n++] = r;
    return out;
  }

  public final int id, referenceIso, whiteBalance, edgeMode, noiseMode;
  public final float exposureBias;
  public final String name, description;
  /** Non-null for films developed from RAW with an official LUT (Fujifilm, or Sony's s709). */
  public final FujiRecipe fuji;
  public final boolean raw;
  /**
   * A plain film simulation or Sony look at standard settings. Fujifilm ones show once their LUT
   * is imported; Sony s709 always shows.
   */
  public final boolean standard;

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
    standard = false;
  }

  /** RAW-developed Fujifilm recipe. The ISP only shapes the preview and the reference JPEG. */
  private Recipe(int id, FujiRecipe fuji, String description, boolean standard) {
    this.id = id;
    this.fuji = fuji;
    this.standard = standard;
    raw = true;
    name = fuji.name;
    referenceIso = 0;
    this.description = description;
    whiteBalance = CaptureRequest.CONTROL_AWB_MODE_AUTO;
    edgeMode = CaptureRequest.EDGE_MODE_FAST;
    noiseMode = CaptureRequest.NOISE_REDUCTION_MODE_FAST;
    exposureBias = rawCaptureBias(fuji);
  }

  /**
   * RAW capture headroom for a film's Dynamic Range setting (DR-Auto captures 1/3 EV under; D-Range
   * Priority Weak like DR200, Strong like DR400); development meters the scene itself.
   */
  public static float rawCaptureBias(FujiRecipe f) {
    // Sony's s709 has no dynamic range setting: 1/3 EV of highlight room, like DR-Auto
    if (f.sony()) return -1f / 3;
    if ("kodak-800-v3".equals(f.key) && f.dynamicRange == FujiRecipe.DynamicRange.DR400)
      return -1.25f; // phone-sensor optimal headroom: 1.25 EV highlight safety without 4x shadow photon loss
    if ("leica-x".equals(f.key) && f.dynamicRange == FujiRecipe.DynamicRange.AUTO)
      return -2f / 3f; // 2/3 EV specular protection for crisp sunlit whites on phone CMOS
    switch (f.dynamicRange) {
      case DR400:
      case DRP_STRONG:
        return -2f;
      case DR200:
      case DRP_WEAK:
        return -1f;
      case AUTO:
        return -1f / 3;
      default:
        return 0f;
    }
  }

  /**
   * Camera2 white balance for the viewfinder and reference JPEG of a RAW film: the nearest preset
   * to a fixed Fujifilm white balance (the RAW development uses the exact white point), else auto.
   */
  public static int awbModeFor(FujiRecipe f) {
    if (f == null) return CaptureRequest.CONTROL_AWB_MODE_AUTO;
    switch (f.wbMode) {
      case DAYLIGHT:
      case FLUORESCENT_2:
        return CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT;
      case SHADE:
        return CaptureRequest.CONTROL_AWB_MODE_SHADE;
      case FLUORESCENT_1:
        return CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
      case FLUORESCENT_3:
        return CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT;
      case INCANDESCENT:
        return CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT;
      case KELVIN:
        int k = f.kelvin;
        return k <= 3300
            ? CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            : k <= 3800
                ? CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT
                : k <= 4600
                    ? CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                    : k <= 5800
                        ? CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                        : k <= 7000
                            ? CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                            : CaptureRequest.CONTROL_AWB_MODE_SHADE;
      default:
        return CaptureRequest.CONTROL_AWB_MODE_AUTO;
    }
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
    return json(strength, null);
  }

  /** Receipt entry. For RAW films {@code tuned} is the film as tuned in the app (null = default). */
  public JSONObject json(int strength, FujiRecipe tuned) throws Exception {
    if (raw) {
      FujiRecipe f = tuned != null ? tuned : fuji;
      boolean changed = !f.sameSettings(fuji);
      JSONObject settings = new JSONObject();
      for (String[] row : f.settings()) settings.put(row[0], row[1]);
      JSONObject out =
          new JSONObject()
              .put("id", id)
              .put("name", name)
              .put("version", 2)
              .put(
                  "kind",
                  fuji.sony()
                      ? "RAW development + Sony's official s709 LUT (S-Log3 / S-Gamut3.Cine)"
                      : standard
                          ? "RAW development + official Fujifilm F-Log2 LUT, film simulation"
                          : "RAW development + official Fujifilm F-Log2 LUT")
              .put("author", fuji.author)
              .put("source", fuji.source)
              .put("filmSimulationLut", fuji.filmSimulation)
              .put("settings", settings)
              .put("tuned", changed)
              .put(
                  "defaultSettings",
                  fuji.sony() ? "Sony s709, standard settings" : standard ? "Fujifilm standard settings" : "published recipe")
              .put("captureEvBias", rawCaptureBias(f));
      if (changed) {
        JSONObject defaults = new JSONObject();
        for (String[] row : fuji.settings()) defaults.put(row[0], row[1]);
        out.put("defaultSettingValues", defaults);
      }
      return out;
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

  /** False for the all-zero placeholder curve some HALs report when no curve is in use. */
  public static boolean hasPoints(TonemapCurve c) {
    for (int ch = 0; ch < 3; ch++) {
      if (c.getPointCount(ch) < 2) return false;
      for (int p = 0; p < c.getPointCount(ch); p++) {
        android.graphics.PointF v = c.getPoint(ch, p);
        if (v.x != 0 || v.y != 0) return true;
      }
    }
    return false;
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
