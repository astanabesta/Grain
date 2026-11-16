package dev.grain.photo.develop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;

/**
 * Multi-frame merge for the MERGE drive modes. Everything here works on undeveloped linear Bayer
 * frames: no film simulation, tone curve, colour matrix or grain is applied until the very end,
 * when the single merged {@link RawFrame} is handed to the ordinary RAW developer. Developing each
 * frame first would blend four tone curves and four independently seeded grain patterns, and would
 * make the recipe impossible to change afterwards.
 *
 * <p>Two modes share the "capture first, process once" contract:
 *
 * <ul>
 *   <li><b>DETAIL</b> — same framing, sub-pixel hand jitter. Delegates to {@link HiResMerger}'s
 *       streaming super-resolution accumulator (up to 2x linear from four frames).
 *   <li><b>PANO</b> — a short guided pan. Frames are registered by a coarse-to-fine translation
 *       search, snapped to even raw pixels so the Bayer phase stays intact, then folded into one
 *       wider canvas with feathered seams and a motion gate. Translation-only: v1 asks the user to
 *       pan straight rather than rotate, so no homography (and no re-demosaic) is needed.
 * </ul>
 *
 * <p>Memory matters at 36-48 MP: four full {@code short[]} RAW frames plus a wider output canvas
 * would not fit a phone heap. Both modes therefore stream. DETAIL folds each frame into the
 * accumulator as it is read; PANO takes a {@link FrameSource} per frame (in-memory for tests,
 * {@link MergeFrameStore.Reader} for a real guided capture) and reads only the row strips it needs.
 * Pure Java, no Android classes, so the whole merge is unit-testable on the JVM.
 */
public final class MergeMerger {

  public static final int MODE_NONE = 0;
  public static final int MODE_DETAIL = 1;
  public static final int MODE_PANO = 2;

  /** Frames in a guided MERGE set. */
  public static final int FRAMES = 4;

  /** Linear unit of the merged frame (matches HiResMerger's output convention). */
  private static final int ONE_LINEAR = 8191;

  /** Fine alignment grid; coarse grid is a 4x4 box downsample of it. */
  private static final int FINE_W = 256, FINE_H = 192, COARSE_DIV = 4;
  private static final int COARSE_W = FINE_W / COARSE_DIV, COARSE_H = FINE_H / COARSE_DIV;

  /** Maximum accepted pan between consecutive frames, as a fraction of the frame size. */
  private static final float MAX_PAN_FRACTION = 0.60f;

  /** Width of the feathered seam ramp in raw pixels, as a fraction of the short edge. */
  private static final float FEATHER_FRACTION = 1f / 6f;

  /** Fallback motion gate (linear units) when the camera reported no noise profile. */
  private static final float DEFAULT_GATE = 0.09f;

  private MergeMerger() {}

  /**
   * One RAW frame of a merge, supplied as row ranges instead of one giant {@code short[]}. This is
   * what lets a four-frame PANO stitch without ever holding four full-resolution frames in memory.
   */
  public interface FrameSource extends AutoCloseable {
    int width();
    int height();
    int cfa();
    float[] black();
    float white();

    /** Copies all calibration metadata into a freshly constructed {@link RawFrame}. */
    void copyMetadataTo(RawFrame target);

    /** Reads the whole frame into a packed {@code width * height} array (alignment/noise work). */
    short[] readAll() throws IOException;

    /** Reads rows [y0,y1) into {@code dst}, packed with rowStride == width. */
    void readRows(int y0, int y1, short[] dst) throws IOException;

    /** Builds a full in-memory RawFrame from this source. */
    default RawFrame load() throws IOException {
      short[] data = readAll();
      RawFrame f = new RawFrame(data, width(), height(), width());
      copyMetadataTo(f);
      return f;
    }

    @Override void close();
  }

  public static final class Result {
    public final RawFrame frame;
    public final int framesUsed;
    /** Even raw-pixel offset of each accepted frame relative to frame 1, in canvas coordinates. */
    public final int[] offsetX, offsetY;
    public final String shifts;
    /** Fraction of (pixel, extra-frame) pairs rejected by the motion gate. */
    public final float gatedFraction;

    Result(RawFrame frame, int framesUsed, int[] offsetX, int[] offsetY, String shifts, float gated) {
      this.frame = frame;
      this.framesUsed = framesUsed;
      this.offsetX = offsetX;
      this.offsetY = offsetY;
      this.shifts = shifts;
      this.gatedFraction = gated;
    }
  }

  /** Super-resolution merge: delegates to the proven tripod accumulator, but fed a fast burst. */
  public static Result detail(List<RawFrame> frames, long targetPixels, double maxScale) {
    if (frames == null || frames.isEmpty()) throw new IllegalArgumentException("No merge frames");
    DetailAccumulator acc = new DetailAccumulator(frames.get(0), targetPixels, maxScale);
    for (int i = 1; i < frames.size(); i++) acc.add(frames.get(i));
    return acc.finish();
  }

  /** Streaming super-resolution merge; each source is loaded and released in turn. */
  public static Result detailSources(
      List<? extends FrameSource> sources, long targetPixels, double maxScale) throws IOException {
    if (sources == null || sources.isEmpty()) throw new IllegalArgumentException("No merge frames");
    DetailAccumulator acc = new DetailAccumulator(sources.get(0), targetPixels, maxScale);
    for (int i = 1; i < sources.size(); i++) acc.add(sources.get(i));
    return acc.finish();
  }

  /**
   * Folds RAW frames into HiResMerger one at a time. The accumulator itself is the streaming
   * primitive; this wrapper just supplies the receipt fields.
   */
  public static final class DetailAccumulator {
    private final HiResMerger.Accumulator acc;
    private int frames;

    public DetailAccumulator(FrameSource anchor, long targetPixels, double maxScale)
        throws IOException {
      this(anchor.load(), targetPixels, maxScale);
    }

    /** Fast path when the frame is already in memory (one captureBurst). */
    public DetailAccumulator(RawFrame anchor, long targetPixels, double maxScale) {
      acc = new HiResMerger.Accumulator(anchor, targetPixels, maxScale);
      acc.addFrame(anchor);
      frames = 1;
    }

    public void add(FrameSource source) throws IOException {
      add(source.load());
    }

    public void add(RawFrame f) {
      acc.addFrame(f);
      frames++;
    }

    public Result finish() {
      RawFrame merged = acc.finish();
      int[] ox = new int[frames], oy = new int[frames];
      return new Result(
          merged,
          frames,
          ox,
          oy,
          "detail " + frames + " frames · phases " + acc.phasesFilled() + "/9 · " + acc.shiftsSummary(),
          0f);
    }
  }

  /** Convenience path for tests: stitch ordinary in-memory RawFrames. */
  public static Result pano(List<RawFrame> frames) {
    try {
      return panoSources(wrap(frames));
    } catch (IOException e) {
      throw new RuntimeException("In-memory merge source cannot fail", e);
    }
  }

  /**
   * Translation-stitch a guided pan into one wider Bayer frame. Frames that cannot be registered
   * are dropped rather than allowed to tear the image; the first frame is always kept. Only row
   * strips are held in memory, so a 4 x 48 MP PANO never stacks four full sensor frames on the heap.
   */
  public static Result panoSources(List<? extends FrameSource> sources) throws IOException {
    if (sources == null || sources.isEmpty()) throw new IllegalArgumentException("No merge frames");
    final int all = sources.size();
    FrameSource anchor = sources.get(0);
    final int w = anchor.width(), h = anchor.height();
    if ((w & 1) != 0 || (h & 1) != 0) throw new IllegalArgumentException("RAW size must be even");
    for (int i = 0; i < all; i++) {
      if (sources.get(i).width() != w || sources.get(i).height() != h)
        throw new IllegalArgumentException("Merge frames differ in size");
    }

    float[][] fine = new float[all][];
    float[][] coarse = new float[all][];
    for (int i = 0; i < all; i++) {
      fine[i] = buildFineGrid(sources.get(i), FINE_W, FINE_H);
      coarse[i] = downsample(fine[i], FINE_W, FINE_H, COARSE_W, COARSE_H);
    }

    // Frame-measured noise model, clamped against the HAL profile, exactly like RAW stacking.
    RawFrame gateFrame = anchor.load();
    final double[] gateNoise = RawDeveloper.stackGateNoise(gateFrame);
    gateFrame = null;

    List<FrameSource> kept = new ArrayList<>();
    List<Integer> ox = new ArrayList<>(), oy = new ArrayList<>();
    kept.add(anchor);
    ox.add(0);
    oy.add(0);
    StringBuilder report = new StringBuilder("f1:(0,0)");
    int curX = 0, curY = 0;
    int prev = 0; // chain-align to the last ACCEPTED frame, so a dropped frame cannot break the chain
    for (int i = 1; i < all; i++) {
      double[] best = estimateShift(coarse[prev], coarse[i], fine[prev], fine[i], w, h);
      int dx = (int) best[0], dy = (int) best[1];
      double confidence = best[2];
      double maxSad = meanAbs(zeroMean(fine[prev])) * 0.35;
      if (Math.abs(dx) > w * MAX_PAN_FRACTION
          || Math.abs(dy) > h * MAX_PAN_FRACTION
          || confidence > maxSad) {
        report.append(String.format(Locale.US, " f%d:rejected(%d,%d,%.0f)", i + 1, dx, dy, confidence));
        continue; // not enough overlap / no match — never blend a frame we cannot place
      }
      curX += dx;
      curY += dy;
      kept.add(sources.get(i));
      ox.add(curX);
      oy.add(curY);
      prev = i;
      report.append(String.format(Locale.US, " f%d:(%+d,%+d)px", i + 1, dx, dy));
    }

    final int n = kept.size();
    int[] oxA = new int[n], oyA = new int[n];
    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
    int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
    for (int i = 0; i < n; i++) {
      oxA[i] = ox.get(i);
      oyA[i] = oy.get(i);
      minX = Math.min(minX, oxA[i]);
      maxX = Math.max(maxX, oxA[i]);
      minY = Math.min(minY, oyA[i]);
      maxY = Math.max(maxY, oyA[i]);
    }
    // The horizontal union is fully covered (consecutive pans overlap). Vertically we keep only
    // the strip covered by EVERY frame, which needs no hole filling or boundary warp.
    final int left = minX, top = maxY;
    final int outW = (maxX - minX) + w;
    final int outH = h - (maxY - minY);
    if (outW < 8 || outH < 8 || (outW & 1) != 0 || (outH & 1) != 0)
      throw new IllegalStateException("Invalid merge canvas " + outW + "x" + outH);
    final int[] tx = new int[n], ty = new int[n];
    for (int i = 0; i < n; i++) {
      tx[i] = oxA[i] - left;
      ty[i] = oyA[i] - top;
    }

    final float[][] black = new float[n][];
    final float[] white = new float[n];
    for (int i = 0; i < n; i++) {
      black[i] = kept.get(i).black();
      white[i] = kept.get(i).white();
    }
    final short[] out = new short[outW * outH];
    final float ramp = Math.max(8f, Math.min(w, h) * FEATHER_FRACTION);
    final int stripH = 64;
    int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    final LongAdder gated = new LongAdder(), compared = new LongAdder();
    try {
      List<Callable<Void>> tasks = new ArrayList<>();
      for (int y0 = 0; y0 < outH; y0 += stripH) {
        final int ys = y0, ye = Math.min(outH, y0 + stripH);
        tasks.add(
            () -> {
              // Each strip loads only the source rows that overlap it. A full 48 MP frame is never
              // resident here; four row buffers are a few megabytes at most.
              short[][] buf = new short[n][];
              int[] sy0 = new int[n], sy1 = new int[n];
              for (int i = 0; i < n; i++) {
                int a = Math.max(0, ys - ty[i]);
                int b = Math.min(h, ye - ty[i]);
                sy0[i] = a;
                sy1[i] = b;
                if (b > a) {
                  short[] rows = new short[(b - a) * w];
                  kept.get(i).readRows(a, b, rows);
                  buf[i] = rows;
                }
              }
              for (int y = ys; y < ye; y++) {
                for (int x = 0; x < outW; x++) {
                  float refVal = -1f;
                  // Lowest-index frame covering this pixel is the ghost-free reference.
                  for (int i = 0; i < n; i++) {
                    int sy = y - ty[i], sx = x - tx[i];
                    if (sx < 0 || sy < 0 || sx >= w || sy >= h || buf[i] == null) continue;
                    if (sy < sy0[i] || sy >= sy1[i]) continue;
                    int k = ((sy & 1) << 1) | (sx & 1);
                    refVal =
                        linearValue(buf[i], w, sx, sy - sy0[i], k, black[i][k], white[i]);
                    break;
                  }
                  if (refVal < 0f) continue; // never happens with the canvas above; keeps it safe
                  float sum = 0f, weightSum = 0f;
                  for (int i = 0; i < n; i++) {
                    int sy = y - ty[i], sx = x - tx[i];
                    if (sx < 0 || sy < 0 || sx >= w || sy >= h || buf[i] == null) continue;
                    if (sy < sy0[i] || sy >= sy1[i]) continue;
                    int k = ((sy & 1) << 1) | (sx & 1);
                    float v = linearValue(buf[i], w, sx, sy - sy0[i], k, black[i][k], white[i]);
                    float edge = Math.min(Math.min(sx, w - 1 - sx), Math.min(sy, h - 1 - sy));
                    float weight = Math.min(1f, edge / ramp);
                    if (weight <= 0f) continue;
                    if (i > 0) {
                      compared.increment();
                      float sigma =
                          (float) Math.sqrt(Math.max(0.0, gateNoise[0] * Math.max(0f, refVal) + gateNoise[1]));
                      float gate =
                          gateNoise[0] > 0 || gateNoise[1] > 0
                              ? Math.max(0.04f, 2.4f * 1.4142f * sigma)
                              : DEFAULT_GATE;
                      if (Math.abs(v - refVal) > gate) {
                        gated.increment();
                        continue; // moving subject: degrade to the reference, never a ghost
                      }
                    }
                    sum += weight * v;
                    weightSum += weight;
                  }
                  float value = weightSum > 0f ? sum / weightSum : refVal;
                  out[y * outW + x] = (short) Math.round(Math.max(0f, Math.min(1f, value)) * ONE_LINEAR);
                }
              }
              return null;
            });
      }
      for (Future<Void> f : pool.invokeAll(tasks)) f.get();
    } catch (Exception e) {
      throw new RuntimeException("Pano merge failed", e);
    } finally {
      pool.shutdown();
      for (FrameSource s : sources) s.close();
    }

    RawFrame merged = new RawFrame(out, outW, outH, outW);
    kept.get(0).copyMetadataTo(merged);
    merged.cfa = anchor.cfa();
    merged.black[0] = merged.black[1] = merged.black[2] = merged.black[3] = 0f;
    merged.white = ONE_LINEAR;
    merged.shading = null; // cannot be reused on a wider canvas
    merged.shadingColumns = 0;
    merged.shadingRows = 0;
    merged.faces = null; // face boxes are in the old frame's coordinates
    merged.faceScores = null;
    merged.stackCount = n;
    float gatedFraction =
        compared.sum() == 0 ? 0f : (float) gated.sum() / (float) compared.sum();
    return new Result(merged, n, oxA, oyA, report.toString(), gatedFraction);
  }

  /** Wraps an already-resident RawFrame as a FrameSource (close() is a no-op). */
  public static FrameSource source(RawFrame f) {
    return new InMemorySource(f);
  }

  /** In-memory source used by JVM tests and by any caller that already holds a RawFrame. */
  private static List<FrameSource> wrap(List<RawFrame> frames) {
    if (frames == null) throw new IllegalArgumentException("No merge frames");
    List<FrameSource> out = new ArrayList<>(frames.size());
    for (RawFrame f : frames) out.add(new InMemorySource(f));
    return out;
  }

  private static final class InMemorySource implements FrameSource {
    private final RawFrame f;

    InMemorySource(RawFrame f) { this.f = f; }

    @Override public int width() { return f.width; }
    @Override public int height() { return f.height; }
    @Override public int cfa() { return f.cfa; }
    @Override public float[] black() { return f.black; }
    @Override public float white() { return f.white; }

    @Override
    public void copyMetadataTo(RawFrame t) {
      t.cfa = f.cfa;
      System.arraycopy(f.black, 0, t.black, 0, 4);
      t.white = f.white;
      t.neutral = f.neutral == null ? new double[] {1, 1, 1} : f.neutral.clone();
      t.colorMatrix1 = copy(f.colorMatrix1);
      t.colorMatrix2 = copy(f.colorMatrix2);
      t.forwardMatrix1 = copy(f.forwardMatrix1);
      t.forwardMatrix2 = copy(f.forwardMatrix2);
      t.calibration1 = copy(f.calibration1);
      t.calibration2 = copy(f.calibration2);
      t.illuminant1 = f.illuminant1;
      t.illuminant2 = f.illuminant2;
      t.iso = f.iso;
      t.exposureNs = f.exposureNs;
      t.noiseS = copy(f.noiseS);
      t.noiseO = copy(f.noiseO);
      t.shading = f.shading == null ? null : f.shading.clone();
      t.shadingColumns = f.shadingColumns;
      t.shadingRows = f.shadingRows;
      t.shadingLeft = f.shadingLeft;
      t.shadingTop = f.shadingTop;
      t.shadingWidth = f.shadingWidth;
      t.shadingHeight = f.shadingHeight;
      t.faces = f.faces == null ? null : f.faces.clone();
      t.faceScores = f.faceScores == null ? null : f.faceScores.clone();
      t.faceSource = f.faceSource;
      t.faceEyes = f.faceEyes;
      t.faceIds = f.faceIds;
      t.faceMouths = f.faceMouths;
      t.stackCount = f.stackCount;
    }

    private static double[] copy(double[] a) { return a == null ? null : a.clone(); }

    @Override
    public synchronized short[] readAll() {
      short[] dst = new short[f.width * f.height];
      readRows(0, f.height, dst);
      return dst;
    }

    @Override
    public synchronized void readRows(int y0, int y1, short[] dst) {
      int rows = y1 - y0;
      if (y0 < 0 || y1 > f.height || rows < 0 || dst.length < rows * f.width)
        throw new IllegalArgumentException("Bad row range " + y0 + ".." + y1);
      for (int y = y0; y < y1; y++)
        System.arraycopy(f.data, y * f.rowStride, dst, (y - y0) * f.width, f.width);
    }

    @Override public void close() {}
  }

  private static float linearValue(
      short[] data, int rowStride, int x, int y, int k, float black, float white) {
    float raw = (data[y * rowStride + x] & 0xffff) - black;
    float span = Math.max(1f, white - black);
    return Math.max(0f, Math.min(1f, raw / span));
  }

  /** Box-average the Bayer mosaic into a fixed-size fine grid, reading only short row strips. */
  private static float[] buildFineGrid(FrameSource src, int gw, int gh) throws IOException {
    float[] g = new float[gw * gh];
    int[] counts = new int[gw * gh];
    int w = src.width(), h = src.height();
    float[] black = src.black();
    int chunk = Math.max(1, Math.min(64, 65536 / Math.max(1, w)));
    short[] buf = new short[chunk * w];
    for (int y0 = 0; y0 < h; y0 += chunk) {
      int y1 = Math.min(h, y0 + chunk);
      src.readRows(y0, y1, buf);
      for (int y = y0; y < y1; y++) {
        int gy = y * gh / h;
        int row = (y - y0) * w;
        for (int x = 0; x < w; x++) {
          int gx = x * gw / w;
          int k = ((y & 1) << 1) | (x & 1);
          int idx = gy * gw + gx;
          g[idx] += Math.max(0f, (buf[row + x] & 0xffff) - black[k]);
          counts[idx]++;
        }
      }
    }
    for (int i = 0; i < g.length; i++) g[i] = counts[i] > 0 ? g[i] / counts[i] : 0f;
    return g;
  }

  private static float[] downsample(float[] fine, int fw, int fh, int cw, int ch) {
    float[] c = new float[cw * ch];
    for (int y = 0; y < fh; y++) {
      int cy = y / COARSE_DIV;
      for (int x = 0; x < fw; x++) c[cy * cw + x / COARSE_DIV] += fine[y * fw + x];
    }
    int perCell = COARSE_DIV * COARSE_DIV;
    for (int i = 0; i < c.length; i++) c[i] /= perCell;
    return c;
  }

  /**
   * Coarse-to-fine mean-absolute-difference translation search. Returns the raw-pixel shift of
   * {@code cur} relative to {@code ref}, snapped to even pixels to preserve the Bayer phase.
   */
  static double[] estimateShift(float[] refC, float[] curC, float[] refF, float[] curF, int w, int h) {
    float[] aC = zeroMean(refC), bC = zeroMean(curC);
    double[] best = search(aC, bC, COARSE_W, COARSE_H,
        -COARSE_W * 3 / 5, COARSE_W * 3 / 5, -COARSE_H / 4, COARSE_H / 4);
    float[] aF = zeroMean(refF), bF = zeroMean(curF);
    int cx = (int) best[0] * COARSE_DIV, cy = (int) best[1] * COARSE_DIV;
    double[] fineBest = search(aF, bF, FINE_W, FINE_H, cx - 6, cx + 6, cy - 6, cy + 6);
    double sx = fineBest[0], sy = fineBest[1];
    // Parabolic sub-cell refinement on the SAD surface.
    sx += subCell(aF, bF, FINE_W, FINE_H, (int) fineBest[0], (int) fineBest[1], true);
    sy += subCell(aF, bF, FINE_W, FINE_H, (int) fineBest[0], (int) fineBest[1], false);
    double dxRaw = sx * (double) w / FINE_W;
    double dyRaw = sy * (double) h / FINE_H;
    return new double[] {snapEven(dxRaw), snapEven(dyRaw), fineBest[2]};
  }

  private static int snapEven(double v) {
    return (int) (Math.round(v / 2.0) * 2);
  }

  private static float[] zeroMean(float[] g) {
    double sum = 0;
    for (float v : g) sum += v;
    float mean = (float) (sum / g.length);
    float[] out = new float[g.length];
    for (int i = 0; i < g.length; i++) out[i] = g[i] - mean;
    return out;
  }

  private static double meanAbs(float[] g) {
    double s = 0;
    for (float v : g) s += Math.abs(v);
    return s / g.length;
  }

  private static double[] search(float[] a, float[] b, int gw, int gh,
      int minDx, int maxDx, int minDy, int maxDy) {
    int bestX = 0, bestY = 0;
    double bestScore = Double.MAX_VALUE;
    int minOverlap = (int) (gw * gh * 0.20);
    for (int dy = minDy; dy <= maxDy; dy++) {
      for (int dx = minDx; dx <= maxDx; dx++) {
        int x0 = Math.max(0, -dx), x1 = Math.min(gw, gw - dx);
        int y0 = Math.max(0, -dy), y1 = Math.min(gh, gh - dy);
        int count = (x1 - x0) * (y1 - y0);
        if (count < minOverlap) continue;
        double sad = 0;
        for (int y = y0; y < y1; y++) {
          int ra = (y + dy) * gw, rb = y * gw;
          for (int x = x0; x < x1; x++) sad += Math.abs(a[ra + x + dx] - b[rb + x]);
        }
        double score = sad / count;
        if (score < bestScore) {
          bestScore = score;
          bestX = dx;
          bestY = dy;
        }
      }
    }
    return new double[] {bestX, bestY, bestScore};
  }

  /** Parabolic minimum of the 3-point SAD neighbourhood; 0 when the minimum is at an edge. */
  private static double subCell(float[] a, float[] b, int gw, int gh, int cx, int cy, boolean alongX) {
    double left = sadAt(a, b, gw, gh, alongX ? cx - 1 : cx, alongX ? cy : cy - 1);
    double mid = sadAt(a, b, gw, gh, cx, cy);
    double right = sadAt(a, b, gw, gh, alongX ? cx + 1 : cx, alongX ? cy : cy + 1);
    double denom = left - 2 * mid + right;
    if (Math.abs(denom) < 1e-9) return 0;
    double delta = 0.5 * (left - right) / denom;
    return Math.max(-0.5, Math.min(0.5, delta));
  }

  private static double sadAt(float[] a, float[] b, int gw, int gh, int dx, int dy) {
    int x0 = Math.max(0, -dx), x1 = Math.min(gw, gw - dx);
    int y0 = Math.max(0, -dy), y1 = Math.min(gh, gh - dy);
    double sad = 0;
    int count = 0;
    for (int y = y0; y < y1; y++) {
      int ra = (y + dy) * gw, rb = y * gw;
      for (int x = x0; x < x1; x++) {
        sad += Math.abs(a[ra + x + dx] - b[rb + x]);
        count++;
      }
    }
    return count == 0 ? Double.MAX_VALUE : sad / count;
  }
}
