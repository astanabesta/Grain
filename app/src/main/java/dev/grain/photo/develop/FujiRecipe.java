package dev.grain.photo.develop;

/**
 * A published Fujifilm film-simulation recipe, expressed in the camera's own menu units. Grain
 * applies the film simulation with Fujifilm's official F-Log2 LUT; the other settings are modelled
 * (see {@link RawDeveloper} and docs/RAW_FUJI_RECIPES.md for what is measured and what is
 * approximate).
 *
 * <p>The same type carries Sony's s709 look ({@link #SONY_S709}, v0.3.5): Sony's official LUT on
 * S-Log3 / S-Gamut3.Cine. Fujifilm-only settings (Dynamic range, Color Chrome Effect, FX Blue) are
 * fixed off for it; white balance, tone, colour, sharpness, noise reduction, clarity, grain and the
 * Grain extras work as for any film.
 */
public final class FujiRecipe {
  /** Dynamic Range and D-Range Priority, in the cameras' menu order. Never reorder (saved by name). */
  public enum DynamicRange {
    AUTO("DR-Auto"),
    DR100("DR100"),
    DR200("DR200"),
    DR400("DR400"),
    DRP_WEAK("DR-P Weak"),
    DRP_STRONG("DR-P Strong");

    public final String label;

    DynamicRange(String label) {
      this.label = label;
    }

    /** D-Range Priority: Highlight and Shadow are set by the mode, as on the cameras. */
    public boolean priority() {
      return this == DRP_WEAK || this == DRP_STRONG;
    }
  }

  /**
   * Fujifilm white-balance modes. A fixed mode develops for its white point instead of the phone's
   * as-shot (auto) white. Preset white points (CCT on the Planckian locus plus Duv, CIE 1960) were
   * measured from the preset gain tables inside public Fujifilm RAF files (X-T5, X-T4, GFX100 II;
   * see docs/RAW_FUJI_RECIPES.md): Fujifilm publishes names only. Never reorder (saved by name).
   */
  public enum WhiteBalance {
    AUTO("Auto", 0, 0),
    DAYLIGHT("Daylight", 4850, 0),
    SHADE("Shade", 6000, 0),
    FLUORESCENT_1("Fluorescent 1", 6450, 0.0075),
    FLUORESCENT_2("Fluorescent 2", 4950, 0.0078),
    FLUORESCENT_3("Fluorescent 3", 4300, 0.015),
    INCANDESCENT("Incandescent", 2856, 0),
    KELVIN("Kelvin", 0, 0);

    public final String label;
    /** Preset white point: correlated colour temperature (K) and Duv; 0 for Auto and Kelvin. */
    public final int cct;

    public final double duv;

    WhiteBalance(String label, int cct, double duv) {
      this.label = label;
      this.cct = cct;
      this.duv = duv;
    }

    /** Parses a menu label ("Auto", "Daylight", "Fluorescent 3", "5800K"); unknown = Auto. */
    public static WhiteBalance parse(String label) {
      if (label == null) return AUTO;
      String l = label.trim();
      for (WhiteBalance w : values()) if (w.label.equalsIgnoreCase(l)) return w;
      return l.matches("\\d{4,5}\\s*[Kk]") ? KELVIN : AUTO;
    }
  }

  /**
   * How Grain develops the RAW (not a Fujifilm setting). CLEAN (v0.2.1 and later): noise reduction
   * from the frame's measured noise curve at two scales, recipe sharpening, and grain topped up
   * only to the recipe amount. FIRST_VERSION (Grain v0.2.0): colour noise reduction only, no
   * luminance noise reduction at High ISO NR -4, no sharpening at Sharpness -2, the full recipe
   * grain, and Color Chrome / Color applied in OKLab: a grittier, more film-like picture. FX Blue
   * is the same in both (fitted to Fujifilm JPEGs, v0.3.3). Never reorder (saved by name).
   */
  public enum Development {
    CLEAN("Clean"),
    FIRST_VERSION("First version");

    public final String label;

    Development(String label) {
      this.label = label;
    }
  }

  /** Acros filter variants (Fujifilm: ACROS+Ye, +R, +G), as positions 0..3. */
  public static final String[] MONO_FILTERS = {"None", "+Ye", "+R", "+G"};

  public final String key, name, author, source, filmSimulation, filmLabel;
  public final DynamicRange dynamicRange;
  public final double highlight, shadow;
  public final int color, sharpness, noiseReduction, clarity;
  /** 0 off, 1 weak, 2 strong. */
  public final int grainRoughness, colorChrome, colorChromeBlue;
  /** true = large grain. */
  public final boolean grainLarge;
  public final int wbShiftRed, wbShiftBlue;
  /** White-balance mode; {@link #kelvin} is used when the mode is {@link WhiteBalance#KELVIN}. */
  public final WhiteBalance wbMode;
  /** Colour temperature for Kelvin white balance (2500..10000 K, 10 K steps). */
  public final int kelvin;
  /** The white balance as the camera menu shows it: "Auto", "Daylight", "5800K", ... */
  public final String whiteBalance;
  /** Acros filter, 0 none .. 3 (see {@link #MONO_FILTERS}); always 0 for colour films. */
  public final int monoFilter;
  /**
   * The exposure compensation the recipe's author typically uses, as published ("" if none).
   * Guidance only: Grain never applies it automatically.
   */
  public final String exposureNote;
  /**
   * Grain extras, not Fujifilm settings (no Fujifilm film simulation has them): 0 = off, 1..10.
   * Halation re-exposes mainly the red record around bright light; bloom spreads highlight light.
   */
  public final int halation, bloom;
  /** How the RAW is developed (Grain's choice, not a Fujifilm setting); see {@link Development}. */
  public final Development development;

  /** Tuning ranges, in Fujifilm's menu units (X-Trans IV / V). */
  public static final double TONE_MIN = -2, TONE_MAX = 4, TONE_STEP = 0.5;

  public static final int COLOR_MAX = 4, SHARPNESS_MAX = 4, NR_MAX = 4, CLARITY_MAX = 5;
  public static final int WB_SHIFT_MAX = 9, EXTRA_MAX = 10;
  /** Kelvin white balance range; the app steps it by {@link #KELVIN_STEP}. */
  public static final int KELVIN_MIN = 2500, KELVIN_MAX = 10000, KELVIN_STEP = 50;

  public static final int KELVIN_DEFAULT = 5500;

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
    this(key, name, author, source, filmSimulation, filmLabel, dynamicRange, highlight, shadow, color,
        sharpness, noiseReduction, clarity, grainRoughness, grainLarge, colorChrome, colorChromeBlue,
        WhiteBalance.parse(whiteBalance), kelvinOf(whiteBalance), wbShiftRed, wbShiftBlue, halation,
        bloom, 0, "", Development.CLEAN);
  }

  private FujiRecipe(
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
      WhiteBalance wbMode,
      int kelvin,
      int wbShiftRed,
      int wbShiftBlue,
      int halation,
      int bloom,
      int monoFilter,
      String exposureNote,
      Development development) {
    this.key = key;
    this.name = name;
    this.author = author;
    this.source = source;
    this.filmSimulation = filmSimulation;
    this.filmLabel = filmLabel;
    boolean sony = sonyLut(filmSimulation);
    // Sony s709 has no Fujifilm dynamic range or Color Chrome settings: fixed, whatever is tuned
    this.dynamicRange = sony ? DynamicRange.DR100 : dynamicRange == null ? DynamicRange.AUTO : dynamicRange;
    this.highlight = highlight;
    this.shadow = shadow;
    this.color = color;
    this.sharpness = sharpness;
    this.noiseReduction = noiseReduction;
    this.clarity = clarity;
    this.grainRoughness = grainRoughness;
    this.grainLarge = grainLarge;
    this.colorChrome = sony ? 0 : colorChrome;
    this.colorChromeBlue = sony ? 0 : colorChromeBlue;
    this.wbMode = wbMode == null ? WhiteBalance.AUTO : wbMode;
    this.kelvin = kelvin(kelvin);
    this.whiteBalance = this.wbMode == WhiteBalance.KELVIN ? this.kelvin + "K" : this.wbMode.label;
    this.wbShiftRed = wbShiftRed;
    this.wbShiftBlue = wbShiftBlue;
    this.halation = halation;
    this.bloom = bloom;
    this.monoFilter = monochrome() ? Math.max(0, Math.min(MONO_FILTERS.length - 1, monoFilter)) : 0;
    this.exposureNote = exposureNote == null ? "" : exposureNote;
    this.development = development == null ? Development.CLEAN : development;
  }

  /** The Kelvin value in a label such as "5800K", else {@link #KELVIN_DEFAULT}. */
  static int kelvinOf(String label) {
    if (label != null) {
      String digits = label.trim().replaceAll("\\s*[Kk]$", "");
      if (digits.matches("\\d{4,5}")) return kelvin(Integer.parseInt(digits));
    }
    return KELVIN_DEFAULT;
  }

  /** Clamped to Fujifilm's range, in its 10 K steps. */
  static int kelvin(int k) {
    int c = Math.max(KELVIN_MIN, Math.min(KELVIN_MAX, k));
    return (int) Math.round(c / 10.0) * 10;
  }

  /**
   * Fluent definition of a published recipe, in the order Fuji X Weekly lists settings. Defaults are
   * Fujifilm's standard settings (DR-Auto, Auto WB, everything 0 / Off).
   */
  public static final class Builder {
    private final String key, name, author, source, lut, label;
    private DynamicRange dynamicRange = DynamicRange.AUTO;
    private double highlight, shadow;
    private int color, sharpness, noiseReduction, clarity;
    private int grainRoughness, colorChrome, colorChromeBlue;
    private boolean grainLarge;
    private WhiteBalance wbMode = WhiteBalance.AUTO;
    private int kelvin = KELVIN_DEFAULT;
    private int wbShiftRed, wbShiftBlue, monoFilter;
    private String exposureNote = "";

    public Builder(String key, String name, String author, String source, String lut, String label) {
      this.key = key;
      this.name = name;
      this.author = author;
      this.source = source;
      this.lut = lut;
      this.label = label;
    }

    public Builder dynamicRange(DynamicRange d) {
      dynamicRange = d;
      return this;
    }

    public Builder whiteBalance(WhiteBalance mode, int shiftRed, int shiftBlue) {
      wbMode = mode;
      wbShiftRed = shiftRed;
      wbShiftBlue = shiftBlue;
      return this;
    }

    public Builder kelvin(int k, int shiftRed, int shiftBlue) {
      kelvin = k;
      return whiteBalance(WhiteBalance.KELVIN, shiftRed, shiftBlue);
    }

    public Builder tone(double highlight, double shadow) {
      this.highlight = highlight;
      this.shadow = shadow;
      return this;
    }

    public Builder color(int color) {
      this.color = color;
      return this;
    }

    public Builder detail(int sharpness, int noiseReduction, int clarity) {
      this.sharpness = sharpness;
      this.noiseReduction = noiseReduction;
      this.clarity = clarity;
      return this;
    }

    public Builder grain(int roughness, boolean large) {
      grainRoughness = roughness;
      grainLarge = large;
      return this;
    }

    public Builder colorChrome(int effect, int blue) {
      colorChrome = effect;
      colorChromeBlue = blue;
      return this;
    }

    public Builder filter(int filter) {
      monoFilter = filter;
      return this;
    }

    public Builder exposure(String note) {
      exposureNote = note;
      return this;
    }

    public FujiRecipe build() {
      return new FujiRecipe(key, name, author, source, lut, label, dynamicRange, highlight, shadow,
          color, sharpness, noiseReduction, clarity, grainRoughness, grainLarge, colorChrome,
          colorChromeBlue, wbMode, kelvin, wbShiftRed, wbShiftBlue, 0, 0, monoFilter, exposureNote,
          Development.CLEAN);
    }
  }

  /** The adjustable settings of a film, edited in the app and applied with {@link #with}. */
  public static final class Tuning {
    public DynamicRange dynamicRange = DynamicRange.AUTO;
    public double highlight, shadow;
    public int color, sharpness, noiseReduction, clarity;
    public int grainRoughness, colorChrome, colorChromeBlue;
    public boolean grainLarge;
    public int wbShiftRed, wbShiftBlue;
    public WhiteBalance wbMode = WhiteBalance.AUTO;
    public int kelvin = KELVIN_DEFAULT;
    public int monoFilter;
    public int halation, bloom;
    public Development development = Development.CLEAN;
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
    t.wbMode = wbMode;
    t.kelvin = kelvin;
    t.monoFilter = monoFilter;
    t.halation = halation;
    t.bloom = bloom;
    t.development = development;
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
        t.wbMode == null ? WhiteBalance.AUTO : t.wbMode,
        t.kelvin,
        clamp(t.wbShiftRed, WB_SHIFT_MAX),
        clamp(t.wbShiftBlue, WB_SHIFT_MAX),
        Math.max(0, Math.min(EXTRA_MAX, t.halation)),
        Math.max(0, Math.min(EXTRA_MAX, t.bloom)),
        monochrome() ? Math.max(0, Math.min(MONO_FILTERS.length - 1, t.monoFilter)) : 0,
        exposureNote,
        t.development == null ? Development.CLEAN : t.development);
  }

  /**
   * True when every adjustable setting matches (identity strings are not compared; the colour
   * temperature only counts for Kelvin white balance).
   */
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
        && wbMode == o.wbMode
        && (wbMode != WhiteBalance.KELVIN || kelvin == o.kelvin)
        && wbShiftRed == o.wbShiftRed
        && wbShiftBlue == o.wbShiftBlue
        && monoFilter == o.monoFilter
        && halation == o.halation
        && bloom == o.bloom
        && development == o.development;
  }

  static int clamp(int v, int max) {
    return Math.max(-max, Math.min(max, v));
  }

  static double tone(double v) {
    double s = Math.rint(v / TONE_STEP) * TONE_STEP;
    return Math.max(TONE_MIN, Math.min(TONE_MAX, s)) + 0.0;
  }

  /**
   * Reggie Ballesteros' recipe as published by Fuji X Weekly (X-Trans IV), unchanged; developed
   * like Grain's first version (v0.2.0) by default, the look chosen on the phone.
   */
  public static final FujiRecipe REGGIES_PORTRA =
      developed(Development.FIRST_VERSION, new FujiRecipe(
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
          -4));

  /** The same film and settings, developed the given way. */
  static FujiRecipe developed(Development development, FujiRecipe film) {
    Tuning t = film.tuning();
    t.development = development;
    return film.with(t);
  }

  /**
   * Reggie Ballesteros' Classic Negative recipe as published by Fuji X Weekly (X-Trans V, also for
   * X-Trans IV), unchanged; no typical exposure (removed at his request, as for Reggie's Portra).
   * Developed like his Portra (First version) by default.
   */
  public static final FujiRecipe REGGIES_SUPERIA =
      developed(Development.FIRST_VERSION, new Builder(
              "reggies-superia",
              "Reggie’s Superia",
              "Reggie Ballesteros",
              "https://fujixweekly.com/2026/03/12/reggies-superia-a-fujifilm-recipe-for-x-trans-iv-v-cameras/",
              "CLASSIC-Neg.",
              "Classic Neg.")
          .whiteBalance(WhiteBalance.AUTO, 1, -3)
          .tone(-2, -1)
          .color(1)
          .detail(-2, -4, 0)
          .grain(2, true)
          .colorChrome(2, 2)
          .build());

  private static final String ROESCH = "Ritchie Roesch (Fuji X Weekly)";

  /** Kodak Gold 200, Fuji X Weekly (X-Trans V), as published. */
  public static final FujiRecipe KODAK_GOLD_200 =
      new Builder(
              "kodak-gold-200",
              "Kodak Gold 200",
              ROESCH,
              "https://fujixweekly.com/2023/10/24/kodak-gold-200-fujifilm-x-t5-x-trans-v-film-simulation-recipe/",
              "CLASSIC-CHROME",
              "Classic Chrome")
          .dynamicRange(DynamicRange.DR400)
          .whiteBalance(WhiteBalance.DAYLIGHT, 4, -5)
          .tone(-1.5, 0.5)
          .color(3)
          .detail(-2, -4, -2)
          .grain(2, false)
          .colorChrome(1, 0)
          .exposure("+2/3 to +1 EV")
          .build();

  /** Kodachrome 64, Fuji X Weekly (X-Trans V), as published. */
  public static final FujiRecipe KODACHROME_64 =
      new Builder(
              "kodachrome-64",
              "Kodachrome 64",
              ROESCH,
              "https://fujixweekly.com/2022/11/28/kodachrome-64-fujifilm-x-t5-x-trans-v-film-simulation-recipe/",
              "CLASSIC-CHROME",
              "Classic Chrome")
          .dynamicRange(DynamicRange.DR200)
          .whiteBalance(WhiteBalance.DAYLIGHT, 2, -5)
          .tone(0, 0.5)
          .color(2)
          .detail(1, -4, 3)
          .grain(1, false)
          .colorChrome(2, 0)
          .exposure("0 to +2/3 EV")
          .build();

  /** Pacific Blues, Fuji X Weekly (X-Trans V), as published. */
  public static final FujiRecipe PACIFIC_BLUES =
      new Builder(
              "pacific-blues",
              "Pacific Blues",
              ROESCH,
              "https://fujixweekly.com/2022/12/06/pacific-blues-fujifilm-x-t5-x-trans-v-film-simulation-recipe/",
              "CLASSIC-Neg.",
              "Classic Neg.")
          .dynamicRange(DynamicRange.DR400)
          .kelvin(5800, 1, -3)
          .tone(-2, 3)
          .color(4)
          .detail(-2, -4, -3)
          .grain(2, true)
          .colorChrome(2, 1)
          .exposure("+2/3 to +1 EV")
          .build();

  /** Vibrant Arizona, Fuji X Weekly (X-Trans V), as published. Tone is set by D-Range Priority. */
  public static final FujiRecipe VIBRANT_ARIZONA =
      new Builder(
              "vibrant-arizona",
              "Vibrant Arizona",
              ROESCH,
              "https://fujixweekly.com/2023/04/25/getting-a-wes-anderson-look-from-your-fujifilm-camera-4-new-film-simulation-recipes/",
              "CLASSIC-CHROME",
              "Classic Chrome")
          .dynamicRange(DynamicRange.DRP_STRONG)
          .kelvin(4350, 6, -8)
          .color(4)
          .detail(-2, -4, -3)
          .grain(1, false)
          .colorChrome(0, 1)
          .exposure("+2/3 to +1 1/3 EV")
          .build();

  /** CineStill 800T, Fuji X Weekly (X-Trans V), as published; made for artificial light. */
  public static final FujiRecipe CINESTILL_800T =
      new Builder(
              "cinestill-800t",
              "CineStill 800T",
              ROESCH,
              "https://fujixweekly.com/2024/04/16/cinestill-800t-fujifilm-x-trans-v-film-simulation-recipe/",
              "ETERNA",
              "Eterna")
          .dynamicRange(DynamicRange.DR400)
          .whiteBalance(WhiteBalance.FLUORESCENT_3, -6, -4)
          .tone(0, 2)
          .color(4)
          .detail(-3, -4, -5)
          .grain(2, true)
          .colorChrome(2, 1)
          .exposure("−1/3 to +2/3 EV")
          .build();

  /**
   * Kodak Tri-X 400 by Anders Lindborg, Ritchie Roesch's X100V version, as published (plain Acros;
   * the +Ye/+R/+G filters are optional tuning). Published ISO 1600-12800.
   */
  public static final FujiRecipe KODAK_TRI_X_400 =
      new Builder(
              "kodak-tri-x-400",
              "Kodak Tri-X 400",
              "Anders Lindborg; X100V adaptation by " + ROESCH,
              "https://fujixweekly.com/2020/06/18/fujifilm-x100v-film-simulation-recipe-kodak-tri-x-400/",
              "ACROS",
              "Acros")
          .dynamicRange(DynamicRange.DR200)
          .whiteBalance(WhiteBalance.DAYLIGHT, 9, -9)
          .tone(0, 3)
          .detail(1, -4, 4)
          .grain(2, true)
          .colorChrome(2, 0)
          .exposure("+1/3 to +1 EV")
          .build();

  /**
   * Bright Kodak by Ritchie Roesch (X-Trans IV), as published by Fuji X Weekly. Published Auto ISO
   * up to ISO 6400.
   */
  public static final FujiRecipe BRIGHT_KODAK =
      new Builder(
              "bright-kodak",
              "Bright Kodak",
              ROESCH,
              "https://fujixweekly.com/2022/06/10/fujifilm-x-trans-iv-film-simulation-recipe-bright-kodak/",
              "CLASSIC-CHROME",
              "Classic Chrome")
          .dynamicRange(DynamicRange.DR400)
          .whiteBalance(WhiteBalance.DAYLIGHT, 3, -7)
          .tone(-2, -2)
          .color(4)
          .detail(-2, -4, -3)
          .grain(2, true)
          .colorChrome(0, 0)
          .exposure("+1 to +1 2/3 EV")
          .build();

  /**
   * Kodak Portra 800 v3 ("Kodak 800 v3") by Ritchie Roesch, as published by Fuji X Weekly, with
   * phone-sensor-to-emulsion tone, highlight shoulder, shadow latitude and perceptual OKLab colour
   * calibration.
   */
  public static final FujiRecipe KODAK_800_V3 =
      new Builder(
              "kodak-800-v3",
              "Kodak 800 v3",
              ROESCH,
              "https://fujixweekly.com/2024/02/14/kodak-portra-800-v3-fujifilm-x-t5-x-trans-v-x-e4-x-trans-iv-film-simulation-recipe/",
              "CLASSIC-CHROME",
              "Classic Chrome")
          .dynamicRange(DynamicRange.DR400)
          .kelvin(6600, -1, -3)
          .tone(-2, -0.5)
          .color(3)
          .detail(-2, -4, -3)
          .grain(2, true)
          .colorChrome(2, 1)
          .exposure("+1/3 to +1 1/3 EV")
          .build();

  /**
   * Leica X by Carl Cruz (@carlcruzsnaps), emulating Leica Color on Classic Chrome, with
   * phone-sensor-to-Summilux/X-Trans V perceptual colour density, highlight roll-off and black-toe
   * calibration.
   */
  public static final FujiRecipe LEICA_X =
      new Builder(
              "leica-x",
              "Leica X",
              "Carl Cruz (@carlcruzsnaps)",
              "https://carlcruzsnaps.com/leica-x.html",
              "CLASSIC-CHROME",
              "Classic Chrome")
          .dynamicRange(DynamicRange.AUTO)
          .whiteBalance(WhiteBalance.AUTO, 1, -2)
          .tone(-1, 0)
          .color(4)
          .detail(0, -4, 0)
          .grain(0, false)
          .colorChrome(2, 2)
          .exposure("0 to +1/3 EV")
          .build();

  /** True when this recipe uses Grain's phone-sensor-to-emulsion tone and perceptual colour model. */
  public boolean sensorCalibrated() {
    return "kodak-800-v3".equals(key) || "leica-x".equals(key);
  }

  /**
   * The published recipes Grain develops, in the order the film list shows them (Reggie's two
   * first). Ids are assigned in Recipe and never change.
   */
  public static final FujiRecipe[] PUBLISHED = {
    REGGIES_PORTRA, REGGIES_SUPERIA, KODAK_GOLD_200, BRIGHT_KODAK, KODAK_800_V3, LEICA_X,
    KODACHROME_64, PACIFIC_BLUES, VIBRANT_ARIZONA, CINESTILL_800T, KODAK_TRI_X_400
  };

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
    if (sonyLut(lutName)) return false; // Sony's s709 is a look, not a technical conversion
    String n = lutName.toUpperCase(java.util.Locale.ROOT);
    return n.endsWith("709") || n.startsWith("FLOG2") || n.startsWith("F-LOG");
  }

  /** LUT names Grain gives Sony's looks, so they never collide with Fujifilm's film names. */
  public static final String SONY_PREFIX = "SONY-";

  /** Sony's s709, imported from SL3SG3Ctos709.cube (S-Log3 / S-Gamut3.Cine to Rec.709). */
  public static final String SONY_S709_LUT = "SONY-S709";

  /** Sony's official download page for the s709 LUT (S-Log grading guide). */
  public static final String SONY_S709_PAGE =
      "https://support.d-imaging.sony.co.jp/support/ilc/movie/en/grading/02.html";

  public static boolean sonyLut(String lutName) {
    return lutName != null && lutName.toUpperCase(java.util.Locale.ROOT).startsWith(SONY_PREFIX);
  }

  /** Sony's look on S-Log3 rather than a Fujifilm film simulation. */
  public boolean sony() {
    return sonyLut(filmSimulation);
  }

  /**
   * Sony's s709 at standard settings: Sony's official look for its cinema cameras ("developed
   * alongside VENICE"), every adjustable setting 0 / Off, Auto white balance, Clean development.
   */
  public static final FujiRecipe SONY_S709 =
      new FujiRecipe(
          "sony-s709",
          "Sony s709",
          "Sony",
          SONY_S709_PAGE,
          SONY_S709_LUT,
          "s709",
          DynamicRange.DR100,
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
    boolean mono = monochrome(), drp = dynamicRange.priority(), sony = sony();
    String notForMono = " (not applied to black and white)";
    java.util.List<String[]> rows = new java.util.ArrayList<>();
    if (sony) rows.add(new String[] {"Look", "Sony " + filmLabel + " (S-Log3 / S-Gamut3.Cine)"});
    else {
      rows.add(new String[] {"Film simulation", filmLabel + (monoFilter > 0 ? MONO_FILTERS[monoFilter] : "")});
      rows.add(new String[] {"Dynamic range", dynamicRange.label});
    }
    rows.add(new String[] {"White balance", whiteBalance + "; R " + signed(wbShiftRed) + ", B " + signed(wbShiftBlue)});
    rows.add(new String[] {"Highlight", drp ? "set by D-Range Priority" : signed(highlight)});
    rows.add(new String[] {"Shadow", drp ? "set by D-Range Priority" : signed(shadow)});
    rows.add(new String[] {"Color", mono ? "n/a (black and white)" : signed(color)});
    rows.add(new String[] {"Sharpness", signed(sharpness)});
    rows.add(new String[] {"High ISO NR", signed(noiseReduction)});
    rows.add(new String[] {"Clarity", signed(clarity)});
    rows.add(new String[] {"Grain", grainRoughness == 0 ? "Off" : level(grainRoughness) + " / " + (grainLarge ? "Large" : "Small")});
    if (!sony) {
      rows.add(new String[] {"Color Chrome Effect", level(colorChrome) + (mono && colorChrome > 0 ? notForMono : "")});
      rows.add(new String[] {"Color Chrome FX Blue", level(colorChromeBlue) + (mono && colorChromeBlue > 0 ? notForMono : "")});
    }
    rows.add(new String[] {"Halation (Grain extra)", halation == 0 ? "Off" : String.valueOf(halation)});
    rows.add(new String[] {"Bloom (Grain extra)", bloom == 0 ? "Off" : String.valueOf(bloom)});
    rows.add(new String[] {"Development (Grain)",
        development == Development.FIRST_VERSION ? "First version (as Grain v0.2.0)" : "Clean"});
    if (!exposureNote.isEmpty())
      rows.add(new String[] {"Exposure compensation (typical, not applied)", exposureNote});
    return rows.toArray(new String[0][]);
  }
}
