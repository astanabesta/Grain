package dev.grain.photo.develop;

/**
 * A published Fujifilm film-simulation recipe, expressed in the camera's own menu units. Grain
 * applies the film simulation with Fujifilm's official F-Log2 LUT; the other settings are modelled
 * (see {@link RawDeveloper} and docs/RAW-ENGINE.md for what is measured and what is approximate).
 */
public final class FujiRecipe {
  public enum DynamicRange {
    AUTO,
    DR100,
    DR200,
    DR400
  }

  public final String key, name, author, source, filmSimulation, filmLabel;
  public final DynamicRange dynamicRange;
  public final double highlight, shadow;
  public final int color, sharpness, noiseReduction, clarity;
  /** 0 off, 1 weak, 2 strong. */
  public final int grainRoughness, colorChrome, colorChromeBlue;
  /** true = large grain. */
  public final boolean grainLarge;
  public final int wbShiftRed, wbShiftBlue;
  public final String whiteBalance;

  public FujiRecipe(
      String key,
      String name,
      String author,
      String source,
      String filmSimulation,
      String filmLabel,
      DynamicRange dynamicRange,
      double highlight,
      double shadow,
      int color,
      int sharpness,
      int noiseReduction,
      int clarity,
      int grainRoughness,
      boolean grainLarge,
      int colorChrome,
      int colorChromeBlue,
      String whiteBalance,
      int wbShiftRed,
      int wbShiftBlue) {
    this.key = key;
    this.name = name;
    this.author = author;
    this.source = source;
    this.filmSimulation = filmSimulation;
    this.filmLabel = filmLabel;
    this.dynamicRange = dynamicRange;
    this.highlight = highlight;
    this.shadow = shadow;
    this.color = color;
    this.sharpness = sharpness;
    this.noiseReduction = noiseReduction;
    this.clarity = clarity;
    this.grainRoughness = grainRoughness;
    this.grainLarge = grainLarge;
    this.colorChrome = colorChrome;
    this.colorChromeBlue = colorChromeBlue;
    this.whiteBalance = whiteBalance;
    this.wbShiftRed = wbShiftRed;
    this.wbShiftBlue = wbShiftBlue;
  }

  /** Reggie Ballesteros' recipe as published by Fuji X Weekly (X-Trans IV), unchanged. */
  public static final FujiRecipe REGGIES_PORTRA =
      new FujiRecipe(
          "reggies-portra",
          "Reggie’s Portra",
          "Reggie Ballesteros",
          "https://fujixweekly.com/2022/06/11/fujifilm-x-trans-iv-film-simulation-recipe-reggies-portra/",
          "CLASSIC-CHROME",
          "Classic Chrome",
          DynamicRange.AUTO,
          -1,
          -1,
          2,
          -2,
          -4,
          0,
          1,
          false,
          2,
          1,
          "Auto",
          2,
          -4);

  public static String level(int value) {
    return value <= 0 ? "Off" : value == 1 ? "Weak" : "Strong";
  }

  public static String signed(double value) {
    if (value == 0) return "0";
    String s = value == Math.rint(value) ? String.valueOf((long) Math.abs(value)) : String.valueOf(Math.abs(value));
    return (value > 0 ? "+" : "−") + s;
  }

  /** Human-readable settings, in the order Fujifilm cameras list them. */
  public String[][] settings() {
    return new String[][] {
      {"Film simulation", filmLabel},
      {"Dynamic range", dynamicRange == DynamicRange.AUTO ? "DR-Auto" : dynamicRange.name()},
      {"White balance", whiteBalance + "; R " + signed(wbShiftRed) + ", B " + signed(wbShiftBlue)},
      {"Highlight", signed(highlight)},
      {"Shadow", signed(shadow)},
      {"Color", signed(color)},
      {"Sharpness", signed(sharpness)},
      {"High ISO NR", signed(noiseReduction)},
      {"Clarity", signed(clarity)},
      {"Grain", grainRoughness == 0 ? "Off" : level(grainRoughness) + " / " + (grainLarge ? "Large" : "Small")},
      {"Color Chrome Effect", level(colorChrome)},
      {"Color Chrome FX Blue", level(colorChromeBlue)}
    };
  }
}
