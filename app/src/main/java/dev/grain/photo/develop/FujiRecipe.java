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
  /**
   * Grain extras, not Fujifilm settings (no Fujifilm film simulation has them): 0 = off, 1..10.
   * Halation re-exposes mainly the red record around bright light; bloom spreads highlight light.
   */
  public final int halation, bloom;

  /** Tuning ranges, in Fujifilm's menu units (X-Trans IV / V). */
  public static final double TONE_MIN = -2, TONE_MAX = 4, TONE_STEP = 0.5;

  public static final int COLOR_MAX = 4, SHARPNESS_MAX = 4, NR_MAX = 4, CLARITY_MAX = 5;
  public static final int WB_SHIFT_MAX = 9, EXTRA_MAX = 10;

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
    this(key, name, author, source, filmSimulation, filmLabel, dynamicRange, highlight, shadow, color,
        sharpness, noiseReduction, clarity, grainRoughness, grainLarge, colorChrome, colorChromeBlue,
        whiteBalance, wbShiftRed, wbShiftBlue, 0, 0);
  }

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
      int wbShiftBlue,
      int halation,
      int bloom) {
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
    this.halation = halation;
    this.bloom = bloom;
  }

  /** The adjustable settings of a film, edited in the app and applied with {@link #with}. */
  public static final class Tuning {
    public DynamicRange dynamicRange = DynamicRange.AUTO;
    public double highlight, shadow;
    public int color, sharpness, noiseReduction, clarity;
    public int grainRoughness, colorChrome, colorChromeBlue;
    public boolean grainLarge;
    public int wbShiftRed, wbShiftBlue;
    public int halation, bloom;
  }

  public Tuning tuning() {
    Tuning t = new Tuning();
    t.dynamicRange = dynamicRange;
    t.highlight = highlight;
    t.shadow = shadow;
    t.color = color;
    t.sharpness = sharpness;
    t.noiseReduction = noiseReduction;
    t.clarity = clarity;
    t.grainRoughness = grainRoughness;
    t.grainLarge = grainLarge;
    t.colorChrome = colorChrome;
    t.colorChromeBlue = colorChromeBlue;
    t.wbShiftRed = wbShiftRed;
    t.wbShiftBlue = wbShiftBlue;
    t.halation = halation;
    t.bloom = bloom;
    return t;
  }

  /** This film with tuned settings (clamped to the camera ranges); identity and LUT unchanged. */
  public FujiRecipe with(Tuning t) {
    return new FujiRecipe(
        key,
        name,
        author,
        source,
        filmSimulation,
        filmLabel,
        t.dynamicRange == null ? DynamicRange.AUTO : t.dynamicRange,
        tone(t.highlight),
        tone(t.shadow),
        monochrome() ? 0 : clamp(t.color, COLOR_MAX),
        clamp(t.sharpness, SHARPNESS_MAX),
        clamp(t.noiseReduction, NR_MAX),
        clamp(t.clarity, CLARITY_MAX),
        Math.max(0, Math.min(2, t.grainRoughness)),
        t.grainLarge && t.grainRoughness > 0,
        Math.max(0, Math.min(2, t.colorChrome)),
        Math.max(0, Math.min(2, t.colorChromeBlue)),
        whiteBalance,
        clamp(t.wbShiftRed, WB_SHIFT_MAX),
        clamp(t.wbShiftBlue, WB_SHIFT_MAX),
        Math.max(0, Math.min(EXTRA_MAX, t.halation)),
        Math.max(0, Math.min(EXTRA_MAX, t.bloom)));
  }

  /** True when every adjustable setting matches (identity strings are not compared). */
  public boolean sameSettings(FujiRecipe o) {
    return dynamicRange == o.dynamicRange
        && highlight == o.highlight
        && shadow == o.shadow
        && color == o.color
        && sharpness == o.sharpness
        && noiseReduction == o.noiseReduction
        && clarity == o.clarity
        && grainRoughness == o.grainRoughness
        && grainLarge == o.grainLarge
        && colorChrome == o.colorChrome
        && colorChromeBlue == o.colorChromeBlue
        && wbShiftRed == o.wbShiftRed
        && wbShiftBlue == o.wbShiftBlue
        && halation == o.halation
        && bloom == o.bloom;
  }

  static int clamp(int v, int max) {
    return Math.max(-max, Math.min(max, v));
  }

  static double tone(double v) {
    double s = Math.rint(v / TONE_STEP) * TONE_STEP;
    return Math.max(TONE_MIN, Math.min(TONE_MAX, s)) + 0.0;
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

  /** Fujifilm's LUT download page, credited for the plain film simulations. */
  public static final String FUJIFILM_LUTS = "https://www.fujifilm-x.com/en-in/support/download/lut/";

  /**
   * Film simulations Grain offers on their own, in Fujifilm's menu order: LUT name (as in the
   * official FLog2_to_&lt;name&gt;_33grid files), label, description. A simulation only appears once
   * its LUT has been imported. The packs also hold technical F-Log2 to Rec.709 conversions
   * (FLog2-709, WDR-709); those are video display transforms, not film simulations.
   */
  public static final String[][] SIMULATIONS = {
    {"PROVIA", "Provia", "Fujifilm’s standard colour: natural, balanced contrast for almost any scene."},
    {"Velvia", "Velvia", "High saturation and deeper contrast, made for landscapes and nature."},
    {"ASTIA", "Astia", "Softer contrast with bright, clean colour; gentle on skin."},
    {"CLASSIC-CHROME", "Classic Chrome", "Muted colour and firm shadows: a documentary, slide-film feel."},
    {"REALA-ACE", "Reala Ace", "True-to-life colour with crisp, faithful tones."},
    {"PRO-Neg.Hi", "Pro Neg. Hi", "Portrait colour with a little more contrast than Pro Neg. Std."},
    {"PRO-Neg.Std", "Pro Neg. Std", "Soft, low-contrast colour for portraits and even light."},
    {"CLASSIC-Neg.", "Classic Neg.", "Hard tones and shifted colour, after everyday colour-negative film."},
    {"NOSTALGIC-Neg.", "Nostalgic Neg.", "Amber highlights and rich shadows, after 1970s colour prints."},
    {"ETERNA", "Eterna", "Muted colour and soft shadows, designed for cinema."},
    {"ETERNA-BB", "Eterna Bleach Bypass", "Low saturation and high contrast, like the bleach-bypass process."},
    {"ACROS", "Acros", "Black and white with deep blacks and rich, fine gradation."}
  };

  /** True for the packs' technical F-Log2 to Rec.709 transforms, which are not film simulations. */
  public static boolean isTechnical(String lutName) {
    String n = lutName.toUpperCase(java.util.Locale.ROOT);
    return n.endsWith("709") || n.startsWith("FLOG2") || n.startsWith("F-LOG");
  }

  /**
   * A film simulation on its own, at standard settings: DR-Auto, Auto white balance, every tone,
   * colour, grain and Color Chrome setting at 0 / Off.
   */
  public static FujiRecipe standard(String lutName, String label) {
    return new FujiRecipe(
        "sim-" + lutName.toLowerCase(java.util.Locale.ROOT),
        label,
        "Fujifilm",
        FUJIFILM_LUTS,
        lutName,
        label,
        DynamicRange.AUTO,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        false,
        0,
        0,
        "Auto",
        0,
        0);
  }

  public boolean monochrome() {
    return filmSimulation.toUpperCase(java.util.Locale.ROOT).startsWith("ACROS");
  }

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
      {"Color", monochrome() ? "n/a (black and white)" : signed(color)},
      {"Sharpness", signed(sharpness)},
      {"High ISO NR", signed(noiseReduction)},
      {"Clarity", signed(clarity)},
      {"Grain", grainRoughness == 0 ? "Off" : level(grainRoughness) + " / " + (grainLarge ? "Large" : "Small")},
      {"Color Chrome Effect", level(colorChrome)},
      {"Color Chrome FX Blue", level(colorChromeBlue)},
      {"Halation (Grain extra)", halation == 0 ? "Off" : String.valueOf(halation)},
      {"Bloom (Grain extra)", bloom == 0 ? "Off" : String.valueOf(bloom)}
    };
  }
}
