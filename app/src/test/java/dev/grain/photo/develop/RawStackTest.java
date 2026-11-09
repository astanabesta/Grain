package dev.grain.photo.develop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Multi-frame RAW stacking: independent-noise averaging must quarter the variance of a 4-frame
 * stack in still regions, integer alignment must neutralise small hand shifts (never tearing the
 * Bayer phase), and the per-pixel motion gate must make a moving subject fall back to the
 * reference frame — the merged output can approach the single frame there but never ghost.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RawStackTest {

  private static final int W = 512, H = 384;
  private static final float BLACK = 64f, WHITE = 1023f;
  private static final double NS = 4e-4, NO = 1.6e-5;

  /**
   * A scene in ~[100, 720] with a genuinely FLAT quarter (x < 128: pure noise floor for variance
   * measurements) and fine texture elsewhere (x >= 192: a high-frequency checker used to prove
   * alignment kept the structure). Gradients only, so a 3x3 residual isolates noise there.
   */
  private static int scene(int x, int y) {
    if (x >= 192)
      return 400 + (((x * 37 + y * 61) % 23) < 11 ? 90 : -90) + (int) (40 * Math.sin(y * 0.017));
    return 400 + Math.max(-20, Math.min(20, (y - H / 2) / 24));
  }

  private static short[] makeFrame(long seed, int dx, int dy, int movedBlock) {
    Random rnd = new Random(seed);
    short[] f = new short[W * H];
    for (int y = 0; y < H; y++)
      for (int x = 0; x < W; x++) {
        int sx = x + dx, sy = y + dy; // frame content shifted: sample scene at shifted coords
        sx = Math.max(0, Math.min(W - 1, sx));
        sy = Math.max(0, Math.min(H - 1, sy));
        double base = scene(sx, sy);
        double xn = (base - BLACK) / (WHITE - BLACK);
        double sigma = (WHITE - BLACK) * Math.sqrt(NS * xn + NO);
        int v = (int) Math.round(base + rnd.nextGaussian() * sigma);
        f[y * W + x] = (short) Math.max(0, Math.min(1023, v));
      }
    if (movedBlock > 0) {
      // a subject that moved between frames: locally the content is entirely different and far
      // brighter, so the gate must reject these frames inside the block
      int c0 = W / 2 - 60, c1 = W / 2 + 60, r0 = H / 2 - 60, r1 = H / 2 + 60;
      for (int y = r0; y < r1; y++)
        for (int x = c0; x < c1; x++)
          f[y * W + x] = (short) (700 + rnd.nextInt(2400) % 40);
    }
    return f;
  }

  /** Noise variance inside the flat zone: residual of each pixel vs its 3x3 mean (kills gradient). */
  private static double flatVar(short[] d, int x0, int y0, int sz) {
    double[] r = new double[sz * sz];
    int k = 0;
    for (int y = y0; y < y0 + sz; y++)
      for (int x = x0; x < x0 + sz; x++) {
        long m = 0;
        for (int yy = -1; yy <= 1; yy++)
          for (int xx = -1; xx <= 1; xx++) m += d[(y + yy) * W + x + xx] & 0xffff;
        r[k++] = (d[y * W + x] & 0xffff) - m / 9.0;
      }
    double mr = 0;
    for (double v : r) mr += v;
    mr /= r.length;
    double s2 = 0;
    for (double v : r) s2 += (v - mr) * (v - mr);
    return s2 / (r.length - 1);
  }

  @Test
  public void fourStillFramesCutNoiseVarianceByFour() {
    List<short[]> frames = new ArrayList<>();
    for (int k = 0; k < 4; k++) frames.add(makeFrame(100 + k, 0, 0, 0));
    RawStack.Out out = RawStack.merge(frames, W, H, W, BLACK, WHITE, NS, NO);
    assertEquals(4, out.framesUsed);
    double vin = flatVar(frames.get(0), 40, 40, 48);
    double vout = flatVar(out.data, 40, 40, 48);
    assertTrue("variance ratio " + vout / vin, vout < vin * 0.42);
    assertTrue("gate should be quiet on stills", out.gatedFraction < 0.12f);
  }

  @Test
  public void alignmentAbsorbsHandShiftAndKeepsBayerPhase() {
    List<short[]> frames = new ArrayList<>();
    frames.add(makeFrame(200, 0, 0, 0));
    frames.add(makeFrame(201, 4, 0, 0));
    frames.add(makeFrame(202, -4, 4, 0));
    frames.add(makeFrame(203, 8, -4, 0));
    RawStack.Out out = RawStack.merge(frames, W, H, W, BLACK, WHITE, NS, NO);
    for (int k = 1; k < 4; k++) {
      assertTrue("shift multiple of 4", out.shiftX[k] % 4 == 0 && out.shiftY[k] % 4 == 0);
      assertTrue("within search range", Math.abs(out.shiftX[k]) <= 56 && Math.abs(out.shiftY[k]) <= 56);
    }
    // noise still drops in a textured area after alignment
    double vin = flatVar(frames.get(0), 40, 140, 48);
    double vout = flatVar(out.data, 40, 140, 48);
    assertTrue("aligned variance ratio " + vout / vin, vout < vin * 0.55);
  }

  @Test
  public void movingSubjectFallsBackToReference() {
    List<short[]> frames = new ArrayList<>();
    frames.add(makeFrame(300, 0, 0, 0));
    frames.add(makeFrame(301, 0, 0, 1));
    frames.add(makeFrame(302, 0, 0, 1));
    frames.add(makeFrame(303, 0, 0, 1));
    RawStack.Out out = RawStack.merge(frames, W, H, W, BLACK, WHITE, NS, NO);
    // inside the moved block the merged pixels must track the reference, not the moved content
    double err = 0;
    int n = 0;
    for (int y = H / 2 - 40; y < H / 2 + 40; y += 2)
      for (int x = W / 2 - 40; x < W / 2 + 40; x += 2) {
        int d = (out.data[y * W + x] & 0xffff) - (frames.get(0)[y * W + x] & 0xffff);
        err += d * d;
        n++;
      }
    double rmse = Math.sqrt(err / n);
    assertTrue("merged-vs-reference rmse " + rmse, rmse < 8);
    assertTrue("gate should fire on the block", out.gatedFraction > 0.05f);
    // still regions still get the noise win
    double vin = flatVar(frames.get(0), 40, 40, 48);
    double vout = flatVar(out.data, 40, 40, 48);
    assertTrue(vout < vin * 0.42);
  }

  // --- v0.9.21 regression: the gate must run on MEASURED noise, not a lying HAL profile. ---

  /** Wide-luminance-ramp static scene so {@link RawDeveloper#measureRawNoise} finds enough bins. */
  private static int calScene(int x, int y) {
    double xn = 0.05 + 0.6 * x / W + (((x * 29 + y * 53) % 17 < 8) ? 0.05 : -0.05);
    return (int) Math.round(BLACK + xn * (WHITE - BLACK));
  }

  /** Real sensor noise {1.2e-3, 5e-6} — what frame-measurement reported on a MediaTek phone. */
  private static short[] makeCalFrame(long seed) {
    Random rnd = new Random(seed);
    short[] f = new short[W * H];
    for (int y = 0; y < H; y++)
      for (int x = 0; x < W; x++) {
        int base = calScene(x, y);
        double xn = (base - BLACK) / (WHITE - BLACK);
        double sigma = (WHITE - BLACK) * Math.sqrt(1.2e-3 * xn + 5e-6);
        f[y * W + x] = (short) Math.max(0, Math.min(1023, Math.round(base + rnd.nextGaussian() * sigma)));
      }
    return f;
  }

  /** RawFrame metadata mirroring the MediaTek HAL: honest colour data, under-reporting profile. */
  private static RawFrame calMeta(short[] data) {
    RawFrame f = new RawFrame(data, W, H, W);
    f.cfa = RawFrame.GRBG; // black defaults to {64,64,64,64}
    f.white = (int) WHITE;
    f.iso = 5834;
    f.exposureNs = 30_000_000L;
    f.neutral = new double[] {0.5, 1.0, 0.52};
    f.noiseS = new double[] {2.9e-4, 1.5e-4, 1.4e-4, 2.5e-4}; // GRBG: greens at k=0 and k=3
    f.noiseO = new double[] {5e-8, 2.6e-8, 0, 2.4e-8};
    return f;
  }

  @Test
  public void motionGateCalibratesToMeasuredNoiseNotLyingHalProfile() {
    List<short[]> a = new ArrayList<>(), b = new ArrayList<>();
    for (int k = 0; k < 3; k++) {
      short[] f = makeCalFrame(900 + k);
      a.add(f.clone());
      b.add(f.clone());
    }
    RawStack.Out naive = RawStack.merge(a, W, H, W, BLACK, WHITE, 2.1e-4, 2.5e-8); // HAL's story
    double[] trusted = RawDeveloper.stackGateNoise(calMeta(b.get(0)));
    RawStack.Out fixed = RawStack.merge(b, W, H, W, BLACK, WHITE, trusted[0], trusted[1]);
    // static scene: the lying profile gates like crazy (device evidence: 43%), the measured
    // model keeps the gate quiet
    assertTrue("gate on raw HAL profile: " + naive.gatedFraction, naive.gatedFraction > 0.20f);
    assertTrue("gate on measured noise: " + fixed.gatedFraction, fixed.gatedFraction < 0.05f);
    assertTrue(
        "naive stacks noticeably worse: naive=" + naive.gatedFraction + " fixed=" + fixed.gatedFraction,
        naive.gatedFraction > fixed.gatedFraction * 4);
    // and with the gate quiet, the stack actually earns its MSE win vs the known signal
    double vin = 0, vout = 0, vNaive = 0;
    int nPix = 0;
    for (int y = 0; y < H; y++)
      for (int x = 0; x < W; x++) {
        double truth = calScene(x, y);
        if (truth > 1000) continue; // skip clipped highlights
        double d0 = (b.get(0)[y * W + x] & 0xffff) - truth;
        double d1 = (fixed.data[y * W + x] & 0xffff) - truth;
        double d2 = (naive.data[y * W + x] & 0xffff) - truth;
        vin += d0 * d0;
        vout += d1 * d1;
        vNaive += d2 * d2;
        nPix++;
      }
    vin /= nPix;
    vout /= nPix;
    vNaive /= nPix;
    assertTrue("fixed merge MSE ratio " + vout / vin, vout < vin * 0.45);
    assertTrue(
        "naive merge MSE ratio " + vNaive / vin + " vs fixed " + vout / vin,
        vNaive > vout * 1.3);
  }

  @Test
  public void singleFrameIsBitExact() {
    List<short[]> frames = new ArrayList<>();
    frames.add(makeFrame(400, 0, 0, 0));
    RawStack.Out out = RawStack.merge(frames, W, H, W, BLACK, WHITE, NS, NO);
    assertEquals(1, out.framesUsed);
    for (int i = 0; i < frames.get(0).length; i++) assertEquals(frames.get(0)[i], out.data[i]);
  }

  @Test
  public void withDataPreservesCalibrationAndStackCount() {
    RawFrame src = new RawFrame(new short[64], 8, 8, 8);
    src.cfa = RawFrame.BGGR;
    src.white = 4095;
    src.iso = 800;
    src.exposureNs = 12_345_678;
    src.neutral = new double[] {0.5, 1.0, 0.7};
    src.noiseS = new double[] {1e-5, 1e-5, 1e-5, 1e-5};
    src.noiseO = new double[] {2e-6, 2e-6, 2e-6, 2e-6};
    src.stackCount = 4;
    short[] data = new short[64];
    data[5] = 123;
    RawFrame f = RawStack.withData(src, data);
    assertEquals(RawFrame.BGGR, f.cfa);
    assertEquals(4095f, f.white, 0f);
    assertEquals(800, f.iso);
    assertEquals(12_345_678, f.exposureNs);
    assertEquals(4, f.stackCount);
    assertEquals(0.5, f.neutral[0], 0f);
    assertEquals(123, f.data[5]);
  }
}
