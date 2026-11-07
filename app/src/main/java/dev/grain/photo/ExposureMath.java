package dev.grain.photo;

/**
 * Pure camera exposure math for Program Auto (P), Shutter Priority (S), ISO Priority (ISO), and
 * Manual (M) modes, plus live preview exposure capping and closed-loop scene luma tracking.
 *
 * <p>Kept free of Android SDK dependencies so {@code check-source.sh}, {@code SmokeTest}, and host
 * unit tests verify every mode directly.
 */
public final class ExposureMath {
  /** Fallback neutral scene exposure (1/30 s at ISO 200) before the first AE frame arrives. */
  public static final double DEFAULT_NEUTRAL_NS_ISO = 33_333_333.0 * 200.0;

  /**
   * Longest per-frame shutter time applied to the live viewfinder (1/8 s = 125 ms, ~8 fps). Slower
   * dial speeds (1/4 s, 1/2 s, 1") compensate preview ISO proportionally so preview brightness
   * still matches the dial while keeping the viewfinder responsive; still captures always use the
   * exact dial shutter time.
   */
  public static final long MAX_PREVIEW_EXPOSURE_NS = 125_000_000L;

  /** Linear sRGB decode table (0..255 -> 0..1) for center-weighted preview metering. */
  private static final float[] SRGB_TO_LINEAR = new float[256];

  static {
    for (int i = 0; i < 256; i++) {
      double s = i / 255.0;
      SRGB_TO_LINEAR[i] =
          (float) (s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4));
    }
  }

  private ExposureMath() {}

  /** Flash mode constants shared between {@code GrainCamera}, {@code MainActivity}, and OSD. */
  public static final int FLASH_OFF = 0;
  public static final int FLASH_AUTO = 1;
  public static final int FLASH_ON = 2;
  public static final int FLASH_TORCH = 3;

  public static final String[] FLASH_NAMES = {"OFF", "AUTO", "FILL ON", "TORCH"};
  public static final String[] FLASH_SHORT = {"⚡ OFF", "⚡ AUTO", "⚡ ON", "⚡ TORCH"};
  public static final String[] FLASH_RECEIPT = {"off", "auto", "on", "torch"};

  /** Exposure mode badge for the viewfinder OSD. */
  public static String modeLabel(long shutterNs, int iso) {
    if (shutterNs > 0 && iso > 0) return "M";
    if (shutterNs > 0) return "S";
    if (iso > 0) return "ISO";
    return "P";
  }

  /** Resolved sensor exposure parameters for a manual or priority capture. */
  public static final class Plan {
    public final String mode;
    public final long shutterNs;
    public final int iso;
    public final int captureIso;
    public final double neutralNsIso;
    public final double shownEv;
    public final double developEv;
    public final boolean outOfRange;

    Plan(
        String mode,
        long shutterNs,
        int iso,
        int captureIso,
        double neutralNsIso,
        double shownEv,
        double developEv,
        boolean outOfRange) {
      this.mode = mode;
      this.shutterNs = shutterNs;
      this.iso = iso;
      this.captureIso = captureIso;
      this.neutralNsIso = neutralNsIso;
      this.shownEv = shownEv;
      this.developEv = developEv;
      this.outOfRange = outOfRange;
    }
  }

  /**
   * Resolves still-capture shutter speed, ISO, RAW headroom ISO, and EV offset from neutral.
   * Returns {@code null} when both dials are on Auto ({@code P}).
   */
  public static Plan planStill(
      long shutterNs,
      int isoSetting,
      double ev,
      double headroomEv,
      double sceneNeutralNsIso,
      long minNs,
      long maxNs,
      int minIso,
      int maxIso) {
    if (shutterNs <= 0 && isoSetting <= 0) return null;
    double neutral =
        Double.isFinite(sceneNeutralNsIso) && sceneNeutralNsIso > 1.0
            ? sceneNeutralNsIso
            : DEFAULT_NEUTRAL_NS_ISO;
    double safeEv = Double.isFinite(ev) ? Math.max(-6.0, Math.min(6.0, ev)) : 0.0;
    double target = neutral * Math.pow(2.0, safeEv);
    long loNs = Math.max(1L, minNs);
    long hiNs = Math.max(loNs, maxNs);
    int loIso = Math.max(1, minIso);
    int hiIso = Math.max(loIso, maxIso);

    long time;
    int iso;
    String mode;
    boolean warn = false;
    if (shutterNs > 0 && isoSetting > 0) {
      time = clampLong(shutterNs, loNs, hiNs);
      iso = clampInt(isoSetting, loIso, hiIso);
      mode = "manual";
    } else if (shutterNs > 0) {
      time = clampLong(shutterNs, loNs, hiNs);
      double wantIso = target / time;
      iso = clampInt((int) Math.round(wantIso), loIso, hiIso);
      warn = wantIso < loIso * 0.97 || wantIso > hiIso * 1.03;
      mode = "shutter priority";
    } else {
      iso = clampInt(isoSetting, loIso, hiIso);
      double wantNs = target / iso;
      time = clampLong(Math.round(wantNs), loNs, hiNs);
      warn = wantNs < loNs * 0.97 || wantNs > hiNs * 1.03;
      mode = "ISO priority";
    }

    int captureIso =
        headroomEv < 0.0
            ? clampInt((int) Math.round(iso * Math.pow(2.0, headroomEv)), loIso, hiIso)
            : iso;
    double rawEv = Math.log((time * (double) iso) / neutral) / Math.log(2.0);
    double clampedEv =
        Double.isFinite(rawEv) ? Math.max(-6.0, Math.min(6.0, rawEv)) : 0.0;
    return new Plan(mode, time, iso, captureIso, neutral, clampedEv, clampedEv, warn);
  }

  /**
   * Computes {@code {previewExposureNs, previewIso, frameDurationNs}} for the live viewfinder when
   * manual or priority exposure is active.
   */
  public static long[] planPreview(
      long stillShutterNs, int stillIso, long minNs, long maxNs, int minIso, int maxIso) {
    long loNs = Math.max(1L, minNs);
    long hiNs = Math.max(loNs, maxNs);
    int loIso = Math.max(1, minIso);
    int hiIso = Math.max(loIso, maxIso);
    long capNs = Math.max(loNs, Math.min(hiNs, MAX_PREVIEW_EXPOSURE_NS));
    long pTime = clampLong(stillShutterNs, loNs, capNs);
    int pIso = clampInt(stillIso, loIso, hiIso);
    if (stillShutterNs > pTime && pTime > 0) {
      double boost = stillShutterNs / (double) pTime;
      pIso = clampInt((int) Math.round(stillIso * boost), loIso, hiIso);
    }
    long frameDurationNs = Math.max(33_333_333L, pTime);
    return new long[] {pTime, pIso, frameDurationNs};
  }

  /**
   * Center-weighted mean linear luminance in [0..1] of an ARGB thumbnail ({@code w * h} pixels).
   */
  public static double meanLinearLuma(int[] argb, int w, int h) {
    if (argb == null || w <= 0 || h <= 0 || argb.length < w * h) return -1.0;
    int x0 = w / 4, x1 = w - x0;
    int y0 = h / 4, y1 = h - y0;
    double sum = 0.0;
    double weightSum = 0.0;
    for (int y = 0; y < h; y++) {
      boolean cy = y >= y0 && y < y1;
      int row = y * w;
      for (int x = 0; x < w; x++) {
        int c = argb[row + x];
        float r = SRGB_TO_LINEAR[(c >> 16) & 255];
        float g = SRGB_TO_LINEAR[(c >> 8) & 255];
        float b = SRGB_TO_LINEAR[c & 255];
        double yLin = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        double wgt = (cy && x >= x0 && x < x1) ? 2.5 : 1.0;
        sum += yLin * wgt;
        weightSum += wgt;
      }
    }
    return weightSum > 0.0 ? sum / weightSum : -1.0;
  }

  /**
   * Updates the estimated neutral scene exposure ({@code ns * ISO} for 18% middle grey) while the
   * preview runs with {@code CONTROL_AE_MODE_OFF}. Uses stop-space smoothing so priority modes
   * converge smoothly without oscillation.
   */
  public static double refineSceneNeutral(
      double currentNeutral,
      double appliedPreviewNsIso,
      double meanLinearY,
      double minNsIso,
      double maxNsIso) {
    double base =
        Double.isFinite(currentNeutral) && currentNeutral > 1.0
            ? currentNeutral
            : DEFAULT_NEUTRAL_NS_ISO;
    if (!Double.isFinite(appliedPreviewNsIso)
        || appliedPreviewNsIso <= 1.0
        || !Double.isFinite(meanLinearY)
        || meanLinearY < 0.0) {
      return base;
    }
    double lo = Math.max(1.0, minNsIso * 0.25);
    double hi = Math.max(lo * 2.0, maxNsIso * 4.0);
    double implied;
    if (meanLinearY <= 0.006) {
      // Crushed dark preview: scene needs more exposure than currently applied.
      implied = Math.max(base, appliedPreviewNsIso * 2.5);
    } else if (meanLinearY >= 0.90) {
      // Clipped bright preview: scene needs less exposure than currently applied.
      implied = Math.min(base, appliedPreviewNsIso * 0.35);
    } else {
      implied = appliedPreviewNsIso / (meanLinearY / 0.18);
    }
    implied = Math.max(lo, Math.min(hi, implied));
    double blended = Math.exp(0.68 * Math.log(base) + 0.32 * Math.log(implied));
    return Math.max(lo, Math.min(hi, blended));
  }

  private static long clampLong(long v, long lo, long hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static int clampInt(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
