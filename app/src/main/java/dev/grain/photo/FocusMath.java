package dev.grain.photo;

/**
 * Pure geometry and scheduling math for Focus Mode (AF-C / AF-S / MF), AF-C face tracking,
 * Focus Bracketing, and the held-to-magnify Focus Check loupe. Kept free of Android SDK imports so
 * desktop smoke checks and JUnit tests exercise the exact production formulas.
 */
public final class FocusMath {
  private FocusMath() {}

  /** Focus modes exposed in MENU -> AF / FACE -> FOCUS MODE. */
  public static final int FOCUS_AFC = 0, FOCUS_AFS = 1, FOCUS_MANUAL = 2;

  public static final String[] FOCUS_MODE_NAMES = {"AF-C", "AF-S", "MANUAL"};

  /** Selectable frame counts and step widths for Focus Bracketing (FOCUS BKT). */
  public static final int[] BKT_COUNTS = {3, 5, 7};

  public static final int BKT_STEP_NARROW = 0, BKT_STEP_MEDIUM = 1, BKT_STEP_WIDE = 2;
  public static final String[] BKT_STEP_NAMES = {"NARROW", "MEDIUM", "WIDE"};
  private static final float[] BKT_DELTAS = {0.045f, 0.10f, 0.16f};

  /** Magnifier modes in MENU -> AF / FACE -> MAGNIFIER. */
  public static final int MAG_OFF = 0, MAG_4X_HOLD = 1, MAG_8X_HOLD = 2, MAG_4X_ON = 3;

  public static final String[] MAG_NAMES = {"OFF", "4× HOLD", "8× HOLD", "4× ON"};

  public static int magFactor(int magMode) {
    return magMode == MAG_8X_HOLD ? 8 : magMode == MAG_OFF ? 1 : 4;
  }

  /**
   * Computes strictly increasing focus-distance fractions in [0..1] (0 = infinity, 1 = closest
   * focus) centered around {@code startFraction}. If the window would cross 0 or 1, shifts the
   * window inward so every frame captures a distinct focus plane without duplicate endpoints.
   */
  public static float[] bracketFractions(float startFraction, int frames, int stepMode) {
    int n = Math.max(2, Math.min(9, frames));
    float center = clamp01(Float.isFinite(startFraction) ? startFraction : 0.5f);
    int mode = Math.max(0, Math.min(BKT_DELTAS.length - 1, stepMode));
    float delta = BKT_DELTAS[mode];
    float[] out = new float[n];
    float span = (n - 1) * delta;
    if (span >= 1f) {
      for (int i = 0; i < n; i++) out[i] = clamp01(i / (float) (n - 1));
      return out;
    }
    float lo = center - span * 0.5f;
    if (lo < 0f) lo = 0f;
    if (lo + span > 1f) lo = 1f - span;
    for (int i = 0; i < n; i++) {
      out[i] = clamp01(lo + i * delta);
    }
    return out;
  }

  /**
   * Sticky face selection for AF-C face tracking.
   *
   * @param boxes flat array of [left, top, right, bottom] normalized to [0..1] in active array
   * @param count number of faces (boxes.length >= count * 4)
   * @param ids optional face IDs (or null); negative means unsupported
   * @param prevId previously tracked face ID, or -1 if none/unsupported
   * @param prevCx previously tracked center X in [0..1]
   * @param prevCy previously tracked center Y in [0..1]
   * @param hasPrev whether an active face was recently tracked
   * @return chosen face index in [0..count-1], or -1 if none valid
   */
  public static int pickTrackedFace(
      float[] boxes,
      int count,
      int[] ids,
      int prevId,
      float prevCx,
      float prevCy,
      boolean hasPrev) {
    if (boxes == null || count <= 0) return -1;
    if (hasPrev && prevId >= 0 && ids != null) {
      for (int i = 0; i < count && i < ids.length; i++) {
        if (ids[i] == prevId && validBox(boxes, i)) return i;
      }
    }
    if (hasPrev) {
      int sticky = -1;
      double bestSticky = 0.28 * 0.28;
      for (int i = 0; i < count; i++) {
        if (!validBox(boxes, i)) continue;
        float cx = (boxes[i * 4] + boxes[i * 4 + 2]) * 0.5f;
        float cy = (boxes[i * 4 + 1] + boxes[i * 4 + 3]) * 0.5f;
        double dx = cx - prevCx, dy = cy - prevCy;
        double d2 = dx * dx + dy * dy;
        if (d2 <= bestSticky) {
          bestSticky = d2;
          sticky = i;
        }
      }
      if (sticky >= 0) return sticky;
    }
    int best = -1;
    double bestCenter = Double.MAX_VALUE;
    for (int i = 0; i < count; i++) {
      if (!validBox(boxes, i)) continue;
      float cx = (boxes[i * 4] + boxes[i * 4 + 2]) * 0.5f;
      float cy = (boxes[i * 4 + 1] + boxes[i * 4 + 3]) * 0.5f;
      double dx = cx - 0.5, dy = cy - 0.5;
      double d2 = dx * dx + dy * dy;
      if (d2 < bestCenter) {
        bestCenter = d2;
        best = i;
      }
    }
    return best;
  }

  private static boolean validBox(float[] boxes, int index) {
    int o = index * 4;
    return o + 3 < boxes.length && boxes[o + 2] > boxes[o] && boxes[o + 3] > boxes[o + 1];
  }

  /** Exponential smoothing for normalized [l, t, r, b] face bounds. */
  public static float[] smoothBox(float[] prev, float[] next, float alpha) {
    if (next == null || next.length < 4) return prev;
    if (prev == null || prev.length < 4) {
      return new float[] {next[0], next[1], next[2], next[3]};
    }
    float a = clamp01(alpha);
    return new float[] {
      prev[0] + (next[0] - prev[0]) * a,
      prev[1] + (next[1] - prev[1]) * a,
      prev[2] + (next[2] - prev[2]) * a,
      prev[3] + (next[3] - prev[3]) * a
    };
  }

  /** True if the tracked box center or size shifted by at least {@code threshold} in [0..1]. */
  public static boolean movedEnough(float[] submitted, float[] current, float threshold) {
    if (submitted == null || current == null || submitted.length < 4 || current.length < 4) {
      return submitted != current;
    }
    float scx = (submitted[0] + submitted[2]) * 0.5f, scy = (submitted[1] + submitted[3]) * 0.5f;
    float ccx = (current[0] + current[2]) * 0.5f, ccy = (current[1] + current[3]) * 0.5f;
    float sw = submitted[2] - submitted[0], sh = submitted[3] - submitted[1];
    float cw = current[2] - current[0], ch = current[3] - current[1];
    return Math.hypot(ccx - scx, ccy - scy) >= threshold
        || Math.abs(cw - sw) >= threshold * 1.5f
        || Math.abs(ch - sh) >= threshold * 1.5f;
  }

  /** Maps an upright normalized point [0..1] to sensor active-array normalized coordinates. */
  public static double[] uprightPointToSensor(
      double ux, double uy, int sensorRotation, boolean frontMirror) {
    double x = frontMirror ? 1.0 - ux : ux;
    double y = uy;
    int rot = Math.floorMod(sensorRotation, 360);
    double sx, sy;
    if (rot == 90) {
      sx = y;
      sy = 1.0 - x;
    } else if (rot == 270) {
      sx = 1.0 - y;
      sy = x;
    } else if (rot == 180) {
      sx = 1.0 - x;
      sy = 1.0 - y;
    } else {
      sx = x;
      sy = y;
    }
    return new double[] {clamp01d(sx), clamp01d(sy)};
  }

  /** Inverse of {@link #uprightPointToSensor}: maps sensor active-array point to upright [0..1]. */
  public static double[] sensorPointToUpright(
      double sx, double sy, int sensorRotation, boolean frontMirror) {
    int rot = Math.floorMod(sensorRotation, 360);
    double ux, uy;
    if (rot == 90) {
      ux = 1.0 - sy;
      uy = sx;
    } else if (rot == 270) {
      ux = sy;
      uy = 1.0 - sx;
    } else if (rot == 180) {
      ux = 1.0 - sx;
      uy = 1.0 - sy;
    } else {
      ux = sx;
      uy = sy;
    }
    if (frontMirror) ux = 1.0 - ux;
    return new double[] {clamp01d(ux), clamp01d(uy)};
  }

  /** Maps a normalized sensor active-array box [l, t, r, b] to upright source [l, t, r, b]. */
  public static double[] sensorBoxToUpright(
      double l, double t, double r, double b, int sensorRotation, boolean frontMirror) {
    double[] c0 = sensorPointToUpright(l, t, sensorRotation, frontMirror);
    double[] c1 = sensorPointToUpright(r, t, sensorRotation, frontMirror);
    double[] c2 = sensorPointToUpright(r, b, sensorRotation, frontMirror);
    double[] c3 = sensorPointToUpright(l, b, sensorRotation, frontMirror);
    double minX = Math.min(Math.min(c0[0], c1[0]), Math.min(c2[0], c3[0]));
    double maxX = Math.max(Math.max(c0[0], c1[0]), Math.max(c2[0], c3[0]));
    double minY = Math.min(Math.min(c0[1], c1[1]), Math.min(c2[1], c3[1]));
    double maxY = Math.max(Math.max(c0[1], c1[1]), Math.max(c2[1], c3[1]));
    return new double[] {minX, minY, maxX, maxY};
  }

  /**
   * Maps an upright full-source normalized box [l, t, r, b] through the active crop & digital zoom
   * and UI rotation into CameraBack OSD image normalized coordinates [l, t, r, b], or null if the
   * box lies outside the cropped preview window.
   */
  public static float[] uprightBoxToOsd(
      double[] uprightBox,
      int sourceWidth,
      int sourceHeight,
      String ratio,
      double zoom,
      int uiRotation) {
    if (uprightBox == null || uprightBox.length < 4 || sourceWidth <= 0 || sourceHeight <= 0) {
      return null;
    }
    int[] c = Framing.crop(sourceWidth, sourceHeight, ratio, zoom);
    double pl = (uprightBox[0] * sourceWidth - c[0]) / c[2];
    double pt = (uprightBox[1] * sourceHeight - c[1]) / c[3];
    double pr = (uprightBox[2] * sourceWidth - c[0]) / c[2];
    double pb = (uprightBox[3] * sourceHeight - c[1]) / c[3];
    if (pr <= 0.02 || pl >= 0.98 || pb <= 0.02 || pt >= 0.98) return null;
    pl = clamp01d(pl);
    pt = clamp01d(pt);
    pr = clamp01d(pr);
    pb = clamp01d(pb);
    double[][] pts = {{pl, pt}, {pr, pt}, {pr, pb}, {pl, pb}};
    int rot = Math.floorMod(uiRotation, 360);
    float minX = 1f, minY = 1f, maxX = 0f, maxY = 0f;
    for (double[] p : pts) {
      double ox, oy;
      if (rot == 90) {
        ox = 1.0 - p[1];
        oy = p[0];
      } else if (rot == 270) {
        ox = p[1];
        oy = 1.0 - p[0];
      } else if (rot == 180) {
        ox = 1.0 - p[0];
        oy = 1.0 - p[1];
      } else {
        ox = p[0];
        oy = p[1];
      }
      minX = Math.min(minX, (float) ox);
      maxX = Math.max(maxX, (float) ox);
      minY = Math.min(minY, (float) oy);
      maxY = Math.max(maxY, (float) oy);
    }
    return new float[] {clamp01(minX), clamp01(minY), clamp01(maxX), clamp01(maxY)};
  }

  /**
   * Computes the Focus Check magnifier loupe rectangle [left, top, right, bottom] inside the OSD
   * image rectangle, centered on (fx, fy) and clamped inside {@code margin} from the image edges.
   */
  public static float[] magnifierBox(
      float imgLeft, float imgTop, float imgWidth, float imgHeight, float fx, float fy, float margin) {
    float shortSide = Math.min(imgWidth, imgHeight);
    float size = Math.max(32f, Math.min(shortSide - 2f * margin, shortSide * 0.46f));
    float half = size * 0.5f;
    float cx = imgLeft + clamp01(fx) * imgWidth;
    float cy = imgTop + clamp01(fy) * imgHeight;
    float minCx = imgLeft + margin + half, maxCx = imgLeft + imgWidth - margin - half;
    float minCy = imgTop + margin + half, maxCy = imgTop + imgHeight - margin - half;
    if (minCx <= maxCx) cx = Math.max(minCx, Math.min(maxCx, cx));
    else cx = imgLeft + imgWidth * 0.5f;
    if (minCy <= maxCy) cy = Math.max(minCy, Math.min(maxCy, cy));
    else cy = imgTop + imgHeight * 0.5f;
    return new float[] {cx - half, cy - half, cx + half, cy + half};
  }

  /**
   * Computes analog focus-lever response from thumb displacement {@code (dx, dy)}.
   *
   * @return {@code [vx, vy, visX, visY, speed]} where {@code (visX, visY)} is the normalized
   *     360-degree visual cap deflection on the unit disk and {@code (vx, vy)} is the deadzone-
   *     compensated dual-slope velocity vector ({@code speed = hypot(vx, vy)} in {@code [0..1]}).
   */
  public static float[] leverResponse(float dx, float dy, float maxRadius, float deadzone) {
    float r = Math.max(1f, maxRadius);
    float dist = (float) Math.hypot(dx, dy);
    if (dist <= 1e-5f || !Float.isFinite(dist)) {
      return new float[] {0f, 0f, 0f, 0f, 0f};
    }
    float ux = dx / dist, uy = dy / dist;
    float rawMag = Math.min(1f, dist / r);
    float visX = ux * rawMag, visY = uy * rawMag;
    float dz = Math.max(0f, Math.min(0.6f, deadzone));
    if (rawMag <= dz) {
      return new float[] {0f, 0f, visX, visY, 0f};
    }
    float m = (rawMag - dz) / (1f - dz);
    float speed = 0.24f * m + 0.76f * m * m;
    return new float[] {ux * speed, uy * speed, visX, visY, speed};
  }

  /**
   * Floating-origin leash for the analog lever: once the thumb slides past {@code leashRadius},
   * pulls the virtual origin behind the thumb so reversing direction responds with zero deadzone
   * lag.
   */
  public static float[] leverFloatingOrigin(
      float originX, float originY, float touchX, float touchY, float leashRadius) {
    float leash = Math.max(1f, leashRadius);
    float dx = touchX - originX, dy = touchY - originY;
    float dist = (float) Math.hypot(dx, dy);
    if (dist <= leash || !Float.isFinite(dist)) {
      return new float[] {originX, originY};
    }
    float scale = leash / dist;
    return new float[] {touchX - dx * scale, touchY - dy * scale};
  }

  /** Advances normalized AF coordinates {@code (afX, afY)} smoothly along {@code (vx, vy)}. */
  public static float[] leverGlideStep(
      float afX, float afY, float vx, float vy, float dtSec, float maxPerSec) {
    float dt = Float.isFinite(dtSec) ? Math.max(0f, Math.min(0.05f, dtSec)) : 0f;
    float rate = Math.max(0f, maxPerSec);
    float nx = Math.max(0.06f, Math.min(0.94f, afX + vx * rate * dt));
    float ny = Math.max(0.06f, Math.min(0.94f, afY + vy * rate * dt));
    return new float[] {nx, ny};
  }

  /**
   * Maps normalized analog deflection {@code (nx, ny)} to cardinal directions ({@code 1=UP,
   * 2=RIGHT, 3=DOWN, 4=LEFT}, or {@code -1} neutral) with release and axis hysteresis so menu
   * scrolling never chatters on diagonals.
   */
  public static int leverCardinal(float nx, float ny, float threshold, int currentDir) {
    float mag = (float) Math.hypot(nx, ny);
    float enter = Math.max(0.08f, threshold);
    float limit = currentDir > 0 ? enter * 0.72f : enter;
    if (!Float.isFinite(mag) || mag < limit) return -1;
    float ax = Math.abs(nx), ay = Math.abs(ny);
    if (currentDir == 2 || currentDir == 4) {
      if (ax * 1.15f >= ay) return nx >= 0f ? 2 : 4;
    } else if (currentDir == 1 || currentDir == 3) {
      if (ay * 1.15f >= ax) return ny >= 0f ? 3 : 1;
    }
    if (ax >= ay) return nx >= 0f ? 2 : 4;
    return ny >= 0f ? 3 : 1;
  }

  private static float clamp01(float v) {
    return Math.max(0f, Math.min(1f, v));
  }

  private static double clamp01d(double v) {
    return Math.max(0.0, Math.min(1.0, v));
  }
}
