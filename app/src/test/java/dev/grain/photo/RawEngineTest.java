package dev.grain.photo;

import static org.junit.Assert.*;

import dev.grain.photo.develop.ColorMath;
import dev.grain.photo.develop.CubeLut;
import dev.grain.photo.develop.FLog2;
import dev.grain.photo.develop.FujiRecipe;
import dev.grain.photo.develop.RawDeveloper;
import dev.grain.photo.develop.RawFrame;
import dev.grain.photo.develop.ToneModel;
import dev.grain.photo.develop.ToneTable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.StringReader;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

public class RawEngineTest {
  /** Identity F-Log2 cube: output = input code, as a stand-in for Fujifilm's LUT in tests. */
  static CubeLut identity(int n) {
    StringBuilder b = new StringBuilder("TITLE \"identity\"\nLUT_3D_SIZE " + n + "\n");
    for (int bl = 0; bl < n; bl++)
      for (int g = 0; g < n; g++)
        for (int r = 0; r < n; r++)
          b.append(r / (n - 1.0)).append(' ').append(g / (n - 1.0)).append(' ')
              .append(bl / (n - 1.0)).append('\n');
    try {
      return CubeLut.parse(new StringReader(b.toString()));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  public void flog2RoundTripAndDatasheetPoints() {
    for (double v = 1e-4; v < 16; v *= 1.37) assertEquals(v, FLog2.decode(FLog2.encode(v)), v * 1e-9 + 1e-12);
    // F-Log2 data sheet: 18 % grey -> 39.1 % code value (0.391)
    assertEquals(0.391, FLog2.encode(0.18), 0.002);
    assertEquals(0.092864, FLog2.encode(0), 1e-6);
  }

  @Test
  public void toneTableMatchesDirectEvaluation() {
    ToneModel.Curve curve = ToneModel.curve(0, -1, -1, true, 1);
    ToneTable t = new ToneTable(x -> Math.max(0, Math.min(1, curve.apply(FLog2.encode(x)))));
    Random rnd = new Random(7);
    for (int i = 0; i < 2000; i++) {
      double x = Math.pow(2, -12 + 16 * rnd.nextDouble());
      double direct = Math.max(0, Math.min(1, curve.apply(FLog2.encode(x))));
      assertEquals(direct, t.apply((float) x), 0.002);
    }
  }

  @Test
  public void stillsCurveIsMonotonicAndReachesWhiteBeforeVideoLut() {
    CubeLut lut = identity(17);
    ToneModel.Curve dr100 = ToneModel.curve(0, 0, 0, true, 1);
    double prev = -1;
    for (double e = -10; e <= 8; e += 0.05) {
      double v = dr100.apply(FLog2.encode(0.18 * Math.pow(2, e)));
      assertTrue("monotonic at " + e, v >= prev - 1e-9);
      prev = v;
    }
    ToneModel.Curve video = ToneModel.curve(0, 0, 0, false, 1);
    assertTrue(ToneModel.whiteEv(dr100, lut) < ToneModel.whiteEv(video, lut));
  }

  @Test
  public void cubeLutIdentityAndBinaryRoundTrip() throws Exception {
    CubeLut lut = identity(33);
    float[] out = new float[3];
    Random rnd = new Random(3);
    for (int i = 0; i < 500; i++) {
      float r = rnd.nextFloat(), g = rnd.nextFloat(), b = rnd.nextFloat();
      lut.sample(r, g, b, out);
      assertEquals(r, out[0], 1e-5);
      assertEquals(g, out[1], 1e-5);
      assertEquals(b, out[2], 1e-5);
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    lut.write(new DataOutputStream(bytes));
    CubeLut back = CubeLut.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    assertEquals(33, back.size);
    assertArrayEquals(lut.data, back.data, 0f);
  }

  static final double[] CM = { // a plausible XYZ->camera matrix (D65)
    0.80, -0.15, -0.10,
    -0.45, 1.25, 0.20,
    -0.05, 0.12, 0.60
  };

  @Test
  public void neutralMapsToNeutralWithColorMatrix() {
    double[] n = {0.52, 1, 0.61};
    ColorMath.Result r = ColorMath.cameraToRec2020(n, CM, null, null, null, null, null, 21, 21);
    double[] m = r.cameraToRec2020;
    for (int row = 0; row < 3; row++)
      assertEquals(1.0, m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2], 1e-9);
  }

  @Test
  public void neutralMapsToNeutralWithForwardMatrix() {
    double[] n = {0.48, 1, 0.66};
    double[] fm = {0.65, 0.20, 0.11, 0.25, 0.80, -0.05, 0.02, -0.15, 0.96};
    ColorMath.Result r = ColorMath.cameraToRec2020(n, CM, null, fm, null, null, null, 21, 21);
    assertTrue(r.usedForwardMatrix);
    double[] m = r.cameraToRec2020;
    for (int row = 0; row < 3; row++)
      assertEquals(1.0, m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2], 1e-9);
  }

  /** A flat grey Bayer frame must develop to a flat neutral image (identity LUT, no grain). */
  @Test
  public void flatGreyFrameDevelopsNeutralAndEven() throws Exception {
    int w = 256, h = 192;
    double[] n = {0.5, 1, 0.625};
    short[] data = new short[w * h];
    RawFrame f = new RawFrame(data, w, h, w);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int c = f.color(x, y); // RGGB: 0 R, 1 G, 2 B
        double level = 0.10 * (c == 0 ? n[0] : c == 2 ? n[2] : 1);
        data[y * w + x] = (short) Math.round(64 + level * (1023 - 64));
      }
    f.neutral = n;
    f.colorMatrix1 = CM;
    f.iso = 100;
    FujiRecipe plain =
        new FujiRecipe("plain", "Plain", "", "", "LUT", "LUT", FujiRecipe.DynamicRange.DR100,
            0, 0, 0, 0, -4, 0, 0, false, 0, 0, "As shot", 0, 0);
    RawDeveloper.Options o = new RawDeveloper.Options();
    o.grain = false;
    o.threads = 2;
    int[] out = new int[w * h];
    Map<String, Object> stats =
        RawDeveloper.develop(f, plain, identity(33), o, (y, rows, argb, width) ->
            System.arraycopy(argb, 0, out, y * width, rows * width));
    assertEquals("DR100", stats.get("dynamicRange"));
    double sr = 0, sg = 0, sb = 0;
    for (int p : out) {
      sr += (p >> 16) & 255;
      sg += (p >> 8) & 255;
      sb += p & 255;
    }
    sr /= out.length;
    sg /= out.length;
    sb /= out.length;
    assertEquals("neutral R", sg, sr, 1.0);
    assertEquals("neutral B", sg, sb, 1.0);
    for (int i = 0; i < out.length; i++) {
      int g = (out[i] >> 8) & 255;
      // 0.5 LSB Gaussian dither is the only variation allowed in a flat field
      assertTrue("even at " + i + ": " + g + " vs " + sg, Math.abs(g - sg) <= 4);
    }
    // Metered to the fitted key: a flat field lands mid-scale.
    assertTrue(sg > 60 && sg < 200);
  }

  @Test
  public void reggiesPortraIsTheDocumentedRecipe() {
    FujiRecipe r = FujiRecipe.REGGIES_PORTRA;
    assertEquals("CLASSIC-CHROME", r.filmSimulation);
    assertEquals(FujiRecipe.DynamicRange.AUTO, r.dynamicRange);
    assertEquals(-1, r.highlight, 0);
    assertEquals(-1, r.shadow, 0);
    assertEquals(2, r.color);
    assertEquals(-4, r.noiseReduction);
    assertEquals(2, r.wbShiftRed);
    assertEquals(-4, r.wbShiftBlue);
  }

  @Test
  public void lutEntryPatternTakesFLog2NotFLog2C() {
    java.util.regex.Pattern p = CubeLut.FUJI_FLOG2_33;
    java.util.regex.Matcher m =
        p.matcher("gfx-eterna-55-3d-lut-v110/33Grid/F-Log2/FLog2_to_CLASSIC-CHROME_33grid_V.1.00.cube");
    assertTrue(m.find());
    assertEquals("CLASSIC-CHROME", m.group(1));
    assertFalse(p.matcher("x/33Grid/F-Log2C/FLog2C_to_PROVIA_33grid_V.1.00.cube").find());
    assertFalse(p.matcher("x/65Grid/F-Log2/FLog2_to_PROVIA_65grid_V.1.00.cube").find());
  }

  /** Gaussian RAW noise with var = S x + O on a brightness ramp: the frame measurement recovers it. */
  @Test
  public void rawNoiseMeasurementRecoversTheCurve() {
    int w = 640, h = 480;
    short[] data = new short[w * h];
    RawFrame f = new RawFrame(data, w, h, w);
    f.cfa = RawFrame.GRBG;
    double s = 6e-4, o = 1.5e-6;
    Random rnd = new Random(11);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        double level = 0.004 * Math.pow(2, 7.0 * x / w);
        double v = level + rnd.nextGaussian() * Math.sqrt(s * level + o);
        data[y * w + x] = (short) Math.max(0, Math.min(1023, Math.round(64 + v * (1023 - 64))));
      }
    double[] m = RawDeveloper.measureRawNoise(f, 0);
    assertNotNull(m);
    assertEquals("shot term", s, m[0], 0.2 * s);
    // 10-bit quantisation adds 1/12 LSB^2 to the read term
    assertEquals("read term", o + 1.0 / 12 / (959.0 * 959.0), m[1], 0.6 * o);
  }

  static int[] developNoisyStep(double lumaNr, long seed) throws Exception {
    int w = 320, h = 240;
    double[] n = {0.5, 1, 0.625};
    short[] data = new short[w * h];
    RawFrame f = new RawFrame(data, w, h, w);
    Random rnd = new Random(seed);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int c = f.color(x, y);
        double level = (x < w / 2 ? 0.05 : 0.20) * (c == 0 ? n[0] : c == 2 ? n[2] : 1);
        double v = level + rnd.nextGaussian() * Math.sqrt(6e-4 * level + 1.5e-6);
        data[y * w + x] = (short) Math.max(0, Math.min(1023, Math.round(64 + v * (1023 - 64))));
      }
    f.neutral = n;
    f.colorMatrix1 = CM;
    f.iso = 3200;
    FujiRecipe plain =
        new FujiRecipe("plain", "Plain", "", "", "LUT", "LUT", FujiRecipe.DynamicRange.DR100,
            0, 0, 0, 0, -4, 0, 0, false, 0, 0, "As shot", 0, 0);
    RawDeveloper.Options opt = new RawDeveloper.Options();
    opt.grain = false;
    opt.threads = 2;
    opt.sharpen = 0;
    opt.lumaNr = lumaNr;
    opt.fixedGain = 2.0;
    int[] out = new int[w * h];
    RawDeveloper.develop(f, plain, identity(33), opt, (y, rows, argb, width) ->
        System.arraycopy(argb, 0, out, y * width, rows * width));
    return out;
  }

  static double flatStd(int[] img, int w, int x0, int x1, int y0, int y1) {
    double s = 0, ss = 0;
    int k = 0;
    for (int y = y0; y < y1; y++)
      for (int x = x0; x < x1; x++) {
        int g = (img[y * w + x] >> 8) & 255;
        s += g;
        ss += g * g;
        k++;
      }
    double mean = s / k;
    return Math.sqrt(Math.max(0, ss / k - mean * mean));
  }

  /** 10-90 % rise width (pixels) of the row-averaged green profile around the step at w / 2. */
  static double riseWidth(int[] img, int w, int h) {
    double[] prof = new double[w];
    for (int y = 20; y < h - 20; y++)
      for (int x = 0; x < w; x++) prof[x] += (img[y * w + x] >> 8) & 255;
    double lo = 0, hi = 0;
    for (int x = w / 2 - 40; x < w / 2 - 20; x++) lo += prof[x] / 20;
    for (int x = w / 2 + 20; x < w / 2 + 40; x++) hi += prof[x] / 20;
    double a = lo + 0.1 * (hi - lo), b = lo + 0.9 * (hi - lo);
    double xa = -1, xb = -1;
    for (int x = w / 2 - 20; x < w / 2 + 20; x++) {
      if (xa < 0 && prof[x] >= a) xa = x - 1 + (a - prof[x - 1]) / (prof[x] - prof[x - 1]);
      if (xb < 0 && prof[x] >= b) xb = x - 1 + (b - prof[x - 1]) / (prof[x] - prof[x - 1]);
    }
    return xb - xa;
  }

  /** Luma noise reduction must clean flat areas without blurring a strong edge. */
  @Test
  public void noiseReductionCleansFlatAreasButKeepsEdges() throws Exception {
    int w = 320, h = 240;
    int[] off = developNoisyStep(0, 5), on = developNoisyStep(1, 5);
    double sdOff = flatStd(off, w, 20, w / 2 - 30, 20, h - 20), sdOn = flatStd(on, w, 20, w / 2 - 30, 20, h - 20);
    double sdOffB = flatStd(off, w, w / 2 + 30, w - 20, 20, h - 20), sdOnB = flatStd(on, w, w / 2 + 30, w - 20, 20, h - 20);
    assertTrue("dark side " + sdOn + " vs " + sdOff, sdOn < 0.75 * sdOff);
    assertTrue("bright side " + sdOnB + " vs " + sdOffB, sdOnB < 0.75 * sdOffB);
    double wOff = riseWidth(off, w, h), wOn = riseWidth(on, w, h);
    assertTrue("edge width " + wOn + " vs " + wOff, wOn <= wOff + 0.75);
  }
}
