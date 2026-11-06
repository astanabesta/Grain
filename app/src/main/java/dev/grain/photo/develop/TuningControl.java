package dev.grain.photo.develop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * One tunable film setting as the app shows it: the positions it steps through (in Fujifilm's menu
 * units), read from and written to a {@link FujiRecipe.Tuning}. Every position is a value the
 * development engine really applies.
 */
public final class TuningControl {
  public final String name;
  public final String[] labels;
  /** Shown instead of the value while the setting does not apply (e.g. Highlight under DR-P). */
  public final String disabledLabel;
  private final ToIntFunction<FujiRecipe.Tuning> index;
  private final ObjIntConsumer<FujiRecipe.Tuning> apply;
  private final Predicate<FujiRecipe.Tuning> enabled;

  private TuningControl(
      String name,
      String[] labels,
      ToIntFunction<FujiRecipe.Tuning> index,
      ObjIntConsumer<FujiRecipe.Tuning> apply) {
    this(name, labels, index, apply, null, null);
  }

  private TuningControl(
      String name,
      String[] labels,
      ToIntFunction<FujiRecipe.Tuning> index,
      ObjIntConsumer<FujiRecipe.Tuning> apply,
      Predicate<FujiRecipe.Tuning> enabled,
      String disabledLabel) {
    this.name = name;
    this.labels = labels;
    this.index = index;
    this.apply = apply;
    this.enabled = enabled;
    this.disabledLabel = disabledLabel;
  }

  /** False while another setting overrides this one (the value is kept, but not applied). */
  public boolean enabled(FujiRecipe.Tuning t) {
    return enabled == null || enabled.test(t);
  }

  /** Position of the current value, 0 .. labels.length - 1. */
  public int at(FujiRecipe.Tuning t) {
    return Math.max(0, Math.min(labels.length - 1, index.applyAsInt(t)));
  }

  /** Sets the value at a position (clamped to the range). */
  public void set(FujiRecipe.Tuning t, int position) {
    apply.accept(t, Math.max(0, Math.min(labels.length - 1, position)));
  }

  /**
   * Fujifilm's settings of a film. Black-and-white films get the Acros filter and no Color, Color
   * Chrome Effect or FX Blue (Grain does not apply those to black and white). Sony's s709 gets no
   * Dynamic range, Color Chrome Effect or FX Blue: Fujifilm-only settings, fixed off for it.
   */
  public static List<TuningControl> fujifilm(FujiRecipe film) {
    List<TuningControl> s = new ArrayList<>();
    String[] levels = {"Off", "Weak", "Strong"};
    boolean mono = film.monochrome(), sony = film.sony();
    if (mono)
      s.add(
          new TuningControl(
              "Filter",
              FujiRecipe.MONO_FILTERS.clone(),
              t -> t.monoFilter,
              (t, i) -> t.monoFilter = i));
    FujiRecipe.DynamicRange[] ranges = FujiRecipe.DynamicRange.values();
    String[] rangeLabels = new String[ranges.length];
    for (int i = 0; i < ranges.length; i++) rangeLabels[i] = ranges[i].label;
    if (!sony)
      s.add(
          new TuningControl(
              "Dynamic range",
              rangeLabels,
              t -> t.dynamicRange.ordinal(),
              (t, i) -> t.dynamicRange = FujiRecipe.DynamicRange.values()[i]));
    FujiRecipe.WhiteBalance[] modes = FujiRecipe.WhiteBalance.values();
    String[] modeLabels = new String[modes.length];
    for (int i = 0; i < modes.length; i++) modeLabels[i] = modes[i].label;
    s.add(
        new TuningControl(
            "White balance",
            modeLabels,
            t -> t.wbMode.ordinal(),
            (t, i) -> t.wbMode = FujiRecipe.WhiteBalance.values()[i]));
    String[] kelvins = new String[(FujiRecipe.KELVIN_MAX - FujiRecipe.KELVIN_MIN) / FujiRecipe.KELVIN_STEP + 1];
    for (int i = 0; i < kelvins.length; i++) kelvins[i] = (FujiRecipe.KELVIN_MIN + i * FujiRecipe.KELVIN_STEP) + "K";
    s.add(
        new TuningControl(
            "Color temperature",
            kelvins,
            t -> Math.round((t.kelvin - FujiRecipe.KELVIN_MIN) / (float) FujiRecipe.KELVIN_STEP),
            (t, i) -> t.kelvin = FujiRecipe.KELVIN_MIN + i * FujiRecipe.KELVIN_STEP,
            t -> t.wbMode == FujiRecipe.WhiteBalance.KELVIN,
            "Kelvin only"));
    s.add(
        new TuningControl(
            "WB shift red",
            signed(FujiRecipe.WB_SHIFT_MAX),
            t -> t.wbShiftRed + FujiRecipe.WB_SHIFT_MAX,
            (t, i) -> t.wbShiftRed = i - FujiRecipe.WB_SHIFT_MAX));
    s.add(
        new TuningControl(
            "WB shift blue",
            signed(FujiRecipe.WB_SHIFT_MAX),
            t -> t.wbShiftBlue + FujiRecipe.WB_SHIFT_MAX,
            (t, i) -> t.wbShiftBlue = i - FujiRecipe.WB_SHIFT_MAX));
    s.add(
        new TuningControl(
            "Highlight",
            tones(),
            t -> position(t.highlight),
            (t, i) -> t.highlight = toneAt(i),
            t -> !t.dynamicRange.priority(),
            "Set by DR-P"));
    s.add(
        new TuningControl(
            "Shadow",
            tones(),
            t -> position(t.shadow),
            (t, i) -> t.shadow = toneAt(i),
            t -> !t.dynamicRange.priority(),
            "Set by DR-P"));
    if (!mono)
      s.add(
          new TuningControl(
              "Color",
              signed(FujiRecipe.COLOR_MAX),
              t -> t.color + FujiRecipe.COLOR_MAX,
              (t, i) -> t.color = i - FujiRecipe.COLOR_MAX));
    s.add(
        new TuningControl(
            "Sharpness",
            signed(FujiRecipe.SHARPNESS_MAX),
            t -> t.sharpness + FujiRecipe.SHARPNESS_MAX,
            (t, i) -> t.sharpness = i - FujiRecipe.SHARPNESS_MAX));
    s.add(
        new TuningControl(
            "High ISO NR",
            signed(FujiRecipe.NR_MAX),
            t -> t.noiseReduction + FujiRecipe.NR_MAX,
            (t, i) -> t.noiseReduction = i - FujiRecipe.NR_MAX));
    s.add(
        new TuningControl(
            "Clarity",
            signed(FujiRecipe.CLARITY_MAX),
            t -> t.clarity + FujiRecipe.CLARITY_MAX,
            (t, i) -> t.clarity = i - FujiRecipe.CLARITY_MAX));
    s.add(
        new TuningControl(
            "Grain",
            new String[] {"Off", "Weak · Small", "Weak · Large", "Strong · Small", "Strong · Large"},
            t -> t.grainRoughness <= 0 ? 0 : 2 * Math.min(2, t.grainRoughness) - 1 + (t.grainLarge ? 1 : 0),
            (t, i) -> {
              t.grainRoughness = (i + 1) / 2;
              t.grainLarge = i == 2 || i == 4;
            }));
    if (!mono && !sony) {
      s.add(new TuningControl("Color Chrome Effect", levels, t -> t.colorChrome, (t, i) -> t.colorChrome = i));
      s.add(
          new TuningControl(
              "Color Chrome FX Blue", levels, t -> t.colorChromeBlue, (t, i) -> t.colorChromeBlue = i));
    }
    return s;
  }

  /**
   * Grain's own settings, not part of any Fujifilm film simulation: how the RAW is developed, and
   * the halation / bloom extras (Off by default).
   */
  public static List<TuningControl> extras() {
    String[] labels = new String[FujiRecipe.EXTRA_MAX + 1];
    labels[0] = "Off";
    for (int i = 1; i < labels.length; i++) labels[i] = String.valueOf(i);
    FujiRecipe.Development[] ways = FujiRecipe.Development.values();
    String[] wayLabels = new String[ways.length];
    for (int i = 0; i < ways.length; i++) wayLabels[i] = ways[i].label;
    List<TuningControl> s = new ArrayList<>();
    s.add(
        new TuningControl(
            "Development",
            wayLabels,
            t -> t.development.ordinal(),
            (t, i) -> t.development = FujiRecipe.Development.values()[i]));
    s.add(new TuningControl("Halation", labels, t -> t.halation, (t, i) -> t.halation = i));
    s.add(new TuningControl("Bloom", labels, t -> t.bloom, (t, i) -> t.bloom = i));
    return s;
  }

  private static String[] signed(int max) {
    String[] labels = new String[2 * max + 1];
    for (int i = 0; i < labels.length; i++) labels[i] = FujiRecipe.signed(i - max);
    return labels;
  }

  private static String[] tones() {
    String[] labels = new String[position(FujiRecipe.TONE_MAX) + 1];
    for (int i = 0; i < labels.length; i++) labels[i] = FujiRecipe.signed(toneAt(i));
    return labels;
  }

  /** Position of a Highlight / Shadow value. */
  private static int position(double value) {
    return (int) Math.round((value - FujiRecipe.TONE_MIN) / FujiRecipe.TONE_STEP);
  }

  private static double toneAt(int position) {
    return FujiRecipe.TONE_MIN + position * FujiRecipe.TONE_STEP;
  }
}
