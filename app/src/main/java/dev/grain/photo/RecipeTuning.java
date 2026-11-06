package dev.grain.photo;

import android.content.SharedPreferences;
import dev.grain.photo.develop.FujiRecipe;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * Per-film tuning of the RAW films, saved as JSON under "tune.&lt;film key&gt;". A film without saved
 * tuning develops with its default: the published recipe (Reggie's Portra), or Fujifilm's standard
 * settings for a film simulation on its own. Settings equal to the default are not stored.
 */
final class RecipeTuning {
  private final SharedPreferences prefs;
  private final Map<String, FujiRecipe> cache = new HashMap<>();

  RecipeTuning(SharedPreferences prefs) {
    this.prefs = prefs;
  }

  /** The film as it will be developed; null for ISP looks. */
  FujiRecipe effective(Recipe r) {
    if (!r.raw) return null;
    FujiRecipe known = cache.get(r.fuji.key);
    if (known != null) return known;
    FujiRecipe film = r.fuji;
    String saved = prefs.getString(key(r), null);
    if (saved != null)
      try {
        film = r.fuji.with(read(new JSONObject(saved), r.fuji.tuning()));
      } catch (Exception e) {
        android.util.Log.w("Grain", "Ignoring unreadable tuning for " + r.fuji.key, e);
      }
    cache.put(r.fuji.key, film);
    return film;
  }

  /** True when the film's settings differ from its default. */
  boolean tuned(Recipe r) {
    return r.raw && !effective(r).sameSettings(r.fuji);
  }

  /** Stores new settings (clamped to the camera ranges) and returns the film as now tuned. */
  FujiRecipe save(Recipe r, FujiRecipe.Tuning t) {
    FujiRecipe film = r.fuji.with(t);
    SharedPreferences.Editor edit = prefs.edit();
    if (film.sameSettings(r.fuji)) edit.remove(key(r));
    else
      try {
        edit.putString(key(r), write(film.tuning()).toString());
      } catch (Exception e) {
        android.util.Log.w("Grain", "Tuning not saved for " + r.fuji.key, e);
      }
    edit.apply();
    cache.put(r.fuji.key, film);
    return film;
  }

  /** Back to the film's default settings. */
  void reset(Recipe r) {
    if (!r.raw) return;
    prefs.edit().remove(key(r)).apply();
    cache.remove(r.fuji.key);
  }

  static String key(Recipe r) {
    return "tune." + r.fuji.key;
  }

  static JSONObject write(FujiRecipe.Tuning t) throws Exception {
    return new JSONObject()
        .put("version", 1)
        .put("dynamicRange", t.dynamicRange.name())
        .put("highlight", t.highlight)
        .put("shadow", t.shadow)
        .put("color", t.color)
        .put("sharpness", t.sharpness)
        .put("noiseReduction", t.noiseReduction)
        .put("clarity", t.clarity)
        .put("grain", t.grainRoughness)
        .put("grainLarge", t.grainLarge)
        .put("colorChrome", t.colorChrome)
        .put("colorChromeBlue", t.colorChromeBlue)
        .put("wbShiftRed", t.wbShiftRed)
        .put("wbShiftBlue", t.wbShiftBlue)
        .put("halation", t.halation)
        .put("bloom", t.bloom)
        .put("whiteBalance", t.wbMode.name())
        .put("kelvin", t.kelvin)
        .put("filter", t.monoFilter)
        .put("development", t.development.name());
  }

  /** Reads saved settings over the film's defaults; missing or unknown values keep the default. */
  static FujiRecipe.Tuning read(JSONObject j, FujiRecipe.Tuning t) {
    try {
      t.dynamicRange = FujiRecipe.DynamicRange.valueOf(j.optString("dynamicRange", t.dynamicRange.name()));
    } catch (IllegalArgumentException ignored) {
      // keep the default
    }
    double highlight = j.optDouble("highlight", t.highlight), shadow = j.optDouble("shadow", t.shadow);
    if (!Double.isNaN(highlight) && !Double.isInfinite(highlight)) t.highlight = highlight;
    if (!Double.isNaN(shadow) && !Double.isInfinite(shadow)) t.shadow = shadow;
    t.color = j.optInt("color", t.color);
    t.sharpness = j.optInt("sharpness", t.sharpness);
    t.noiseReduction = j.optInt("noiseReduction", t.noiseReduction);
    t.clarity = j.optInt("clarity", t.clarity);
    t.grainRoughness = j.optInt("grain", t.grainRoughness);
    t.grainLarge = j.optBoolean("grainLarge", t.grainLarge);
    t.colorChrome = j.optInt("colorChrome", t.colorChrome);
    t.colorChromeBlue = j.optInt("colorChromeBlue", t.colorChromeBlue);
    t.wbShiftRed = j.optInt("wbShiftRed", t.wbShiftRed);
    t.wbShiftBlue = j.optInt("wbShiftBlue", t.wbShiftBlue);
    t.halation = j.optInt("halation", t.halation);
    t.bloom = j.optInt("bloom", t.bloom);
    try {
      t.wbMode = FujiRecipe.WhiteBalance.valueOf(j.optString("whiteBalance", t.wbMode.name()));
    } catch (IllegalArgumentException ignored) {
      // keep the default
    }
    t.kelvin = j.optInt("kelvin", t.kelvin);
    t.monoFilter = j.optInt("filter", t.monoFilter);
    // settings saved before v0.3.1 have no "development": they keep the film's default
    try {
      t.development = FujiRecipe.Development.valueOf(j.optString("development", t.development.name()));
    } catch (IllegalArgumentException ignored) {
      // keep the default
    }
    return t;
  }
}
