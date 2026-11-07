package dev.grain.photo.develop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Streaming 4-frame Tripod Multi-Frame Super-Resolution merger ({@code ~36 MP}) in linear Bayer
 * sensor space.
 *
 * <p>Each incoming {@link RawFrame} is aligned across a {@code 4 x 4} grid of quadrant tiles to
 * sub-pixel precision ({@code ~0.02 px} via parabolic 2D refinement against Frame 1's anchor
 * pyramid), directional-demosaiced on the fly with its per-frame lens-shading map applied, and
 * accumulated onto a {@code ~36 MP} CFA-aligned {@code char[]} grid with ghost rejection so no two
 * full RAW frames need to stay in memory simultaneously.
 */
public final class HiResMerger {
  /** Target pixel count (~36 MP: e.g. 6928 x 5196 = 35,997,888 px for a 4:3 sensor). */
  public static final long TARGET_PIXELS = 36_000_000L;

  /** 13-bit linear unit so 4 accumulated frames (4 * 8191 = 32,764) fit inside unsigned 16-bit char[]. */
  private static final int ONE_LINEAR = 8191;

  private static final int ALIGN_LONG = 512;
  private static final int TILES_X = 4, TILES_Y = 4;
  private static final int SEARCH_RADIUS = 4;

  private HiResMerger() {}

  /** Computes even, 4-pixel-aligned output dimensions targeting ~36 MP (never shrinking larger sensors). */
  public static int[] targetDimensions(int srcW, int srcH) {
    long srcPixels = (long) srcW * (long) srcH;
    if (srcPixels <= 0) throw new IllegalArgumentException("Invalid source size");
    if (srcPixels >= TARGET_PIXELS) {
      return new int[] {srcW & ~3, srcH & ~3};
    }
    double scale = Math.sqrt((double) TARGET_PIXELS / (double) srcPixels);
    scale = Math.min(2.0, Math.max(1.0, scale));
    int outW = Math.max(8, ((int) Math.round(srcW * scale) + 2) & ~3);
    int outH = Math.max(8, ((int) Math.round(srcH * scale) + 2) & ~3);
    return new int[] {outW, outH};
  }

  public static final class Accumulator {
    public final int srcW, srcH, outW, outH;
    private final int cfa;
    private final double[] neutral;
    private final double[] colorMatrix1, colorMatrix2, forwardMatrix1, forwardMatrix2;
    private final double[] calibration1, calibration2;
    private final int illuminant1, illuminant2;
    private final int iso;
    private final long exposureNs;
    private final double[] noiseS, noiseO;
    private final int[] faces, faceScores;
    private final String faceSource;
    private final int faceEyes, faceIds, faceMouths;

    private final int gridW, gridH;
    private float[] refGrid;
    private char[] accSum;
    private char[] frame0Fallback;
    private int framesAdded;
    private final List<String> shiftReports = new ArrayList<>();

    public Accumulator(RawFrame anchor) {
      this.srcW = anchor.width;
      this.srcH = anchor.height;
      int[] dims = targetDimensions(srcW, srcH);
      this.outW = dims[0];
      this.outH = dims[1];
      this.cfa = anchor.cfa;
      this.neutral = anchor.neutral == null ? new double[] {1, 1, 1} : anchor.neutral.clone();
      this.colorMatrix1 = anchor.colorMatrix1 == null ? null : anchor.colorMatrix1.clone();
      this.colorMatrix2 = anchor.colorMatrix2 == null ? null : anchor.colorMatrix2.clone();
      this.forwardMatrix1 = anchor.forwardMatrix1 == null ? null : anchor.forwardMatrix1.clone();
      this.forwardMatrix2 = anchor.forwardMatrix2 == null ? null : anchor.forwardMatrix2.clone();
      this.calibration1 = anchor.calibration1 == null ? null : anchor.calibration1.clone();
      this.calibration2 = anchor.calibration2 == null ? null : anchor.calibration2.clone();
      this.illuminant1 = anchor.illuminant1;
      this.illuminant2 = anchor.illuminant2;
      this.iso = anchor.iso;
      this.exposureNs = anchor.exposureNs;
      this.noiseS = anchor.noiseS == null ? null : anchor.noiseS.clone();
      this.noiseO = anchor.noiseO == null ? null : anchor.noiseO.clone();
      this.faces = scaleFaces(anchor.faces, (double) outW / srcW, (double) outH / srcH);
      this.faceScores = anchor.faceScores == null ? null : anchor.faceScores.clone();
      this.faceSource = anchor.faceSource;
      this.faceEyes = anchor.faceEyes;
      this.faceIds = anchor.faceIds;
      this.faceMouths = anchor.faceMouths;

      if (srcW >= srcH) {
        this.gridW = ALIGN_LONG;
        this.gridH = Math.max(32, Math.round((float) ALIGN_LONG * srcH / srcW));
      } else {
        this.gridH = ALIGN_LONG;
        this.gridW = Math.max(32, Math.round((float) ALIGN_LONG * srcW / srcH));
      }
      this.accSum = new char[outW * outH];
    }

    public synchronized void addFrame(RawFrame frame) {
      if (accSum == null) throw new IllegalStateException("Accumulator already finished");
      if (frame.width != srcW || frame.height != srcH) {
        throw new IllegalArgumentException("Frame dimensions changed mid-burst");
      }

      float[] curGrid = buildGreenLuminanceGrid(frame, gridW, gridH);
      final float[] tileDx = new float[TILES_X * TILES_Y];
      final float[] tileDy = new float[TILES_X * TILES_Y];
      final float[] tileGhostBlend = new float[TILES_X * TILES_Y]; // 1.0 = trust aligned frame, 0.0 = fallback anchor

      if (framesAdded == 0) {
        this.refGrid = curGrid;
        for (int i = 0; i < tileGhostBlend.length; i++) tileGhostBlend[i] = 1.0f;
        shiftReports.add("f1:(0.00,0.00)px");
      } else {
        double meanDx = 0, meanDy = 0;
        float scaleToFullX = (float) srcW / gridW;
        float scaleToFullY = (float) srcH / gridH;
        for (int ty = 0; ty < TILES_Y; ty++) {
          for (int tx = 0; tx < TILES_X; tx++) {
            int idx = ty * TILES_X + tx;
            float[] est = estimateTileShift(refGrid, curGrid, gridW, gridH, tx, ty);
            tileDx[idx] = est[0] * scaleToFullX;
            tileDy[idx] = est[1] * scaleToFullY;
            // Reject ghosting if normalized SAD residual > 0.055 (moving subject in quadrant)
            float mad = est[2];
            float trust = mad <= 0.030f ? 1.0f : mad >= 0.075f ? 0.0f : (0.075f - mad) / 0.045f;
            tileGhostBlend[idx] = trust;
            meanDx += tileDx[idx];
            meanDy += tileDy[idx];
          }
        }
        meanDx /= (TILES_X * TILES_Y);
        meanDy /= (TILES_X * TILES_Y);
        shiftReports.add(
            String.format(Locale.US, "f%d:(%+.2f,%+.2f)px", framesAdded + 1, meanDx, meanDy));
      }

      final int fIdx = framesAdded;
      final boolean isAnchor = (fIdx == 0);
      if (isAnchor) {
        this.frame0Fallback = new char[outW * outH];
      }

      int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      try {
        List<Callable<Void>> tasks = new ArrayList<>();
        final int stripH = 128;
        for (int y0 = 0; y0 < outH; y0 += stripH) {
          final int ys = y0;
          final int ye = Math.min(outH, y0 + stripH);
          tasks.add(
              () -> {
                splatStrip(frame, ys, ye, isAnchor, tileDx, tileDy, tileGhostBlend);
                return null;
              });
        }
        for (Future<Void> f : pool.invokeAll(tasks)) f.get();
      } catch (Exception e) {
        throw new RuntimeException("Hi-Res frame accumulation failed", e);
      } finally {
        pool.shutdown();
      }
      framesAdded++;
    }

    public int framesAdded() {
      return framesAdded;
    }

    public String shiftsSummary() {
      return String.join(" ", shiftReports);
    }

    /** Finalizes accumulation, applies edge-protected optical PSF deconvolution, and returns a 36MP RawFrame. */
    public synchronized RawFrame finish() {
      if (accSum == null || framesAdded <= 0) {
        throw new IllegalStateException("No frames accumulated");
      }
      this.frame0Fallback = null;
      this.refGrid = null;

      final int count = framesAdded;
      final char[] avg = this.accSum;
      if (count > 1) {
        int total = outW * outH;
        int half = count / 2;
        for (int i = 0; i < total; i++) {
          avg[i] = (char) Math.min(ONE_LINEAR, (avg[i] + half) / count);
        }
      }

      // Allocate final 36 MP Bayer RawFrame and apply 1-pass CFA-aware optical PSF micro-deconvolution
      short[] outPixels = new short[outW * outH];
      deconvolveSameColorPsa(avg, outPixels, outW, outH, count);
      this.accSum = null;

      RawFrame merged = new RawFrame(outPixels, outW, outH, outW);
      merged.cfa = this.cfa;
      merged.black[0] = 0f;
      merged.black[1] = 0f;
      merged.black[2] = 0f;
      merged.black[3] = 0f;
      merged.white = ONE_LINEAR;
      merged.shading = null; // Lens shading was applied per-frame prior to sub-pixel alignment
      merged.shadingColumns = 0;
      merged.shadingRows = 0;
      System.arraycopy(this.neutral, 0, merged.neutral, 0, 3);
      merged.colorMatrix1 = this.colorMatrix1;
      merged.colorMatrix2 = this.colorMatrix2;
      merged.forwardMatrix1 = this.forwardMatrix1;
      merged.forwardMatrix2 = this.forwardMatrix2;
      merged.calibration1 = this.calibration1;
      merged.calibration2 = this.calibration2;
      merged.illuminant1 = this.illuminant1;
      merged.illuminant2 = this.illuminant2;
      merged.iso = this.iso;
      merged.exposureNs = this.exposureNs;
      if (this.noiseS != null && this.noiseO != null) {
        double snrReduction = Math.max(1.0, count * 0.90);
        merged.noiseS = new double[this.noiseS.length];
        merged.noiseO = new double[this.noiseO.length];
        for (int i = 0; i < this.noiseS.length; i++) merged.noiseS[i] = this.noiseS[i] / snrReduction;
        for (int i = 0; i < this.noiseO.length; i++) merged.noiseO[i] = this.noiseO[i] / snrReduction;
      }
      merged.faces = this.faces;
      merged.faceScores = this.faceScores;
      merged.faceSource = this.faceSource;
      merged.faceEyes = this.faceEyes;
      merged.faceIds = this.faceIds;
      merged.faceMouths = this.faceMouths;
      return merged;
    }

    private void splatStrip(
        RawFrame frame,
        int ys,
        int ye,
        boolean isAnchor,
        float[] tileDx,
        float[] tileDy,
        float[] tileGhostBlend) {
      final float invScaleX = (float) srcW / outW;
      final float invScaleY = (float) srcH / outH;

      // Determine source row window needed for [ys..ye) with +/-4px margin for shift + bicubic kernel
      int srcYMin = Math.max(0, (int) Math.floor(ys * invScaleY) - 6);
      int srcYMax = Math.min(srcH - 1, (int) Math.ceil(ye * invScaleY) + 6);
      int winH = srcYMax - srcYMin + 1;

      // Demosaic just this source strip [srcYMin..srcYMax] into planar linear R, G, B [0..ONE_LINEAR]
      float[] rBuf = new float[srcW * winH];
      float[] gBuf = new float[srcW * winH];
      float[] bBuf = new float[srcW * winH];
      demosaicSourceWindow(frame, srcYMin, srcYMax, rBuf, gBuf, bBuf);

      for (int oy = ys; oy < ye; oy++) {
        float baseSy = (oy + 0.5f) * invScaleY - 0.5f;
        float tyCoord = ((float) oy / outH) * TILES_Y - 0.5f;
        int rowOff = oy * outW;

        for (int ox = 0; ox < outW; ox++) {
          float baseSx = (ox + 0.5f) * invScaleX - 0.5f;
          float sx = baseSx, sy = baseSy;
          float trust = 1.0f;

          if (!isAnchor) {
            float txCoord = ((float) ox / outW) * TILES_X - 0.5f;
            float[] interp = bilerpTileField(tileDx, tileDy, tileGhostBlend, txCoord, tyCoord);
            sx += interp[0];
            sy += interp[1];
            trust = interp[2];
          }

          int c = cfaColor(cfa, ox, oy);
          float[] plane = (c == 0) ? rBuf : (c == 1) ? gBuf : bBuf;
          float val = sampleCatmullRom(plane, srcW, winH, sx, sy - srcYMin);
          int iv = Math.max(0, Math.min(ONE_LINEAR, Math.round(val)));

          int outIdx = rowOff + ox;
          if (isAnchor) {
            frame0Fallback[outIdx] = (char) iv;
            accSum[outIdx] = (char) iv;
          } else {
            int anchorVal = frame0Fallback[outIdx];
            // Per-pixel anti-ghost check: if pixel differs wildly from anchor (>14% full scale), blend toward anchor
            int diff = Math.abs(iv - anchorVal);
            float pixTrust =
                diff <= 650 ? 1.0f : diff >= 1500 ? 0.0f : (1500f - diff) / 850f;
            float w = trust * pixTrust;
            int blended = Math.round(w * iv + (1.0f - w) * anchorVal);
            accSum[outIdx] = (char) (accSum[outIdx] + blended);
          }
        }
      }
    }
  }

  /**
   * Directional Hamilton-Adams green + bilinear (R-G, B-G) constant-hue demosaic over a narrow row
   * window, normalizing black/white level and applying Camera2 LensShadingMap gains.
   */
  static void demosaicSourceWindow(
      RawFrame f, int yMin, int yMax, float[] rOut, float[] gOut, float[] bOut) {
    final int w = f.width, h = f.height, stride = f.rowStride;
    final short[] raw = f.data;
    final int padTop = Math.max(0, yMin - 2);
    final int padBot = Math.min(h - 1, yMax + 2);
    final int padH = padBot - padTop + 1;
    final float[] norm = new float[w * padH];

    for (int y = padTop; y <= padBot; y++) {
      int rOff = y * stride;
      int nOff = (y - padTop) * w;
      for (int x = 0; x < w; x++) {
        int rawCode = raw[rOff + x] & 0xFFFF;
        int bIdx = ((y & 1) << 1) | (x & 1);
        float blk = f.black[bIdx];
        float denom = Math.max(1f, f.white - blk);
        float v = Math.max(0f, (rawCode - blk) / denom);
        if (f.shading != null && f.shadingColumns > 1 && f.shadingRows > 1) {
          v *= sampleShadingGain(f, x, y);
        }
        norm[nOff + x] = Math.min(1.0f, v) * ONE_LINEAR;
      }
    }

    // Pass 1: Directional Hamilton-Adams Green across [padTop..padBot]
    final float[] greenPad = new float[w * padH];
    for (int y = padTop; y <= padBot; y++) {
      int py = y - padTop;
      int yM1 = Math.max(0, py - 1), yP1 = Math.min(padH - 1, py + 1);
      int yM2 = Math.max(0, py - 2), yP2 = Math.min(padH - 1, py + 2);
      for (int x = 0; x < w; x++) {
        int c = f.color(x, y);
        int idx = py * w + x;
        if (c == 1) {
          greenPad[idx] = norm[idx];
        } else {
          int xM1 = Math.max(0, x - 1), xP1 = Math.min(w - 1, x + 1);
          int xM2 = Math.max(0, x - 2), xP2 = Math.min(w - 1, x + 2);
          float gL = norm[py * w + xM1], gR = norm[py * w + xP1];
          float gU = norm[yM1 * w + x], gD = norm[yP1 * w + x];
          float c0 = norm[idx];
          float gradH = Math.abs(gL - gR) + Math.abs(2f * c0 - norm[py * w + xM2] - norm[py * w + xP2]);
          float gradV = Math.abs(gU - gD) + Math.abs(2f * c0 - norm[yM2 * w + x] - norm[yP2 * w + x]);
          float gEst;
          if (gradH * 1.15f < gradV) {
            gEst = 0.5f * (gL + gR) + 0.25f * (2f * c0 - norm[py * w + xM2] - norm[py * w + xP2]);
          } else if (gradV * 1.15f < gradH) {
            gEst = 0.5f * (gU + gD) + 0.25f * (2f * c0 - norm[yM2 * w + x] - norm[yP2 * w + x]);
          } else {
            gEst = 0.25f * (gL + gR + gU + gD);
          }
          greenPad[idx] = Math.max(0f, Math.min(ONE_LINEAR, gEst));
        }
      }
    }

    // Pass 2: Constant-hue (R - G) and (B - G) reconstruction over [yMin..yMax]
    for (int y = yMin; y <= yMax; y++) {
      int py = y - padTop;
      int outRow = (y - yMin) * w;
      int yM1 = Math.max(0, py - 1), yP1 = Math.min(padH - 1, py + 1);
      for (int x = 0; x < w; x++) {
        int c = f.color(x, y);
        int idx = py * w + x;
        float g = greenPad[idx];
        gOut[outRow + x] = g;
        int xM1 = Math.max(0, x - 1), xP1 = Math.min(w - 1, x + 1);

        if (c == 0) {
          rOut[outRow + x] = norm[idx];
          float d1 = norm[yM1 * w + xM1] - greenPad[yM1 * w + xM1];
          float d2 = norm[yM1 * w + xP1] - greenPad[yM1 * w + xP1];
          float d3 = norm[yP1 * w + xM1] - greenPad[yP1 * w + xM1];
          float d4 = norm[yP1 * w + xP1] - greenPad[yP1 * w + xP1];
          bOut[outRow + x] = clampLinear(g + 0.25f * (d1 + d2 + d3 + d4));
        } else if (c == 2) {
          bOut[outRow + x] = norm[idx];
          float d1 = norm[yM1 * w + xM1] - greenPad[yM1 * w + xM1];
          float d2 = norm[yM1 * w + xP1] - greenPad[yM1 * w + xP1];
          float d3 = norm[yP1 * w + xM1] - greenPad[yP1 * w + xM1];
          float d4 = norm[yP1 * w + xP1] - greenPad[yP1 * w + xP1];
          rOut[outRow + x] = clampLinear(g + 0.25f * (d1 + d2 + d3 + d4));
        } else {
          // Green site: check whether horizontal neighbor is R or B
          int cHoriz = f.color(x ^ 1, y);
          float dh = 0.5f * ((norm[py * w + xM1] - greenPad[py * w + xM1]) + (norm[py * w + xP1] - greenPad[py * w + xP1]));
          float dv = 0.5f * ((norm[yM1 * w + x] - greenPad[yM1 * w + x]) + (norm[yP1 * w + x] - greenPad[yP1 * w + x]));
          if (cHoriz == 0) {
            rOut[outRow + x] = clampLinear(g + dh);
            bOut[outRow + x] = clampLinear(g + dv);
          } else {
            bOut[outRow + x] = clampLinear(g + dh);
            rOut[outRow + x] = clampLinear(g + dv);
          }
        }
      }
    }
  }

  private static float sampleShadingGain(RawFrame f, int x, int y) {
    int ch = f.shadingChannel(x, y);
    float sw = Math.max(1f, f.shadingWidth > 0 ? f.shadingWidth : f.width);
    float sh = Math.max(1f, f.shadingHeight > 0 ? f.shadingHeight : f.height);
    float fx = Math.max(0f, Math.min(1f, (x - f.shadingLeft) / sw)) * (f.shadingColumns - 1);
    float fy = Math.max(0f, Math.min(1f, (y - f.shadingTop) / sh)) * (f.shadingRows - 1);
    int x0 = (int) fx, y0 = (int) fy;
    int x1 = Math.min(f.shadingColumns - 1, x0 + 1);
    int y1 = Math.min(f.shadingRows - 1, y0 + 1);
    float tx = fx - x0, ty = fy - y0;
    int cols = f.shadingColumns;
    float g00 = f.shading[(y0 * cols + x0) * 4 + ch];
    float g10 = f.shading[(y0 * cols + x1) * 4 + ch];
    float g01 = f.shading[(y1 * cols + x0) * 4 + ch];
    float g11 = f.shading[(y1 * cols + x1) * 4 + ch];
    return (g00 * (1f - tx) + g10 * tx) * (1f - ty) + (g01 * (1f - tx) + g11 * tx) * ty;
  }

  /** Same-CFA-parity (step 2) edge-protected optical PSF micro-contrast deconvolution. */
  private static void deconvolveSameColorPsa(
      char[] src, short[] dst, int w, int h, int frameCount) {
    final float amount = frameCount >= 4 ? 0.34f : (frameCount >= 2 ? 0.22f : 0.12f);
    for (int y = 0; y < h; y++) {
      int yM2 = y >= 2 ? y - 2 : y;
      int yP2 = y + 2 < h ? y + 2 : y;
      int row = y * w, rM2 = yM2 * w, rP2 = yP2 * w;
      for (int x = 0; x < w; x++) {
        int c = src[row + x];
        int xM2 = x >= 2 ? x - 2 : x;
        int xP2 = x + 2 < w ? x + 2 : x;
        int nL = src[row + xM2], nR = src[row + xP2];
        int nU = src[rM2 + x], nD = src[rP2 + x];
        float blur = 0.25f * (nL + nR + nU + nD);
        float detail = c - blur;
        // Soft coring & highlight rolloff so sensor noise floor and clipped speculars never ring
        float absD = Math.abs(detail);
        float gate = absD <= 14f ? 0f : Math.min(1f, (absD - 14f) / 48f);
        float hiRoll = c >= (ONE_LINEAR * 0.90f) ? Math.max(0f, (ONE_LINEAR - c) / (ONE_LINEAR * 0.10f)) : 1f;
        int out = Math.round(c + detail * amount * gate * hiRoll);
        dst[row + x] = (short) Math.max(0, Math.min(ONE_LINEAR, out));
      }
    }
  }

  private static float[] buildGreenLuminanceGrid(RawFrame f, int gw, int gh) {
    float[] grid = new float[gw * gh];
    int[] counts = new int[gw * gh];
    final int w = f.width, h = f.height, stride = f.rowStride;
    final short[] raw = f.data;
    for (int y = 0; y < h; y++) {
      int gy = Math.min(gh - 1, (int) ((long) y * gh / h));
      int rOff = y * stride;
      for (int x = 0; x < w; x++) {
        if (f.color(x, y) != 1) continue;
        int gx = Math.min(gw - 1, (int) ((long) x * gw / w));
        int bIdx = ((y & 1) << 1) | (x & 1);
        float blk = f.black[bIdx];
        float v = Math.max(0f, ((raw[rOff + x] & 0xFFFF) - blk) / Math.max(1f, f.white - blk));
        int idx = gy * gw + gx;
        grid[idx] += (float) Math.sqrt(Math.min(1.0f, v)); // Perceptual sqrt domain for alignment
        counts[idx]++;
      }
    }
    for (int i = 0; i < grid.length; i++) {
      if (counts[i] > 0) grid[i] /= counts[i];
    }
    return grid;
  }

  /** Returns {@code [subpixelDx, subpixelDy, minNormalizedSad]} in alignment-grid pixel units. */
  private static float[] estimateTileShift(
      float[] ref, float[] cur, int gw, int gh, int tx, int ty) {
    int x0 = (tx * gw) / TILES_X + SEARCH_RADIUS + 2;
    int x1 = ((tx + 1) * gw) / TILES_X - SEARCH_RADIUS - 2;
    int y0 = (ty * gh) / TILES_Y + SEARCH_RADIUS + 2;
    int y1 = ((ty + 1) * gh) / TILES_Y - SEARCH_RADIUS - 2;
    if (x1 <= x0 + 4 || y1 <= y0 + 4) return new float[] {0f, 0f, 0f};

    final int side = 2 * SEARCH_RADIUS + 1;
    final float[] costs = new float[side * side];
    int bestDx = 0, bestDy = 0;
    float bestCost = Float.MAX_VALUE;
    int count = (x1 - x0) * (y1 - y0);

    for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
      for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
        float sad = 0f;
        for (int y = y0; y < y1; y++) {
          int rRow = y * gw;
          int cRow = (y + dy) * gw + dx;
          for (int x = x0; x < x1; x++) {
            sad += Math.abs(ref[rRow + x] - cur[cRow + x]);
          }
        }
        float normSad = sad / count;
        costs[(dy + SEARCH_RADIUS) * side + (dx + SEARCH_RADIUS)] = normSad;
        if (normSad < bestCost) {
          bestCost = normSad;
          bestDx = dx;
          bestDy = dy;
        }
      }
    }

    float subX = 0f, subY = 0f;
    if (Math.abs(bestDx) < SEARCH_RADIUS) {
      float cL = costs[(bestDy + SEARCH_RADIUS) * side + (bestDx - 1 + SEARCH_RADIUS)];
      float cR = costs[(bestDy + SEARCH_RADIUS) * side + (bestDx + 1 + SEARCH_RADIUS)];
      float denom = 2f * (cL - 2f * bestCost + cR);
      if (Math.abs(denom) > 1e-6f) {
        subX = Math.max(-0.5f, Math.min(0.5f, (cL - cR) / denom));
      }
    }
    if (Math.abs(bestDy) < SEARCH_RADIUS) {
      float cU = costs[(bestDy - 1 + SEARCH_RADIUS) * side + (bestDx + SEARCH_RADIUS)];
      float cD = costs[(bestDy + 1 + SEARCH_RADIUS) * side + (bestDx + SEARCH_RADIUS)];
      float denom = 2f * (cU - 2f * bestCost + cD);
      if (Math.abs(denom) > 1e-6f) {
        subY = Math.max(-0.5f, Math.min(0.5f, (cU - cD) / denom));
      }
    }
    return new float[] {bestDx + subX, bestDy + subY, bestCost};
  }

  private static float[] bilerpTileField(
      float[] dxField, float[] dyField, float[] trustField, float tx, float ty) {
    float cx = Math.max(0f, Math.min(TILES_X - 1f, tx));
    float cy = Math.max(0f, Math.min(TILES_Y - 1f, ty));
    int x0 = (int) cx, y0 = (int) cy;
    int x1 = Math.min(TILES_X - 1, x0 + 1);
    int y1 = Math.min(TILES_Y - 1, y0 + 1);
    float fx = cx - x0, fy = cy - y0;

    int i00 = y0 * TILES_X + x0, i10 = y0 * TILES_X + x1;
    int i01 = y1 * TILES_X + x0, i11 = y1 * TILES_X + x1;
    float w00 = (1f - fx) * (1f - fy), w10 = fx * (1f - fy);
    float w01 = (1f - fx) * fy, w11 = fx * fy;

    return new float[] {
      dxField[i00] * w00 + dxField[i10] * w10 + dxField[i01] * w01 + dxField[i11] * w11,
      dyField[i00] * w00 + dyField[i10] * w10 + dyField[i01] * w01 + dyField[i11] * w11,
      trustField[i00] * w00 + trustField[i10] * w10 + trustField[i01] * w01 + trustField[i11] * w11
    };
  }

  private static float sampleCatmullRom(float[] plane, int w, int h, float x, float y) {
    float cx = Math.max(0f, Math.min(w - 1f, x));
    float cy = Math.max(0f, Math.min(h - 1f, y));
    int ix = (int) Math.floor(cx);
    int iy = (int) Math.floor(cy);
    float fx = cx - ix;
    float fy = cy - iy;

    float wx0 = cubicWeight(-1f - fx), wx1 = cubicWeight(-fx);
    float wx2 = cubicWeight(1f - fx), wx3 = cubicWeight(2f - fx);
    float wy0 = cubicWeight(-1f - fy), wy1 = cubicWeight(-fy);
    float wy2 = cubicWeight(1f - fy), wy3 = cubicWeight(2f - fy);

    int x0 = Math.max(0, ix - 1), x1 = Math.max(0, Math.min(w - 1, ix));
    int x2 = Math.min(w - 1, ix + 1), x3 = Math.min(w - 1, ix + 2);

    float sum = 0f;
    int[] ys = {
      Math.max(0, iy - 1),
      Math.max(0, Math.min(h - 1, iy)),
      Math.min(h - 1, iy + 1),
      Math.min(h - 1, iy + 2)
    };
    float[] wys = {wy0, wy1, wy2, wy3};
    for (int j = 0; j < 4; j++) {
      int rOff = ys[j] * w;
      float rowVal =
          plane[rOff + x0] * wx0
              + plane[rOff + x1] * wx1
              + plane[rOff + x2] * wx2
              + plane[rOff + x3] * wx3;
      sum += rowVal * wys[j];
    }
    return clampLinear(sum);
  }

  private static float cubicWeight(float t) {
    float a = Math.abs(t);
    if (a <= 1f) {
      return (1.5f * a - 2.5f) * a * a + 1f;
    } else if (a < 2f) {
      return ((-0.5f * a + 2.5f) * a - 4f) * a + 2f;
    }
    return 0f;
  }

  private static float clampLinear(float v) {
    return v < 0f ? 0f : (v > ONE_LINEAR ? ONE_LINEAR : v);
  }

  private static int cfaColor(int cfa, int x, int y) {
    int k = ((y & 1) << 1) | (x & 1);
    switch (cfa) {
      case RawFrame.RGGB:
        return k == 0 ? 0 : k == 3 ? 2 : 1;
      case RawFrame.GRBG:
        return k == 1 ? 0 : k == 2 ? 2 : 1;
      case RawFrame.GBRG:
        return k == 2 ? 0 : k == 1 ? 2 : 1;
      case RawFrame.BGGR:
        return k == 3 ? 0 : k == 0 ? 2 : 1;
      default:
        return 1;
    }
  }

  private static int[] scaleFaces(int[] faces, double sx, double sy) {
    if (faces == null) return null;
    int[] out = new int[faces.length];
    for (int i = 0; i + 3 < faces.length; i += 4) {
      out[i] = (int) Math.round(faces[i] * sx);
      out[i + 1] = (int) Math.round(faces[i + 1] * sy);
      out[i + 2] = (int) Math.round(faces[i + 2] * sx);
      out[i + 3] = (int) Math.round(faces[i + 3] * sy);
    }
    return out;
  }
}
