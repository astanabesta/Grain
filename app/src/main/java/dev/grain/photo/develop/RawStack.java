package dev.grain.photo.develop;

import java.util.List;

/**
 * Multi-frame RAW stacking: aligns a burst of back-to-back Bayer frames to the first (reference)
 * frame and averages them with a per-pixel motion gate. Merging happens on raw mosaic samples with
 * identical exposure/ISO, so every downstream stage (demosaic, noise model, grading) runs unchanged
 * — it just sees a frame whose noise variance is divided by the number of contributing frames.
 *
 * <p>Motion safety by construction (Google HDR+ style): a pixel where a candidate frame deviates
 * from the reference by more than ~2.4 sigma of the frame's raw noise model is excluded from the
 * average at that pixel. A moving subject therefore costs the noise win locally but can never
 * ghost or blur: the merged output degrades gracefully to the reference frame exactly there.
 *
 * <p>Alignment is global integer translation estimated on quarter-resolution luminance thumbnails
 * (all four CFA samples per 2x2 block averaged). Shifts are multiples of THUMB_DIV, i.e. always
 * even in raw coordinates, which keeps the Bayer phase of every frame intact; no resampling of raw
 * data ever happens.
 */
public final class RawStack {
  private RawStack() {}

  /** Thumbnails are W/4 — a thumb step is 4 raw px, so every accepted shift keeps the CFA phase. */
  static final int THUMB_DIV = 4;

  /** +/- search radius in thumb px (56 raw px): comfortably beyond OIS-stabilised hand jitter. */
  static final int MAX_SHIFT = 14;

  /** Motion gate: tolerate up to GATE_K sigma of raw noise before calling a pixel "moved". */
  static final float GATE_K = 2.4f, GATE_FLAT = 1.5f;

  /** Coarse search stride in thumb px, then a +/-2 px refinement at single-px stride. */
  static final int COARSE_STEP = 2, REFINE_RADIUS = 2;

  public static final class Out {
    public final short[] data;
    /** Frames that were merged (frames whose alignment search never left range are all kept). */
    public int framesUsed;
    /** Raw-px translation applied to each input frame to align it to the reference (index 0). */
    public final int[] shiftX, shiftY;
    /** Fraction of (pixel, non-reference frame) pairs rejected by the motion gate. */
    public float gatedFraction;
    /** Mean absolute shift magnitude actually applied, raw px (diagnostic for the receipt). */
    public float meanShift;

    public Out(short[] data, int frames) {
      this.data = data;
      shiftX = new int[frames];
      shiftY = new int[frames];
    }
  }

  /**
   * Merges packed Bayer frames (stride == width, identical size and exposure) into one raw mosaic.
   * frames.get(0) is the reference (its full metadata is kept by the caller). {@code nS}/{@code nO}
   * are the Camera2 noise-profile coefficients (variance of the black-subtracted value normalised
   * to [0,1]); black/white are the frame's levels. Never throws on mismatched content — pixels that
   * cannot line up fall back to the reference.
   */
  public static Out merge(
      List<short[]> frames, int w, int h, int stride, float black, float white, double nS, double nO) {
    short[] ref = frames.get(0);
    if (frames.size() <= 1) {
      Out o = new Out(ref.clone(), 1);
      o.framesUsed = 1;
      return o;
    }
    if (stride != w)
      throw new IllegalArgumentException("RawStack.merge wants packed Bayer rows (stride == width)");
    final int n = frames.size();

    short[] refThumb = thumb(ref, w, h);
    final int tw = w / THUMB_DIV, th = h / THUMB_DIV;
    int[] sx = new int[n], sy = new int[n];
    for (int k = 1; k < n; k++) {
      int[] s = findShift(refThumb, thumb(frames.get(k), w, h), tw, th);
      sx[k] = s[0];
      sy[k] = s[1];
    }

    final float blackToFull = white - black;
    final int[] sum = new int[w * h];
    final byte[] cnt = new byte[w * h];
    // fold the reference in everywhere: a pixel can always use at least the reference frame
    for (int i = 0; i < w * h; i++) {
      sum[i] = ref[i] & 0xffff;
      cnt[i] = 1;
    }
    long gated = 0;
    for (int k = 1; k < n; k++) {
      short[] cur = frames.get(k);
      int dx = sx[k], dy = sy[k];
      long localGated = 0;
      for (int y = 0; y < h; y++) {
        int cy = y + dy;
        int ro = y * w, co = cy * w;
        boolean rowOk = cy >= 0 && cy < h;
        for (int x = 0; x < w; x++) {
          int cx = x + dx;
          if (!rowOk || cx < 0 || cx >= w) {
            localGated++;
            continue;
          }
          int r = ref[ro + x] & 0xffff;
          int v = cur[co + cx] & 0xffff;
          double xn = (r - black) / (double) blackToFull;
          if (xn < 0) xn = 0;
          double tol = GATE_K * blackToFull * Math.sqrt(Math.max(0, nS * xn) + nO) + GATE_FLAT;
          if (Math.abs(v - r) <= tol) {
            sum[ro + x] += v;
            cnt[ro + x]++;
          } else {
            localGated++;
          }
        }
      }
      gated += localGated;
    }

    short[] out = new short[w * h];
    for (int i = 0; i < w * h; i++) out[i] = (short) ((sum[i] + (cnt[i] >> 1)) / cnt[i]);
    Out o = new Out(out, n);
    o.framesUsed = n;
    System.arraycopy(sx, 0, o.shiftX, 0, n);
    System.arraycopy(sy, 0, o.shiftY, 0, n);
    o.gatedFraction = (float) (gated / (double) ((n - 1) * (long) w * h));
    int acc = 0;
    for (int k = 1; k < n; k++) acc += Math.abs(sx[k]) + Math.abs(sy[k]);
    o.meanShift = n > 1 ? acc / (float) (n - 1) : 0f;
    return o;
  }

  /**
   * Copies a RawFrame onto new pixel data (a merged stack), preserving every calibration field the
   * HAL published with the reference frame. Arrays are shared read-only, as downstream stages only
   * consume them.
   */
  public static RawFrame withData(RawFrame src, short[] data) {
    RawFrame f = new RawFrame(data, src.width, src.height, src.width);
    f.cfa = src.cfa;
    System.arraycopy(src.black, 0, f.black, 0, 4);
    f.white = src.white;
    f.shading = src.shading;
    f.shadingColumns = src.shadingColumns;
    f.shadingRows = src.shadingRows;
    f.shadingLeft = src.shadingLeft;
    f.shadingTop = src.shadingTop;
    f.shadingWidth = src.shadingWidth;
    f.shadingHeight = src.shadingHeight;
    f.neutral = src.neutral;
    f.colorMatrix1 = src.colorMatrix1;
    f.colorMatrix2 = src.colorMatrix2;
    f.forwardMatrix1 = src.forwardMatrix1;
    f.forwardMatrix2 = src.forwardMatrix2;
    f.calibration1 = src.calibration1;
    f.calibration2 = src.calibration2;
    f.illuminant1 = src.illuminant1;
    f.illuminant2 = src.illuminant2;
    f.iso = src.iso;
    f.exposureNs = src.exposureNs;
    f.noiseS = src.noiseS;
    f.noiseO = src.noiseO;
    f.faces = src.faces;
    f.faceScores = src.faceScores;
    f.faceSource = src.faceSource;
    f.faceEyes = src.faceEyes;
    f.faceIds = src.faceIds;
    f.faceMouths = src.faceMouths;
    f.stackCount = src.stackCount;
    return f;
  }

  /** Quarter-resolution luminance thumbnail: mean of the 4 CFA samples of every 2x2 block pair. */
  private static short[] thumb(short[] d, int w, int h) {
    int tw = w / THUMB_DIV, th = h / THUMB_DIV;
    short[] t = new short[tw * th];
    for (int y = 0; y < th; y++) {
      int o = y * THUMB_DIV * w, to = y * tw;
      for (int x = 0; x < tw; x++) {
        int xx = x * THUMB_DIV;
        // 4x4 raw block -> one thumb sample (16 taps of a block average would be pointless here:
        // the 2x2 CFAs of the four corner blocks approximate it at a sixth of the cost)
        int a = (d[o + xx] & 0xffff) + (d[o + xx + 1] & 0xffff) + (d[o + w + xx] & 0xffff)
            + (d[o + w + xx + 1] & 0xffff);
        int b = (d[o + 2 * w + xx + 2] & 0xffff) + (d[o + 2 * w + xx + 3] & 0xffff)
            + (d[o + 3 * w + xx + 2] & 0xffff) + (d[o + 3 * w + xx + 3] & 0xffff);
        t[to + x] = (short) ((a + b) >> 3);
      }
    }
    return t;
  }

  /**
   * Two-stage exhaustive integer search on the thumbnails, coarse (stride COARSE_STEP across the
   * full range) then refine (+/-REFINE_RADIUS, stride 1). Cost is minimised by comparing every
   * second thumb sample in both axes over the central 70% window. Returns the translation in raw px
   * (thumb shift times THUMB_DIV) — by construction a multiple of 4, always CFA-phase safe. If the
   * best shift scores worse than the identity shift, the frame is effectively identical or
   * unalignable; identity is used and the motion gate decides per pixel.
   */
  private static int[] findShift(short[] a, short[] b, int tw, int th) {
    int bestX = 0, bestY = 0;
    double best = Double.MAX_VALUE;
    for (int dy = -MAX_SHIFT; dy <= MAX_SHIFT; dy += COARSE_STEP)
      for (int dx = -MAX_SHIFT; dx <= MAX_SHIFT; dx += COARSE_STEP) {
        double s = score(a, b, tw, th, dx, dy);
        if (s < best) {
          best = s;
          bestX = dx;
          bestY = dy;
        }
      }
    for (int dy = bestY - REFINE_RADIUS; dy <= bestY + REFINE_RADIUS; dy++)
      for (int dx = bestX - REFINE_RADIUS; dx <= bestX + REFINE_RADIUS; dx++) {
        double s = score(a, b, tw, th, dx, dy);
        if (s < best) {
          best = s;
          bestX = dx;
          bestY = dy;
        }
      }
    double identity = score(a, b, tw, th, 0, 0);
    if (best > identity * 1.03) {
      bestX = 0;
      bestY = 0;
    }
    return new int[] {bestX * THUMB_DIV, bestY * THUMB_DIV};
  }

  /** Mean absolute difference of the thumbnails under a shift, on the central 70% window. */
  private static double score(short[] a, short[] b, int tw, int th, int dx, int dy) {
    int x0 = (int) (tw * 0.15), x1 = (int) (tw * 0.85);
    int y0 = (int) (th * 0.15), y1 = (int) (th * 0.85);
    long sum = 0;
    int n = 0;
    for (int y = y0; y < y1; y += 2) {
      int by = y + dy;
      if (by < 0 || by >= th) continue;
      int ao = y * tw, bo = by * tw;
      for (int x = x0; x < x1; x += 2) {
        int bx = x + dx;
        if (bx < 0 || bx >= tw) continue;
        sum += Math.abs((a[ao + x] & 0xffff) - (b[bo + bx] & 0xffff));
        n++;
      }
    }
    return n == 0 ? Double.MAX_VALUE : sum / (double) n;
  }
}
