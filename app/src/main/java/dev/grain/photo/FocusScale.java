package dev.grain.photo;

import java.util.Locale;

/**
 * The manual-focus scale.
 *
 * <p>A lens focuses by moving to a dioptre value, and the distance that corresponds to a dioptre
 * value is its reciprocal: 1 dioptre is one metre, 10 dioptres is ten centimetres. So the distance
 * the lens reports is 1 / (focus fraction x minimum focus distance) metres, and infinity at zero.
 * That is why a lens barrel's distance scale is stretched at the far end and crowded at the near
 * end, and why the stops below are spaced by dioptres rather than by metres.
 */
public final class FocusScale {
  private FocusScale() {}

  /** The dioptre marks engraved on the scale, in dioptres. Infinity is zero. */
  public static final float[] STOP_DIOPTERS = {0f, 1f, 2f, 5f, 10f};

  /** The number of focus steps the lens travels in; the scale snaps to them. */
  public static final int STEPS = 30;

  /** Clamps a focus fraction into the 0..1 travel of the lens. */
  public static float clampFraction(float fraction) {
    if (Float.isNaN(fraction)) return 0f;
    return Math.max(0f, Math.min(1f, fraction));
  }

  /** The dioptre value the lens is asked for at this fraction of its travel. */
  public static float diopters(float fraction, float minFocusDistance) {
    if (!(minFocusDistance > 0f)) return 0f;
    return clampFraction(fraction) * minFocusDistance;
  }

  /** The distance in metres the lens focuses at, or positive infinity at the far end. */
  public static float metres(float fraction, float minFocusDistance) {
    float d = diopters(fraction, minFocusDistance);
    if (d <= 0f) return Float.POSITIVE_INFINITY;
    return 1f / d;
  }

  /**
   * A short engraved label for a focus position: the far end reads infinity, then metres, then
   * centimetres once the lens is closer than a metre.
   */
  public static String label(float fraction, float minFocusDistance) {
    float m = metres(fraction, minFocusDistance);
    if (!Float.isFinite(m)) return "∞";
    if (m >= 10f) return Math.round(m) + " m";
    if (m >= 1f) return trim(m) + " m";
    if (m >= 0.1f) return String.format(Locale.US, "%.2f m", m);
    return String.format(Locale.US, "%.1f cm", m * 100f);
  }

  /** How many of the engraved stops this lens can actually reach. */
  public static int stopCount(float minFocusDistance) {
    if (!(minFocusDistance > 0f)) return 1;
    int n = 0;
    for (float d : STOP_DIOPTERS) if (d <= minFocusDistance + 1e-4f) n++;
    return Math.max(1, n);
  }

  /** Where a stop sits along the travel, as a fraction from infinity (0) to closest (1). */
  public static float stopFraction(int index, float minFocusDistance) {
    if (!(minFocusDistance > 0f)) return 0f;
    int n = stopCount(minFocusDistance);
    int i = Math.max(0, Math.min(n - 1, index));
    return clampFraction(STOP_DIOPTERS[i] / minFocusDistance);
  }

  /** The distance engraved at a stop. */
  public static String stopLabel(int index, float minFocusDistance) {
    return label(stopFraction(index, minFocusDistance), minFocusDistance);
  }

  /** One decimal place at most, with the trailing zeros taken off: 3.0 becomes "3", 1.5 stays. */
  private static String trim(float value) {
    String s = String.format(Locale.US, "%.2f", value);
    if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
    if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
    return s;
  }
}
