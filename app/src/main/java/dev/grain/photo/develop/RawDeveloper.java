package dev.grain.photo.develop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Grain's RAW developer. Phone Bayer RAW in, 8-bit display RGB out:
 *
 * <ol>
 *   <li>black level, white level, lens-shading gains, as-shot white balance (+ recipe WB shift)
 *   <li>Malvar-He-Cutler demosaic, soft clip of green-saturated highlights toward neutral
 *   <li>DNG colour calibration to linear Rec.2020 / F-Gamut (D65), metering-based exposure
 *   <li>F-Log2 encoding plus the still-photo tone correction, DR and Highlight/Shadow offsets
 *   <li>optional chroma noise reduction in the log domain
 *   <li>Fujifilm's official F-Log2 3-D LUT for the recipe's film simulation (user-imported)
 *   <li>Color (OKLab chroma), Color Chrome Effect / FX Blue (approximations), film grain
 * </ol>
 *
 * Work is split into 32-row strips processed in parallel, each with its own buffers.
 */
public final class RawDeveloper {
  public static final String VERSION = "grain-raw-1";

  /**
   * Where Fujifilm places the centre-weighted log-average scene luminance, in F-Log2 scene-linear
   * units (median of 11 public RAF/JPEG pairs, spread 0.40 EV; fit_stills_model.py).
   */
  public static final double METERING_KEY = 0.148;

  /** Approximation: no public measurement of Fujifilm's WB-shift step exists. */
  public static final double WB_SHIFT_LOG2_PER_STEP = 0.04;

  /** Approximations of effects Fujifilm does not publish (see docs/RAW-ENGINE.md). */
  static final float CCE_C0 = 0.07f, CCE_C1 = 0.19f, CCE_STRONG = 0.045f, BLUE_STRONG = 0.05f;

  static final float GRAIN_WEAK = 0.012f, GRAIN_STRONG = 0.020f;
  static final float BLUE_A = -0.1045f, BLUE_B = -0.9945f; // OKLab hue ~264 degrees
  static final int STRIP = 32, HALO = 4;
  static final int HIST_MIN = -24, HIST_PER_EV = 8, HIST_BINS = 36 * 8;

  private RawDeveloper() {}

  public interface Sink {
    /** Rows y .. y + count - 1, packed ARGB, row stride = width. Called from worker threads. */
    void rows(int y, int count, int[] argb, int width);
  }

  public static final class Options {
    public double exposureEv;
    public long grainSeed = 1;
    public boolean grain = true;
    /** Test hooks: false = official LUT tone only; > 0 = fixed development gain instead of metering. */
    public boolean stillsTone = true;
    public double fixedGain;
    public int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
  }

  public static Map<String, Object> develop(
      RawFrame f, FujiRecipe recipe, CubeLut lut, Options o, Sink sink) throws Exception {
    long t0 = System.nanoTime();
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("engine", VERSION);
    final Job job = new Job(f, recipe, lut, o, stats);
    long t1 = System.nanoTime();
    int threads = Math.max(1, o.threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      AtomicInteger next = new AtomicInteger();
      int strips = (f.height + STRIP - 1) / STRIP;
      List<Callable<Void>> tasks = new ArrayList<>();
      for (int t = 0; t < threads; t++)
        tasks.add(
            () -> {
              Worker w = new Worker(job);
              for (int i = next.getAndIncrement(); i < strips; i = next.getAndIncrement())
                w.strip(i * STRIP, Math.min(f.height, i * STRIP + STRIP), sink);
              return null;
            });
      for (Future<Void> fu : pool.invokeAll(tasks)) fu.get();
    } finally {
      pool.shutdownNow();
    }
    long t2 = System.nanoTime();
    stats.put("setupMs", Math.round((t1 - t0) / 1e6));
    stats.put("developMs", Math.round((t2 - t1) / 1e6));
    stats.put("threads", threads);
    return stats;
  }

  /** Everything shared by all strips: calibration, tables, textures. */
  static final class Job {
    final RawFrame f;
    final CubeLut lut;
    final int W, H;
    final float[] black = new float[4], scale = new float[4], gainK = new float[4];
    final int[] colorK = new int[4], shadeK = new int[4];
    final float clipFactor;
    final float[] m = new float[9];
    final ToneTable tone;
    final boolean shading;
    final int[] sc0;
    final float[] sfx;
    final int nrRadius;
    final boolean post;
    final float chroma, cceDepth, blueDepth, grainAmp;
    final float[] grainA, grainB;
    final int[] mod997;
    final float[] srgbDecode = new float[4097], srgbEncode = new float[2049];

    Job(RawFrame f, FujiRecipe r, CubeLut lut, Options o, Map<String, Object> stats) {
      this.f = f;
      this.lut = lut;
      W = f.width;
      H = f.height;
      for (int k = 0; k < 4; k++) {
        int x = k & 1, y = k >> 1;
        black[k] = f.black[k];
        scale[k] = 1f / Math.max(1f, f.white - f.black[k]);
        colorK[k] = f.color(x, y);
        shadeK[k] = f.shadingChannel(x, y);
      }
      double nR = f.neutral[0], nG = f.neutral[1], nB = f.neutral[2];
      if (!(nR > 0 && nG > 0 && nB > 0)) throw new IllegalArgumentException("Invalid neutral");
      double shiftR = Math.pow(2, WB_SHIFT_LOG2_PER_STEP * r.wbShiftRed);
      double shiftB = Math.pow(2, WB_SHIFT_LOG2_PER_STEP * r.wbShiftBlue);
      double gR = nG / nR * shiftR, gB = nG / nB * shiftB;
      for (int k = 0; k < 4; k++) gainK[k] = (float) (colorK[k] == 0 ? gR : colorK[k] == 2 ? gB : 1);
      clipFactor = (float) Math.min(1, Math.min(gR, gB));
      stats.put("wbGains", new double[] {round(gR, 4), 1, round(gB, 4)});
      stats.put("wbShiftSteps", new int[] {r.wbShiftRed, r.wbShiftBlue});
      stats.put("wbShiftLog2PerStep", WB_SHIFT_LOG2_PER_STEP);

      ColorMath.Result cm =
          ColorMath.cameraToRec2020(
              f.neutral,
              f.colorMatrix1,
              f.colorMatrix2,
              f.forwardMatrix1,
              f.forwardMatrix2,
              f.calibration1,
              f.calibration2,
              f.illuminant1,
              f.illuminant2);
      stats.put("sceneCct", Math.round(cm.cct));
      stats.put("sceneWhiteXy", new double[] {round(cm.whiteX, 5), round(cm.whiteY, 5)});
      stats.put("illuminantWeight1", round(cm.weight1, 4));
      stats.put("usedForwardMatrix", cm.usedForwardMatrix);
      stats.put("cameraToRec2020", roundAll(cm.cameraToRec2020, 5));

      shading = f.shading != null && f.shadingColumns >= 2 && f.shadingRows >= 2;
      sc0 = new int[W];
      sfx = new float[W];
      if (shading) {
        double span = Math.max(1, f.shadingWidth - 1);
        for (int x = 0; x < W; x++) {
          double u = (x - f.shadingLeft) / span * (f.shadingColumns - 1);
          u = Math.max(0, Math.min(f.shadingColumns - 1, u));
          int c0 = Math.min((int) u, f.shadingColumns - 2);
          sc0[x] = c0;
          sfx[x] = (float) (u - c0);
        }
      }
      stats.put(
          "lensShading",
          shading ? f.shadingColumns + "x" + f.shadingRows + " gain map applied" : "not available");

      // --- metering (centre-weighted log average, as fitted on Fujifilm JPEGs)
      double[] hist = new double[HIST_BINS];
      double[] meter = meter(cm.cameraToRec2020, hist);
      double logAverage = meter[0], rawClipped = meter[1];
      double gain = METERING_KEY * Math.pow(2, o.exposureEv) / Math.max(1e-6, logAverage);
      gain = Math.max(1 / 64.0, Math.min(512, gain));
      if (o.fixedGain > 0) gain = o.fixedGain;
      for (int i = 0; i < 9; i++) m[i] = (float) (cm.cameraToRec2020[i] * gain);
      stats.put("meterLogAverage", round(logAverage, 6));
      stats.put("meterKey", METERING_KEY);
      stats.put("exposureEv", o.exposureEv);
      stats.put("developmentGain", round(gain, 4));
      stats.put("developmentGainEv", round(Math.log(gain) / Math.log(2), 3));
      stats.put("rawClippedFraction", round(rawClipped, 5));

      // --- dynamic range and the white point
      double gEv = Math.log(gain) / Math.log(2);
      double clipEv = Math.log(gain * clipFactor / 0.18) / Math.log(2);
      ToneModel.Curve probe = ToneModel.curve(0, r.highlight, r.shadow, o.stillsTone, 1);
      double whiteEv = ToneModel.whiteEv(probe, lut);
      double above = fractionAbove(hist, whiteEv - gEv);
      int steps;
      switch (r.dynamicRange) {
        case DR200:
          steps = 1;
          break;
        case DR400:
          steps = 2;
          break;
        case DR100:
          steps = 0;
          break;
        default:
          steps = above > 0.01 ? 1 : 0;
      }
      stats.put(
          "dynamicRange",
          (r.dynamicRange == FujiRecipe.DynamicRange.AUTO ? "Auto: DR" : "DR") + (100 << steps));
      if (r.dynamicRange == FujiRecipe.DynamicRange.AUTO)
        stats.put("dynamicRangeAutoRule", "DR200 when >1% of the frame would render white at DR100 (approximation)");
      stats.put("sceneFractionAboveWhite", round(above, 5));
      stats.put("curveWhiteEv", round(whiteEv, 3));
      stats.put("rawClipEv", round(clipEv, 3));
      double shoulder = 1;
      if (o.stillsTone && clipEv < whiteEv) {
        double target = Math.max(ToneModel.KNEE_EV + 0.75, clipEv);
        shoulder = (whiteEv - ToneModel.KNEE_EV) / (target - ToneModel.KNEE_EV);
      }
      stats.put("shoulderScale", round(shoulder, 4));
      final ToneModel.Curve curve = ToneModel.curve(steps, r.highlight, r.shadow, o.stillsTone, shoulder);
      tone =
          new ToneTable(
              x -> {
                double v = curve.apply(FLog2.encode(x));
                return v < 0 ? 0 : v > 1 ? 1 : v;
              });
      stats.put("toneModel", ToneModel.VERSION);
      stats.put("toneOffsetsAtKnots", curve.knots());

      int iso = Math.max(50, f.iso);
      nrRadius = r.noiseReduction <= -4 ? (iso <= 200 ? 1 : iso <= 800 ? 2 : iso <= 3200 ? 3 : 4)
          : Math.min(HALO, (iso <= 200 ? 1 : iso <= 800 ? 2 : iso <= 3200 ? 3 : 4) + Math.max(0, r.noiseReduction + 4) / 3);
      stats.put("chromaNoiseRadius", nrRadius);

      chroma = (float) (1 + ToneModel.COLOR_KAPPA * r.color);
      cceDepth = r.colorChrome == 2 ? CCE_STRONG : r.colorChrome == 1 ? CCE_STRONG / 2 : 0;
      blueDepth = r.colorChromeBlue == 2 ? BLUE_STRONG : r.colorChromeBlue == 1 ? BLUE_STRONG / 2 : 0;
      post = chroma != 1f || cceDepth > 0 || blueDepth > 0;
      stats.put("colorChromaScale", round(chroma, 4));
      stats.put("colorChromeDepth", cceDepth);
      stats.put("colorChromeBlueDepth", blueDepth);

      float amp = !o.grain || r.grainRoughness == 0 ? 0 : r.grainRoughness == 2 ? GRAIN_STRONG : GRAIN_WEAK;
      if (r.grainLarge) amp *= 1.15f;
      grainAmp = amp;
      float sigma = r.grainLarge ? 1.1f : 0.55f;
      grainA = noise(1024, sigma, o.grainSeed);
      grainB = noise(997, sigma, o.grainSeed * 31 + 7);
      mod997 = new int[Math.max(W, H)];
      for (int i = 0; i < mod997.length; i++) mod997[i] = i % 997;
      stats.put("grainAmplitude", grainAmp);
      stats.put("grainSeed", o.grainSeed);

      for (int i = 0; i <= 4096; i++) {
        double c = i / 4096.0;
        srgbDecode[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
      }
      for (int i = 0; i <= 2048; i++) {
        double u = i / 2048.0, lin = u * u;
        srgbEncode[i] = (float) (lin <= 0.0031308 ? 12.92 * lin : 1.055 * Math.pow(lin, 1 / 2.4) - 0.055);
      }
      stats.put("lut", lut.title.isEmpty() ? r.filmSimulation : lut.title);
      stats.put("lutGamut", lut.gamut);
      stats.put("lutModel", lut.model);
    }

    /** Gain of one shading channel along row y into out[0..W). */
    void shadingRow(int ch, int y, float[] tmp, float[] out) {
      if (!shading) {
        java.util.Arrays.fill(out, 0, W, 1f);
        return;
      }
      int cols = f.shadingColumns, rows = f.shadingRows;
      double v = (y - f.shadingTop) / (double) Math.max(1, f.shadingHeight - 1) * (rows - 1);
      v = Math.max(0, Math.min(rows - 1, v));
      int r0 = Math.min((int) v, rows - 2);
      float fr = (float) (v - r0);
      float[] map = f.shading;
      for (int c = 0; c < cols; c++) {
        float a = map[(r0 * cols + c) * 4 + ch], b = map[((r0 + 1) * cols + c) * 4 + ch];
        tmp[c] = a + fr * (b - a);
      }
      for (int x = 0; x < W; x++) {
        int c0 = sc0[x];
        out[x] = tmp[c0] + sfx[x] * (tmp[c0 + 1] - tmp[c0]);
      }
    }

    float shadingAt(int ch, int x, int y) {
      if (!shading) return 1f;
      int cols = f.shadingColumns, rows = f.shadingRows;
      double v = (y - f.shadingTop) / (double) Math.max(1, f.shadingHeight - 1) * (rows - 1);
      v = Math.max(0, Math.min(rows - 1, v));
      int r0 = Math.min((int) v, rows - 2);
      float fr = (float) (v - r0);
      int c0 = sc0[x];
      float fx = sfx[x];
      float[] map = f.shading;
      float a = map[(r0 * cols + c0) * 4 + ch], b = map[(r0 * cols + c0 + 1) * 4 + ch];
      float c = map[((r0 + 1) * cols + c0) * 4 + ch], d = map[((r0 + 1) * cols + c0 + 1) * 4 + ch];
      float top = a + fx * (b - a), bottom = c + fx * (d - c);
      return top + fr * (bottom - top);
    }

    /** {log-average luminance, clipped fraction} on a sparse grid of 2x2 quads; fills a log2 histogram. */
    double[] meter(double[] cam, double[] hist) {
      double sumW = 0, sumL = 0;
      long n = 0, clipped = 0;
      float[] q = new float[3];
      double cx = W / 2.0, cy = H / 2.0;
      float sat = f.white - 0.02f * (f.white - Math.min(Math.min(f.black[0], f.black[1]), Math.min(f.black[2], f.black[3])));
      for (int y = 0; y + 1 < H; y += 8) {
        for (int x = 0; x + 1 < W; x += 8) {
          q[0] = q[1] = q[2] = 0;
          boolean clip = false;
          float gShade = 1;
          for (int k = 0; k < 4; k++) {
            int xx = x + (k & 1), yy = y + (k >> 1);
            int raw = f.data[yy * f.rowStride + xx] & 0xffff;
            if (raw >= sat) clip = true;
            float s = shadingAt(shadeK[k], xx, yy);
            if (colorK[k] == 1) gShade = s;
            float v = (raw - black[k]) * scale[k] * s * gainK[k];
            q[colorK[k]] += colorK[k] == 1 ? 0.5f * v : v;
          }
          float t = gShade * clipFactor;
          if (clip) for (int c = 0; c < 3; c++) q[c] = Math.min(q[c], t);
          double R = cam[0] * q[0] + cam[1] * q[1] + cam[2] * q[2];
          double G = cam[3] * q[0] + cam[4] * q[1] + cam[5] * q[2];
          double B = cam[6] * q[0] + cam[7] * q[1] + cam[8] * q[2];
          double Y = 0.2627 * R + 0.6780 * G + 0.0593 * B;
          double rr = Math.hypot((x - cx) / cx, (y - cy) / cy);
          double w = 1 + 2 * Math.exp(-(rr * rr) / (2 * 0.35 * 0.35));
          sumW += w;
          sumL += w * Math.log(Math.max(Y, 1e-5));
          int bin = (int) ((Math.log(Math.max(Y, 1e-9)) / Math.log(2) - HIST_MIN) * HIST_PER_EV);
          hist[bin < 0 ? 0 : bin >= HIST_BINS ? HIST_BINS - 1 : bin]++;
          n++;
          if (clip) clipped++;
        }
      }
      return new double[] {Math.exp(sumL / Math.max(1e-9, sumW)), n == 0 ? 0 : clipped / (double) n};
    }

    float decode(float c) {
      if (c <= 0) return 0;
      if (c >= 1) return 1;
      float p = c * 4096;
      int i = (int) p;
      if (i >= 4096) i = 4095;
      return srgbDecode[i] + (p - i) * (srgbDecode[i + 1] - srgbDecode[i]);
    }

    float encode(float lin) {
      if (lin <= 0) return 0;
      if (lin >= 1) return 1;
      float p = (float) Math.sqrt(lin) * 2048;
      int i = (int) p;
      if (i >= 2048) i = 2047;
      return srgbEncode[i] + (p - i) * (srgbEncode[i + 1] - srgbEncode[i]);
    }
  }

  /** Per-thread buffers and the strip pipeline. */
  static final class Worker {
    final Job j;
    final int W, Wp;
    final float[] cfa, cr, cg, cb, tu, tv, gRow, aRow, bRow, tmp;
    final int[] argb;
    final float[] lutOut = new float[3];

    Worker(Job j) {
      this.j = j;
      W = j.W;
      Wp = W + 4;
      int rowsIn = STRIP + 2 * HALO + 4, rowsMid = STRIP + 2 * HALO;
      cfa = new float[rowsIn * Wp];
      cr = new float[rowsMid * W];
      cg = new float[rowsMid * W];
      cb = new float[rowsMid * W];
      tu = new float[rowsMid * W];
      tv = new float[rowsMid * W];
      gRow = new float[W];
      aRow = new float[W];
      bRow = new float[W];
      tmp = new float[Math.max(2, j.shading ? j.f.shadingColumns : 2)];
      argb = new int[STRIP * W];
    }

    int reflect(int y, int n) {
      if (y < 0) y = -y;
      if (y >= n) y = 2 * n - 2 - y;
      return Math.max(0, Math.min(n - 1, y));
    }

    void strip(int y0, int y1, Sink sink) {
      final RawFrame f = j.f;
      final int H = j.H, n = y1 - y0, rowsIn = n + 2 * HALO + 4, rowsMid = n + 2 * HALO;
      // 1) CFA rows: black, scale, shading, white balance
      for (int r = 0; r < rowsIn; r++) {
        int y = reflect(y0 - HALO - 2 + r, H);
        int chEven = j.shadeK[(y & 1) << 1], chOdd = j.shadeK[((y & 1) << 1) | 1];
        j.shadingRow(chEven, y, tmp, aRow);
        j.shadingRow(chOdd, y, tmp, bRow);
        int k0 = (y & 1) << 1;
        float b0 = j.black[k0], b1 = j.black[k0 + 1];
        float s0 = j.scale[k0] * j.gainK[k0], s1 = j.scale[k0 + 1] * j.gainK[k0 + 1];
        int src = y * f.rowStride, dst = r * Wp + 2;
        short[] data = f.data;
        for (int x = 0; x < W; x += 2) {
          cfa[dst + x] = ((data[src + x] & 0xffff) - b0) * s0 * aRow[x];
          cfa[dst + x + 1] = ((data[src + x + 1] & 0xffff) - b1) * s1 * bRow[x + 1];
        }
        int row = r * Wp;
        cfa[row] = cfa[row + 4];
        cfa[row + 1] = cfa[row + 3];
        cfa[row + W + 2] = cfa[row + W];
        cfa[row + W + 3] = cfa[row + W - 1];
      }
      // 2) demosaic + highlight handling + colour matrix + exposure + F-Log2/tone -> codes
      final float[] m = j.m;
      final float clipF = j.clipFactor;
      for (int r = 0; r < rowsMid; r++) {
        int y = y0 - HALO + r, yy = reflect(y, H);
        int cRow = (r + 2) * Wp + 2;
        int gch = j.shadeK[((yy & 1) << 1)] == 1 || j.shadeK[((yy & 1) << 1)] == 2
            ? j.shadeK[(yy & 1) << 1] : j.shadeK[((yy & 1) << 1) | 1];
        j.shadingRow(gch, yy, tmp, gRow);
        int out = r * W;
        int k0 = (yy & 1) << 1;
        for (int x = 0; x < W; x++) {
          int i = cRow + x;
          int c = j.colorK[k0 | (x & 1)];
          float p0 = cfa[i];
          float R, G, B;
          float n4 = cfa[i - 1] + cfa[i + 1] + cfa[i - Wp] + cfa[i + Wp];
          float far = cfa[i - 2] + cfa[i + 2] + cfa[i - 2 * Wp] + cfa[i + 2 * Wp];
          if (c == 1) {
            G = p0;
            float diag = cfa[i - Wp - 1] + cfa[i - Wp + 1] + cfa[i + Wp - 1] + cfa[i + Wp + 1];
            float h2 = cfa[i - 2] + cfa[i + 2], v2 = cfa[i - 2 * Wp] + cfa[i + 2 * Wp];
            float hv = (5 * p0 + 4 * (cfa[i - 1] + cfa[i + 1]) - diag - h2 + 0.5f * v2) * 0.125f;
            float vv = (5 * p0 + 4 * (cfa[i - Wp] + cfa[i + Wp]) - diag - v2 + 0.5f * h2) * 0.125f;
            int hc = j.colorK[k0 | ((x + 1) & 1)];
            if (hc == 0) {
              R = hv;
              B = vv;
            } else {
              B = hv;
              R = vv;
            }
          } else {
            float diag = cfa[i - Wp - 1] + cfa[i - Wp + 1] + cfa[i + Wp - 1] + cfa[i + Wp + 1];
            G = (4 * p0 + 2 * n4 - far) * 0.125f;
            float other = (6 * p0 + 2 * diag - 1.5f * far) * 0.125f;
            if (c == 0) {
              R = p0;
              B = other;
            } else {
              B = p0;
              R = other;
            }
          }
          // green-saturated highlights: pull R and B toward G so clipped areas stay neutral
          float t = gRow[x] * clipF;
          if (G > 0.9f * t) {
            float w = (G - 0.9f * t) / (0.08f * t);
            if (w > 1) w = 1;
            if (R > G) R += w * (G - R);
            if (B > G) B += w * (G - B);
          }
          float lr = m[0] * R + m[1] * G + m[2] * B;
          float lg = m[3] * R + m[4] * G + m[5] * B;
          float lb = m[6] * R + m[7] * G + m[8] * B;
          cr[out + x] = j.tone.apply(lr);
          cg[out + x] = j.tone.apply(lg);
          cb[out + x] = j.tone.apply(lb);
        }
      }
      // 3) chroma noise reduction (log domain, luminance preserving)
      int rad = j.nrRadius;
      if (rad > 0) chromaBlur(rowsMid, rad);
      // 4) LUT, colour effects, grain -> ARGB
      final float amp = j.grainAmp;
      final float[] ga = j.grainA, gb = j.grainB;
      for (int r = 0; r < n; r++) {
        int y = y0 + r, src = (r + HALO) * W, dst = r * W;
        int rowA = (y & 1023) << 10, rowB = j.mod997[y] * 997;
        for (int x = 0; x < W; x++) {
          j.lut.sample(cr[src + x], cg[src + x], cb[src + x], lutOut);
          float vr = lutOut[0], vg = lutOut[1], vb = lutOut[2];
          if (j.post) {
            float lr = j.decode(vr), lg = j.decode(vg), lb = j.decode(vb);
            float l = (float) Math.cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb);
            float mm = (float) Math.cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb);
            float s = (float) Math.cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb);
            float L = 0.2104542553f * l + 0.7936177850f * mm - 0.0040720468f * s;
            float A = 1.9779984951f * l - 2.4285922050f * mm + 0.4505937099f * s;
            float Bb = 0.0259040371f * l + 0.7827717662f * mm - 0.8086757660f * s;
            if (j.cceDepth > 0 || j.blueDepth > 0) {
              float C = (float) Math.sqrt(A * A + Bb * Bb);
              float w = C <= CCE_C0 ? 0 : C >= CCE_C1 ? 1 : (C - CCE_C0) / (CCE_C1 - CCE_C0);
              w = w * w * (3 - 2 * w);
              float dL = j.cceDepth * w;
              if (j.blueDepth > 0 && C > 1e-4f) {
                float hb = (A * BLUE_A + Bb * BLUE_B) / C;
                if (hb > 0) {
                  hb *= hb;
                  dL += j.blueDepth * w * hb * hb;
                }
              }
              L -= dL * L;
            }
            A *= j.chroma;
            Bb *= j.chroma;
            float l2 = L + 0.3963377774f * A + 0.2158037573f * Bb;
            float m2 = L - 0.1055613458f * A - 0.0638541728f * Bb;
            float s2 = L - 0.0894841775f * A - 1.2914855480f * Bb;
            l2 = l2 * l2 * l2;
            m2 = m2 * m2 * m2;
            s2 = s2 * s2 * s2;
            vr = j.encode(4.0767416621f * l2 - 3.3077115913f * m2 + 0.2309699292f * s2);
            vg = j.encode(-1.2684380046f * l2 + 2.6097574011f * m2 - 0.3413193965f * s2);
            vb = j.encode(-0.0041960863f * l2 - 0.7034186147f * m2 + 1.7076147010f * s2);
          }
          float nz = (ga[rowA + (x & 1023)] + gb[rowB + j.mod997[x]]) * 0.70710678f;
          float d = nz * (0.5f / 255f);
          if (amp > 0) {
            float lum = 0.25f * (vr + 2 * vg + vb);
            float wl = 4 * lum * (1 - lum);
            if (wl < 0) wl = 0;
            d += amp * (float) Math.sqrt(wl) * nz;
          }
          int ir = (int) ((vr + d) * 255f + 0.5f), ig = (int) ((vg + d) * 255f + 0.5f), ib = (int) ((vb + d) * 255f + 0.5f);
          ir = ir < 0 ? 0 : ir > 255 ? 255 : ir;
          ig = ig < 0 ? 0 : ig > 255 ? 255 : ig;
          ib = ib < 0 ? 0 : ib > 255 ? 255 : ib;
          argb[dst + x] = 0xff000000 | (ir << 16) | (ig << 8) | ib;
        }
      }
      sink.rows(y0, n, argb, W);
    }

    /** Box-blur the two opponent channels (r - Y, b - Y) in place for the strip's own rows. */
    void chromaBlur(int rowsMid, int rad) {
      float inv = 1f / (2 * rad + 1);
      for (int r = 0; r < rowsMid; r++) {
        int o = r * W;
        float su = 0, sv = 0;
        for (int k = -rad; k <= rad; k++) {
          int x = Math.max(0, Math.min(W - 1, k));
          float Y = 0.25f * (cr[o + x] + 2 * cg[o + x] + cb[o + x]);
          su += cr[o + x] - Y;
          sv += cb[o + x] - Y;
        }
        for (int x = 0; x < W; x++) {
          tu[o + x] = su * inv;
          tv[o + x] = sv * inv;
          int xa = Math.min(W - 1, x + rad + 1), xr = Math.max(0, x - rad);
          float Ya = 0.25f * (cr[o + xa] + 2 * cg[o + xa] + cb[o + xa]);
          float Yr = 0.25f * (cr[o + xr] + 2 * cg[o + xr] + cb[o + xr]);
          su += (cr[o + xa] - Ya) - (cr[o + xr] - Yr);
          sv += (cb[o + xa] - Ya) - (cb[o + xr] - Yr);
        }
      }
      for (int r = HALO; r < rowsMid - HALO; r++) {
        int o = r * W;
        for (int x = 0; x < W; x++) {
          float su = 0, sv = 0;
          for (int k = -rad; k <= rad; k++) {
            int q = (r + k) * W + x;
            su += tu[q];
            sv += tv[q];
          }
          float Y = 0.25f * (cr[o + x] + 2 * cg[o + x] + cb[o + x]);
          float rr = Y + su * inv, bb = Y + sv * inv;
          cr[o + x] = rr;
          cb[o + x] = bb;
          cg[o + x] = (4 * Y - rr - bb) * 0.5f;
        }
      }
    }
  }

  /** Fraction of histogram samples whose log2 luminance is above the threshold. */
  static double fractionAbove(double[] hist, double log2Threshold) {
    double total = 0, above = 0;
    int start = (int) Math.ceil((log2Threshold - HIST_MIN) * HIST_PER_EV);
    for (int i = 0; i < hist.length; i++) {
      total += hist[i];
      if (i >= start) above += hist[i];
    }
    return total == 0 ? 0 : above / total;
  }

  /** Tileable unit-variance Gaussian noise, blurred to the requested grain size. */
  static float[] noise(int size, float sigma, long seed) {
    float[] a = new float[size * size];
    long s = seed * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
    for (int i = 0; i < a.length; i += 2) {
      s ^= s << 13;
      s ^= s >>> 7;
      s ^= s << 17;
      double u1 = ((s >>> 11) + 1) * 0x1.0p-53;
      s ^= s << 13;
      s ^= s >>> 7;
      s ^= s << 17;
      double u2 = (s >>> 11) * 0x1.0p-53;
      double rr = Math.sqrt(-2 * Math.log(u1));
      a[i] = (float) (rr * Math.cos(2 * Math.PI * u2));
      if (i + 1 < a.length) a[i + 1] = (float) (rr * Math.sin(2 * Math.PI * u2));
    }
    int rad = Math.max(1, (int) Math.ceil(sigma * 3));
    float[] k = new float[2 * rad + 1];
    float ks = 0;
    for (int i = -rad; i <= rad; i++) ks += k[i + rad] = (float) Math.exp(-(i * i) / (2.0 * sigma * sigma));
    for (int i = 0; i < k.length; i++) k[i] /= ks;
    float[] b = new float[a.length];
    for (int y = 0; y < size; y++)
      for (int x = 0; x < size; x++) {
        float v = 0;
        for (int i = -rad; i <= rad; i++) v += k[i + rad] * a[y * size + Math.floorMod(x + i, size)];
        b[y * size + x] = v;
      }
    double sum = 0, sq = 0;
    for (int y = 0; y < size; y++)
      for (int x = 0; x < size; x++) {
        float v = 0;
        for (int i = -rad; i <= rad; i++) v += k[i + rad] * b[Math.floorMod(y + i, size) * size + x];
        a[y * size + x] = v;
        sum += v;
        sq += (double) v * v;
      }
    double mean = sum / a.length, sd = Math.sqrt(Math.max(1e-12, sq / a.length - mean * mean));
    for (int i = 0; i < a.length; i++) a[i] = (float) ((a[i] - mean) / sd);
    return a;
  }

  static double round(double v, int digits) {
    double p = Math.pow(10, digits);
    return Math.round(v * p) / p;
  }

  static double[] roundAll(double[] v, int digits) {
    double[] o = new double[v.length];
    for (int i = 0; i < v.length; i++) o[i] = round(v[i], digits);
    return o;
  }
}
