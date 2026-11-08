package dev.grain.photo;

import static org.junit.Assert.*;

import dev.grain.photo.develop.ColorMath;
import dev.grain.photo.develop.CubeLut;
import dev.grain.photo.develop.FLog2;
import dev.grain.photo.develop.FujiRecipe;
import dev.grain.photo.develop.RawDeveloper;
import dev.grain.photo.develop.RawFrame;
import dev.grain.photo.develop.SLog3;
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

  /**
   * Reggie's Superia (v0.3.2) carries the settings Fuji X Weekly published on 2026-03-12, starts on
   * First version like his Portra, and is listed right after it.
   */
  @Test
  public void reggiesSuperiaIsThePublishedRecipe() throws Exception {
    FujiRecipe r = FujiRecipe.REGGIES_SUPERIA;
    assertEquals("reggies-superia", r.key);
    assertEquals("Reggie Ballesteros", r.author);
    assertEquals("CLASSIC-Neg.", r.filmSimulation);
    assertEquals(FujiRecipe.DynamicRange.AUTO, r.dynamicRange);
    assertEquals(2, r.grainRoughness);
    assertTrue(r.grainLarge);
    assertEquals(2, r.colorChrome);
    assertEquals(2, r.colorChromeBlue);
    assertEquals(FujiRecipe.WhiteBalance.AUTO, r.wbMode);
    assertEquals(1, r.wbShiftRed);
    assertEquals(-3, r.wbShiftBlue);
    assertEquals(-2, r.highlight, 0);
    assertEquals(-1, r.shadow, 0);
    assertEquals(1, r.color);
    assertEquals(-2, r.sharpness);
    assertEquals(-4, r.noiseReduction);
    assertEquals(0, r.clarity);
    assertEquals(0, r.halation);
    assertEquals(0, r.bloom);
    assertEquals(0, r.monoFilter);
    assertTrue("no typical exposure, at Reggie's request", r.exposureNote.isEmpty());
    assertEquals(FujiRecipe.Development.FIRST_VERSION, r.development);
    assertTrue(r.sameSettings(r.with(r.tuning())));
    Map<String, String> shown = new java.util.HashMap<>();
    for (String[] row : r.settings()) shown.put(row[0], row[1]);
    assertEquals("Classic Neg.", shown.get("Film simulation"));
    assertEquals("DR-Auto", shown.get("Dynamic range"));
    assertEquals("Auto; R +1, B −3", shown.get("White balance"));
    assertEquals("−2", shown.get("Highlight"));
    assertEquals("−1", shown.get("Shadow"));
    assertEquals("+1", shown.get("Color"));
    assertEquals("Strong / Large", shown.get("Grain"));
    assertEquals("Strong", shown.get("Color Chrome Effect"));
    assertEquals("Strong", shown.get("Color Chrome FX Blue"));
    assertEquals("First version (as Grain v0.2.0)", shown.get("Development (Grain)"));
    assertFalse(shown.containsKey("Exposure compensation (typical, not applied)"));
    assertSame("listed right after Reggie's Portra", r, FujiRecipe.PUBLISHED[1]);

    java.util.List<Map<String, Object>> st = new java.util.ArrayList<>();
    int[] superia = developGolden(r, true, st);
    Map<String, Object> s = st.get(0);
    assertTrue(String.valueOf(s.get("development")).startsWith("First version"));
    assertEquals(0.0, ((Number) s.get("sharpenAmount")).doubleValue(), 0);
    double amp = ((Number) s.get("grainAmplitude")).doubleValue();
    assertEquals("full Strong / Large grain", ((Number) s.get("grainAmplitudeRecipe")).doubleValue(), amp, 1e-6);
    int[] portra = developGolden(FujiRecipe.REGGIES_PORTRA, true, st);
    assertTrue("Strong / Large is heavier than Weak / Small", amp > 1.5 * ((Number) st.get(0).get("grainAmplitude")).doubleValue());
    assertTrue(((Number) s.get("colorChromeBlueDepth")).doubleValue() > ((Number) st.get(0).get("colorChromeBlueDepth")).doubleValue());
    assertNotEquals(fnv(portra), fnv(superia));
    for (int p : superia) assertEquals("opaque", 0xff, p >>> 24);
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

  /** The LUT names in Fujifilm's GFX ETERNA 55 v1.10 pack (F-Log2, 33-point). */
  static final String[] GFX_ETERNA_55_PACK = {
    "ACROS", "ASTIA", "CLASSIC-CHROME", "CLASSIC-Neg.", "ETERNA-BB", "ETERNA", "FLog2-709",
    "PRO-Neg.Std", "PROVIA", "REALA-ACE", "Velvia", "WDR-709"
  };

  /** Every film simulation in the official pack is offered; the two Rec.709 transforms are not. */
  @Test
  public void everyFilmSimulationInThePackIsOffered() {
    int films = 0;
    for (String lut : GFX_ETERNA_55_PACK) {
      boolean offered = false;
      for (String[] sim : FujiRecipe.SIMULATIONS) offered |= sim[0].equalsIgnoreCase(lut);
      if (FujiRecipe.isTechnical(lut)) {
        assertFalse(lut + " is a video transform", offered);
      } else {
        assertTrue(lut + " should be offered", offered);
        films++;
      }
    }
    assertEquals(10, films);
  }

  @Test
  public void standardSimulationHasNeutralSettings() {
    FujiRecipe r = FujiRecipe.standard("Velvia", "Velvia");
    assertEquals(FujiRecipe.DynamicRange.AUTO, r.dynamicRange);
    assertEquals(0, r.highlight, 0);
    assertEquals(0, r.shadow, 0);
    assertEquals(0, r.color);
    assertEquals(0, r.sharpness);
    assertEquals(0, r.noiseReduction);
    assertEquals(0, r.grainRoughness);
    assertEquals(0, r.colorChrome);
    assertEquals(0, r.colorChromeBlue);
    assertEquals(0, r.wbShiftRed);
    assertEquals(0, r.wbShiftBlue);
    assertEquals("Auto", r.whiteBalance);
    assertFalse(r.monochrome());
    FujiRecipe acros = FujiRecipe.standard("ACROS", "Acros");
    assertTrue(acros.monochrome());
    for (String[] row : acros.settings())
      if (row[0].equals("Color")) assertTrue(row[1].startsWith("n/a"));
  }

  /** Film-sheet thumbnails: greys stay grey and brighter stays brighter through an identity LUT. */
  @Test
  public void lutPreviewKeepsGreysNeutralAndOrdered() {
    int[] px = new int[64];
    for (int i = 0; i < px.length; i++) {
      int v = 20 + i * 3;
      px[i] = 0xff000000 | (v << 16) | (v << 8) | v;
    }
    dev.grain.photo.develop.LutPreview.apply(px, identity(33));
    int prev = -1;
    for (int p : px) {
      int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
      assertTrue("neutral " + r + "," + g + "," + b, Math.abs(r - g) <= 1 && Math.abs(b - g) <= 1);
      assertTrue("ordered", g >= prev);
      prev = g;
      assertEquals(0xff, (p >>> 24));
    }
  }
  static final FujiRecipe PLAIN =
      new FujiRecipe("plain", "Plain", "", "", "LUT", "LUT", FujiRecipe.DynamicRange.DR100,
          0, 0, 0, 0, -4, 0, 0, false, 0, 0, "As shot", 0, 0);

  interface Scene {
    /** Scene level of a neutral surface, relative to the RAW white level (values above 1 clip). */
    double level(int x, int y);
  }

  /** Develops a noise-free synthetic neutral scene through an identity LUT at a fixed gain. */
  static int[] developScene(int w, int h, Scene scene, FujiRecipe recipe) throws Exception {
    double[] n = {0.5, 1, 0.625};
    short[] data = new short[w * h];
    RawFrame f = new RawFrame(data, w, h, w);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int c = f.color(x, y);
        double v = scene.level(x, y) * (c == 0 ? n[0] : c == 2 ? n[2] : 1);
        data[y * w + x] = (short) Math.round(64 + Math.min(1, v) * (1023 - 64));
      }
    f.neutral = n;
    f.colorMatrix1 = CM;
    f.iso = 100;
    RawDeveloper.Options o = new RawDeveloper.Options();
    o.grain = false;
    o.threads = 2;
    o.fixedGain = 1.5;
    int[] out = new int[w * h];
    RawDeveloper.develop(f, recipe, identity(33), o, (y, rows, argb, width) ->
        System.arraycopy(argb, 0, out, y * width, rows * width));
    return out;
  }

  static FujiRecipe extras(FujiRecipe r, int halation, int bloom, int clarity) {
    FujiRecipe.Tuning t = r.tuning();
    t.halation = halation;
    t.bloom = bloom;
    t.clarity = clarity;
    return r.with(t);
  }

  /** Mean channel difference b - a (0 R, 1 G, 2 B) over pixels whose distance to (cx, cy) is in [r0, r1). */
  static double ringDiff(int[] a, int[] b, int w, int h, int cx, int cy, double r0, double r1, int ch) {
    double s = 0;
    int k = 0, shift = 16 - 8 * ch;
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        double d = Math.hypot(x - cx, y - cy);
        if (d < r0 || d >= r1) continue;
        s += ((b[y * w + x] >> shift) & 255) - ((a[y * w + x] >> shift) & 255);
        k++;
      }
    return s / k;
  }

  /** Halation and bloom only move light around: a uniform field, clipped or not, is unchanged. */
  @Test
  public void glowLeavesUniformFieldsUnchanged() throws Exception {
    int w = 256, h = 192;
    for (double level : new double[] {0.10, 4.0}) {
      int[] off = developScene(w, h, (x, y) -> level, PLAIN);
      int[] on = developScene(w, h, (x, y) -> level, extras(PLAIN, 10, 10, 0));
      for (int i = 0; i < off.length; i++)
        for (int sh = 0; sh <= 16; sh += 8) {
          int d = ((on[i] >> sh) & 255) - ((off[i] >> sh) & 255);
          assertTrue("level " + level + " pixel " + i + " differs by " + d, Math.abs(d) <= 1);
        }
    }
  }

  /** Halation around a clipped lamp is red-dominant and local; bloom around it is close to neutral. */
  @Test
  public void halationIsRedAroundBrightLightAndBloomIsNeutral() throws Exception {
    int w = 800, h = 600, cx = 400, cy = 300;
    Scene lamp = (x, y) -> Math.hypot(x - cx, y - cy) <= 6 ? 4.0 : 0.01;
    int[] off = developScene(w, h, lamp, PLAIN);
    int[] halo = developScene(w, h, lamp, extras(PLAIN, 10, 0, 0));
    double r = ringDiff(off, halo, w, h, cx, cy, 9, 14, 0);
    double g = ringDiff(off, halo, w, h, cx, cy, 9, 14, 1);
    double b = ringDiff(off, halo, w, h, cx, cy, 9, 14, 2);
    assertTrue("halation red " + r, r > 4);
    assertTrue("red dominant " + r + " vs green " + g, r > 1.5 * g);
    assertTrue("green above blue " + g + " vs " + b, g >= b - 0.5);
    double far = ringDiff(off, halo, w, h, cx, cy, 120, 1000, 0);
    assertTrue("halation stays local " + far, Math.abs(far) < 0.3);
    int[] bloom = developScene(w, h, lamp, extras(PLAIN, 0, 10, 0));
    double br = ringDiff(off, bloom, w, h, cx, cy, 9, 14, 0);
    double bg = ringDiff(off, bloom, w, h, cx, cy, 9, 14, 1);
    double bb = ringDiff(off, bloom, w, h, cx, cy, 9, 14, 2);
    assertTrue("bloom glows " + bg, bg > 4);
    assertTrue("bloom close to neutral " + br + "/" + bg + "/" + bb,
        Math.abs(br - bg) < 0.35 * bg && Math.abs(bb - bg) < 0.35 * bg);
  }

  static double greenStd(int[] img, int w, int h) {
    return flatStd(img, w, w / 8, w - w / 8, h / 8, h - h / 8);
  }

  static double greenMean(int[] img, int w, int h) {
    double s = 0;
    int k = 0;
    for (int y = h / 8; y < h - h / 8; y++)
      for (int x = w / 8; x < w - w / 8; x++) {
        s += (img[y * w + x] >> 8) & 255;
        k++;
      }
    return s / k;
  }

  /** Clarity changes mid-scale local contrast: negative softens, positive strengthens. */
  @Test
  public void clarityChangesLocalContrast() throws Exception {
    int w = 640, h = 480;
    Scene stripes = (x, y) -> 0.08 * (1 + 0.5 * Math.sin(2 * Math.PI * x / 24.0));
    int[] soft = developScene(w, h, stripes, extras(PLAIN, 0, 0, -5));
    int[] base = developScene(w, h, stripes, PLAIN);
    int[] hard = developScene(w, h, stripes, extras(PLAIN, 0, 0, 5));
    double s0 = greenStd(base, w, h), sm = greenStd(soft, w, h), sp = greenStd(hard, w, h);
    assertTrue("clarity -5 " + sm + " vs " + s0, sm < 0.9 * s0);
    assertTrue("clarity +5 " + sp + " vs " + s0, sp > 1.1 * s0);
    assertEquals(greenMean(base, w, h), greenMean(soft, w, h), 3);
    assertEquals(greenMean(base, w, h), greenMean(hard, w, h), 3);
  }

  /** Tuning clamps to the camera ranges and keeps the film's identity and LUT. */
  @Test
  public void tuningClampsAndKeepsTheFilm() {
    FujiRecipe reggie = FujiRecipe.REGGIES_PORTRA;
    FujiRecipe same = reggie.with(reggie.tuning());
    assertTrue(same.sameSettings(reggie));
    assertEquals(reggie.key, same.key);
    assertEquals(reggie.filmSimulation, same.filmSimulation);
    assertEquals(reggie.whiteBalance, same.whiteBalance);
    FujiRecipe.Tuning t = reggie.tuning();
    t.highlight = 7;
    t.shadow = 1.3;
    t.color = 9;
    t.sharpness = -6;
    t.noiseReduction = 5;
    t.clarity = -8;
    t.wbShiftRed = 12;
    t.wbShiftBlue = -12;
    t.grainRoughness = 0;
    t.grainLarge = true;
    t.colorChrome = 5;
    t.halation = 15;
    t.bloom = -2;
    t.dynamicRange = FujiRecipe.DynamicRange.DR400;
    FujiRecipe x = reggie.with(t);
    assertEquals(4, x.highlight, 0);
    assertEquals(1.5, x.shadow, 0);
    assertEquals(4, x.color);
    assertEquals(-4, x.sharpness);
    assertEquals(4, x.noiseReduction);
    assertEquals(-5, x.clarity);
    assertEquals(9, x.wbShiftRed);
    assertEquals(-9, x.wbShiftBlue);
    assertFalse("large grain needs grain", x.grainLarge);
    assertEquals(2, x.colorChrome);
    assertEquals(10, x.halation);
    assertEquals(0, x.bloom);
    assertEquals(FujiRecipe.DynamicRange.DR400, x.dynamicRange);
    assertFalse(x.sameSettings(reggie));
    assertEquals("Reggie’s Portra", x.name);
    FujiRecipe acros = FujiRecipe.standard("ACROS", "Acros");
    FujiRecipe.Tuning mono = acros.tuning();
    mono.color = 3;
    assertEquals("no colour on black and white", 0, acros.with(mono).color);
    assertEquals(0, reggie.halation);
    assertEquals(0, reggie.bloom);
  }

  /** Every Highlight / Shadow combination the app can set keeps the tone curve monotonic. */
  @Test
  public void toneCurveStaysMonotonicAcrossTuning() {
    for (int dr = 0; dr <= 2; dr++)
      for (double hl = FujiRecipe.TONE_MIN; hl <= FujiRecipe.TONE_MAX; hl += FujiRecipe.TONE_STEP)
        for (double sh = FujiRecipe.TONE_MIN; sh <= FujiRecipe.TONE_MAX; sh += FujiRecipe.TONE_STEP) {
          ToneModel.Curve c = ToneModel.curve(dr, hl, sh, true, 1);
          double prev = -1;
          for (double e = -10; e <= 8; e += 0.05) {
            double v = c.apply(FLog2.encode(0.18 * Math.pow(2, e)));
            assertTrue("DR" + (100 << dr) + " H" + hl + " S" + sh + " at " + e, v >= prev - 1e-9);
            prev = v;
          }
        }
  }
  /** Every position of every app control round-trips through the recipe and shows the default. */
  @Test
  public void tuningControlsCoverEveryEngineValue() {
    FujiRecipe reggie = FujiRecipe.REGGIES_PORTRA;
    java.util.List<dev.grain.photo.develop.TuningControl> all =
        new java.util.ArrayList<>(dev.grain.photo.develop.TuningControl.fujifilm(reggie));
    all.addAll(dev.grain.photo.develop.TuningControl.extras());
    java.util.Map<String, String> shown = new java.util.HashMap<>();
    for (dev.grain.photo.develop.TuningControl c : all) {
      shown.put(c.name, c.labels[c.at(reggie.tuning())]);
      for (int p = 0; p < c.labels.length; p++) {
        FujiRecipe.Tuning t = reggie.tuning();
        c.set(t, p);
        assertEquals(c.name + " position " + p, p, c.at(t));
        assertEquals(c.name + " survives with()", p, c.at(reggie.with(t).tuning()));
      }
    }
    assertEquals("DR-Auto", shown.get("Dynamic range"));
    assertEquals("+2", shown.get("WB shift red"));
    assertEquals("−4", shown.get("WB shift blue"));
    assertEquals("−1", shown.get("Highlight"));
    assertEquals("−1", shown.get("Shadow"));
    assertEquals("+2", shown.get("Color"));
    assertEquals("−2", shown.get("Sharpness"));
    assertEquals("−4", shown.get("High ISO NR"));
    assertEquals("0", shown.get("Clarity"));
    assertEquals("Weak · Small", shown.get("Grain"));
    assertEquals("Strong", shown.get("Color Chrome Effect"));
    assertEquals("Weak", shown.get("Color Chrome FX Blue"));
    assertEquals("Off", shown.get("Halation"));
    assertEquals("Off", shown.get("Bloom"));
    assertEquals("First version", shown.get("Development"));
    assertEquals(13, control(reggie, "Highlight").labels.length);
    assertEquals("Auto", shown.get("White balance"));
    for (dev.grain.photo.develop.TuningControl c :
        dev.grain.photo.develop.TuningControl.fujifilm(FujiRecipe.standard("ACROS", "Acros")))
      assertNotEquals("no Color control for Acros", "Color", c.name);
    FujiRecipe velvia = FujiRecipe.standard("Velvia", "Velvia");
    for (dev.grain.photo.develop.TuningControl c : dev.grain.photo.develop.TuningControl.fujifilm(velvia)) {
      if (!c.enabled(velvia.tuning())) continue; // Color temperature: Kelvin only
      String v = c.labels[c.at(velvia.tuning())];
      assertTrue(
          c.name + " standard is " + v,
          v.equals("0") || v.equals("Off") || v.equals("DR-Auto") || v.equals("Auto"));
    }
  }

  static dev.grain.photo.develop.TuningControl control(FujiRecipe film, String name) {
    for (dev.grain.photo.develop.TuningControl c : dev.grain.photo.develop.TuningControl.fujifilm(film))
      if (c.name.equals(name)) return c;
    return null;
  }

  // ---------------------------------------------------------------- v0.3.0: WB, DR-P, Acros filters

  /** This phone's DNG calibration (Redmi Note 13 Pro+ camera0): D65 and A matrices. */
  static final double[] PHONE_CM1 = {
    0.6667938232, -0.1588897705, -0.08573913574, -0.5739440918, 1.389785767, 0.1430206299,
    -0.137878418, 0.2651519775, 0.6036224365
  };
  static final double[] PHONE_CM2 = {
    1.531463623, -0.4696044922, -0.215057373, -0.4762268066, 1.445327759, 0.006698608398,
    -0.07174682617, 0.2387237549, 0.2329559326
  };
  static final double[] PHONE_FM1 = {
    0.6731414795, 0.1950378418, 0.09602355957, 0.276184082, 0.8182067871, -0.09440612793,
    0.02165222168, -0.2324523926, 1.036010742
  };
  static final double[] PHONE_FM2 = {
    0.5744934082, 0.1840057373, 0.2057037354, 0.1938171387, 0.7453765869, 0.06079101562,
    -0.01449584961, -0.5286865234, 1.368408203
  };

  /**
   * A fixed white balance round-trips: the neutral for a white point develops with exactly that
   * white, across Fujifilm's Kelvin range, even where this phone's two matrices would let the DNG
   * search settle on a second, wrong white (e.g. 4850 K read back as ~7200 K).
   */
  @Test
  public void fixedWhitePointRoundTripsOnThePhoneMatrices() {
    for (int k = FujiRecipe.KELVIN_MIN; k <= FujiRecipe.KELVIN_MAX; k += 250)
      for (double duv : new double[] {0, 0.0075, 0.015}) {
        double[] xy = ColorMath.whiteXy(k, duv);
        double[] n = ColorMath.neutralForXy(xy[0], xy[1], PHONE_CM1, PHONE_CM2, null, null, 21, 17);
        assertEquals(1.0, n[1], 1e-12);
        ColorMath.Result r =
            ColorMath.cameraToRec2020(n, xy, PHONE_CM1, PHONE_CM2, PHONE_FM1, PHONE_FM2, null, null, 21, 17);
        assertEquals("x at " + k, xy[0], r.whiteX, 1e-12);
        assertEquals("y at " + k, xy[1], r.whiteY, 1e-12);
        double[] m = r.cameraToRec2020;
        for (int row = 0; row < 3; row++)
          assertEquals("white stays white at " + k, 1.0, m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2], 1e-9);
        // the Planckian white sits on the locus (McCamy's CCT readout is approximate: compare in mired)
        if (duv == 0) assertEquals(1e6 / k, 1e6 / ColorMath.cctFromXy(xy[0], xy[1]), 2);
        // Duv moves the white off the locus by the asked distance in CIE 1960 uv
        double[] uvW = ColorMath.uv(xy[0], xy[1]), p = ColorMath.planckXy(k), uvP = ColorMath.uv(p[0], p[1]);
        assertEquals(duv, Math.hypot(uvW[0] - uvP[0], uvW[1] - uvP[1]), 1e-6);
        if (duv > 0) assertTrue("positive Duv is above the locus (greener)", uvW[1] > uvP[1]);
      }
    // the case that broke the DNG search: same neutral, white given vs searched
    double[] xy = ColorMath.planckXy(4850);
    double[] n = ColorMath.neutralForXy(xy[0], xy[1], PHONE_CM1, PHONE_CM2, null, null, 21, 17);
    ColorMath.Result given =
        ColorMath.cameraToRec2020(n, xy, PHONE_CM1, PHONE_CM2, PHONE_FM1, PHONE_FM2, null, null, 21, 17);
    assertEquals(4850, given.cct, 30);
  }

  /** Warmer whites need less red gain and more blue gain, monotonically (Kelvin ordering). */
  @Test
  public void kelvinWhitesAreOrdered() {
    double prevR = 0, prevB = Double.MAX_VALUE;
    for (int k = FujiRecipe.KELVIN_MIN; k <= FujiRecipe.KELVIN_MAX; k += 50) {
      double[] xy = ColorMath.planckXy(k);
      double[] n = ColorMath.neutralForXy(xy[0], xy[1], PHONE_CM1, PHONE_CM2, null, null, 21, 17);
      double gainR = 1 / n[0], gainB = 1 / n[2];
      assertTrue("red gain rises with Kelvin at " + k, gainR > prevR);
      assertTrue("blue gain falls with Kelvin at " + k, gainB < prevB);
      prevR = gainR;
      prevB = gainB;
    }
    // presets in the same order as their white points
    FujiRecipe.WhiteBalance[] warmToCool = {
      FujiRecipe.WhiteBalance.INCANDESCENT, FujiRecipe.WhiteBalance.FLUORESCENT_3,
      FujiRecipe.WhiteBalance.DAYLIGHT, FujiRecipe.WhiteBalance.SHADE
    };
    for (int i = 1; i < warmToCool.length; i++) assertTrue(warmToCool[i].cct > warmToCool[i - 1].cct);
  }

  static final class Developed {
    int[] px;
    Map<String, Object> stats;
  }

  interface ColourScene {
    /** RAW level of a CFA channel (0 R, 1 G, 2 B) at (x, y), relative to white (above 1 clips). */
    double level(int x, int y, int c);
  }

  static Developed developColour(
      int w, int h, ColourScene scene, double[] asShot, FujiRecipe recipe, CubeLut lut, double fixedGain)
      throws Exception {
    short[] data = new short[w * h];
    RawFrame f = new RawFrame(data, w, h, w);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        double v = scene.level(x, y, f.color(x, y));
        data[y * w + x] = (short) Math.round(64 + Math.max(0, Math.min(1, v)) * (1023 - 64));
      }
    f.neutral = asShot;
    f.colorMatrix1 = CM;
    f.iso = 100;
    RawDeveloper.Options o = new RawDeveloper.Options();
    o.grain = false;
    o.threads = 2;
    o.fixedGain = fixedGain;
    Developed d = new Developed();
    d.px = new int[w * h];
    d.stats =
        RawDeveloper.develop(f, recipe, lut, o, (y, rows, argb, width) ->
            System.arraycopy(argb, 0, d.px, y * width, rows * width));
    return d;
  }

  static FujiRecipe whiteBalance(FujiRecipe r, FujiRecipe.WhiteBalance mode, int kelvin) {
    FujiRecipe.Tuning t = r.tuning();
    t.wbMode = mode;
    t.kelvin = kelvin;
    return r.with(t);
  }

  static double mean(int[] px, int w, int x0, int x1, int y0, int y1, int ch) {
    double s = 0;
    int shift = 16 - 8 * ch;
    for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) s += (px[y * w + x] >> shift) & 255;
    return s / ((x1 - x0) * (y1 - y0));
  }

  /**
   * A fixed white balance ignores the phone's as-shot white and develops a surface lit by its own
   * white point as neutral; a warmer preset on the same light renders it blue, a cooler one warm.
   */
  @Test
  public void fixedWhiteBalanceUsesItsWhitePointNotTheAsShotWhite() throws Exception {
    int w = 128, h = 96;
    double[] xy = ColorMath.whiteXy(4850, 0);
    double[] lit = ColorMath.neutralForXy(xy[0], xy[1], CM, null, null, null, 21, 21);
    ColourScene grey = (x, y, c) -> 0.12 * lit[c];
    FujiRecipe daylight = whiteBalance(PLAIN, FujiRecipe.WhiteBalance.DAYLIGHT, 5500);
    Developed a = developColour(w, h, grey, new double[] {0.5, 1, 0.625}, daylight, identity(33), 1.5);
    Developed b = developColour(w, h, grey, new double[] {0.7, 1, 0.4}, daylight, identity(33), 1.5);
    assertArrayEquals("as-shot white must not matter", a.px, b.px);
    double[] neutral = (double[]) a.stats.get("whiteBalanceNeutral");
    assertEquals(lit[0], neutral[0], 1e-5);
    assertEquals(lit[2], neutral[2], 1e-5);
    double r = mean(a.px, w, 8, w - 8, 8, h - 8, 0), g = mean(a.px, w, 8, w - 8, 8, h - 8, 1);
    double bl = mean(a.px, w, 8, w - 8, 8, h - 8, 2);
    assertEquals("neutral R", g, r, 1.0);
    assertEquals("neutral B", g, bl, 1.0);
    // Auto follows the as-shot white instead
    Developed autoA = developColour(w, h, grey, new double[] {0.5, 1, 0.625}, PLAIN, identity(33), 1.5);
    Developed autoB = developColour(w, h, grey, new double[] {0.7, 1, 0.4}, PLAIN, identity(33), 1.5);
    assertFalse(java.util.Arrays.equals(autoA.px, autoB.px));
    // Incandescent on daylight-ish light: blue; Shade: warm. Kelvin equals the same Planckian white.
    Developed inc =
        developColour(w, h, grey, lit, whiteBalance(PLAIN, FujiRecipe.WhiteBalance.INCANDESCENT, 5500), identity(33), 1.5);
    Developed shade =
        developColour(w, h, grey, lit, whiteBalance(PLAIN, FujiRecipe.WhiteBalance.SHADE, 5500), identity(33), 1.5);
    assertTrue(mean(inc.px, w, 8, w - 8, 8, h - 8, 2) > mean(inc.px, w, 8, w - 8, 8, h - 8, 0) + 20);
    assertTrue(mean(shade.px, w, 8, w - 8, 8, h - 8, 0) > mean(shade.px, w, 8, w - 8, 8, h - 8, 2) + 3);
    Developed k4850 =
        developColour(w, h, grey, lit, whiteBalance(PLAIN, FujiRecipe.WhiteBalance.KELVIN, 4850), identity(33), 1.5);
    assertArrayEquals(a.px, k4850.px);
    // the WB shift still applies on top of a fixed white balance
    FujiRecipe.Tuning t = daylight.tuning();
    t.wbShiftRed = 6;
    Developed shifted = developColour(w, h, grey, lit, daylight.with(t), identity(33), 1.5);
    assertTrue(mean(shifted.px, w, 8, w - 8, 8, h - 8, 0) > r + 3);
  }

  static FujiRecipe range(FujiRecipe r, FujiRecipe.DynamicRange dr, double highlight, double shadow) {
    FujiRecipe.Tuning t = r.tuning();
    t.dynamicRange = dr;
    t.highlight = highlight;
    t.shadow = shadow;
    return r.with(t);
  }

  /**
   * D-Range Priority: Weak is DR400 with Highlight and Shadow -2; Strong also folds one more stop of
   * highlights into white when the RAW holds it. Under DR-P the Highlight/Shadow values are ignored.
   */
  @Test
  public void dRangePriorityIsDr400FlatToneAndStrongCompressesHighlights() throws Exception {
    int w = 256, h = 64;
    Scene ramp = (x, y) -> 0.002 * Math.pow(2, 9.0 * x / (w - 1)); // 0.002 .. 1.02 of RAW white
    int[] weak = developScene(w, h, ramp, range(PLAIN, FujiRecipe.DynamicRange.DRP_WEAK, 0, 0));
    int[] dr400 = developScene(w, h, ramp, range(PLAIN, FujiRecipe.DynamicRange.DR400, -2, -2));
    assertArrayEquals("DR-P Weak = DR400 with H/S -2", dr400, weak);
    int[] weakTuned = developScene(w, h, ramp, range(PLAIN, FujiRecipe.DynamicRange.DRP_WEAK, 3, 4));
    assertArrayEquals("Highlight/Shadow are set by DR-P", weak, weakTuned);
    assertEquals(-2, RawDeveloper.DRP_HIGHLIGHT, 0);
    assertEquals(-2, RawDeveloper.DRP_SHADOW, 0);
    // a RAW with headroom (clips > 1 EV above the film's white): Strong maps one more stop of the
    // scene under white, so the upper tones are darker; Weak keeps the plain curve
    ColourScene wide =
        (x, y, c) -> 0.0005 * Math.pow(2, 11.0 * x / 255) * (c == 0 ? 0.5 : c == 2 ? 0.625 : 1);
    double[] asShot = {0.5, 1, 0.625};
    Developed sw =
        developColour(256, 64, wide, asShot, range(PLAIN, FujiRecipe.DynamicRange.DRP_WEAK, 0, 0), whiteAt(33, 0.6), 8);
    Developed ss =
        developColour(256, 64, wide, asShot, range(PLAIN, FujiRecipe.DynamicRange.DRP_STRONG, 0, 0), whiteAt(33, 0.6), 8);
    double clipEv = ((Number) ss.stats.get("rawClipEv")).doubleValue();
    double whiteEv = ((Number) ss.stats.get("curveWhiteEv")).doubleValue();
    assertTrue("test RAW has headroom: clip " + clipEv + " white " + whiteEv, clipEv > whiteEv + 1);
    assertEquals(1.0, ((Number) sw.stats.get("shoulderScale")).doubleValue(), 1e-9);
    double strongShoulder = ((Number) ss.stats.get("shoulderScale")).doubleValue();
    assertTrue("Strong compresses: " + strongShoulder, strongShoulder < 0.95);
    int brighter = 0, darker = 0;
    for (int x = 8; x < 248; x++) {
      int a = (sw.px[32 * 256 + x] >> 8) & 255, b = (ss.px[32 * 256 + x] >> 8) & 255;
      if (b > a + 1) brighter++;
      if (b < a - 1) darker++;
    }
    assertTrue("Strong is darker in the highlights", darker > 10);
    assertEquals("and never brighter", 0, brighter);
    // the Strong shoulder is monotonic (column means: the dither is per pixel; checked over the upper
    // half, where the noise-free test RAW is well clear of its 1-DN quantisation steps)
    double prev = -1;
    for (int x = 128; x < 252; x += 4) {
      double g = mean(ss.px, 256, x, x + 1, 8, 56, 1);
      assertTrue("monotonic at " + x + ": " + g + " after " + prev, g >= prev - 0.5);
      prev = Math.max(prev, g);
    }
  }

  /** A test cube that reaches white at the given F-Log2 code (about +3 EV for 0.6), like a film LUT. */
  static CubeLut whiteAt(int n, double code) {
    StringBuilder b = new StringBuilder("LUT_3D_SIZE " + n + "\n");
    for (int bl = 0; bl < n; bl++)
      for (int g = 0; g < n; g++)
        for (int r = 0; r < n; r++)
          b.append(Math.min(1, r / (n - 1.0) / code)).append(' ')
              .append(Math.min(1, g / (n - 1.0) / code)).append(' ')
              .append(Math.min(1, bl / (n - 1.0) / code)).append('\n');
    try {
      return CubeLut.parse(new StringReader(b.toString()));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  /** A black-and-white test cube: grey = F-Log2 code of the linear mix 0.25 R + 0.60 G + 0.15 B. */
  static CubeLut monoCube(int n) {
    StringBuilder b = new StringBuilder("LUT_3D_SIZE " + n + "\n");
    for (int bl = 0; bl < n; bl++)
      for (int g = 0; g < n; g++)
        for (int r = 0; r < n; r++) {
          double y =
              0.25 * Math.max(0, FLog2.decode(r / (n - 1.0)))
                  + 0.60 * Math.max(0, FLog2.decode(g / (n - 1.0)))
                  + 0.15 * Math.max(0, FLog2.decode(bl / (n - 1.0)));
          double c = Math.max(0, Math.min(1, FLog2.encode(y)));
          b.append(c).append(' ').append(c).append(' ').append(c).append('\n');
        }
    try {
      return CubeLut.parse(new StringReader(b.toString()));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  static FujiRecipe filter(FujiRecipe r, int filter) {
    FujiRecipe.Tuning t = r.tuning();
    t.monoFilter = filter;
    return r.with(t);
  }

  /**
   * Acros filters: greys keep the LUT's exact rendering; +R lightens red, +G darkens it, +Ye sits
   * between; the picture stays black and white.
   */
  @Test
  public void acrosFiltersChangeColoursButNotGreys() throws Exception {
    int w = 384, h = 64;
    CubeLut mono = monoCube(33);
    double[] n = {0.5, 1, 0.625};
    // left: grey; right: red (camera levels before white balance). Chroma processing spreads colour
    // a few dozen pixels across the edge, so greys are checked well away from it.
    ColourScene scene =
        (x, y, c) -> x < w / 2 ? 0.10 * n[c] : (c == 0 ? 0.16 : c == 1 ? 0.03 : 0.02);
    FujiRecipe acros = FujiRecipe.standard("ACROS", "Acros");
    Developed none = developColour(w, h, scene, n, acros, mono, 1.5);
    Developed ye = developColour(w, h, scene, n, filter(acros, 1), mono, 1.5);
    Developed red = developColour(w, h, scene, n, filter(acros, 2), mono, 1.5);
    Developed green = developColour(w, h, scene, n, filter(acros, 3), mono, 1.5);
    double[] weights = (double[]) red.stats.get("monoLutWeights");
    assertEquals(0.25, weights[0], 0.02);
    assertEquals(0.60, weights[1], 0.02);
    assertEquals(0.15, weights[2], 0.02);
    for (Developed d : new Developed[] {ye, red, green}) {
      for (int y = 4; y < h - 4; y++)
        for (int x = 4; x < w / 2 - 64; x++)
          assertEquals("grey unchanged at " + x + "," + y, none.px[y * w + x], d.px[y * w + x]);
      for (int p : d.px) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        assertTrue("black and white", r == g && g == b);
      }
    }
    double base = mean(none.px, w, w / 2 + 64, w - 4, 4, h - 4, 1);
    double withYe = mean(ye.px, w, w / 2 + 64, w - 4, 4, h - 4, 1);
    double withRed = mean(red.px, w, w / 2 + 64, w - 4, 4, h - 4, 1);
    double withGreen = mean(green.px, w, w / 2 + 64, w - 4, 4, h - 4, 1);
    assertTrue("+R lightens red: " + base + " -> " + withRed, withRed > base + 8);
    assertTrue("+Ye lightens red less: " + withYe, withYe > base + 2 && withYe < withRed);
    assertTrue("+G darkens red: " + withGreen, withGreen < base - 2);
    // a colour film ignores the filter setting
    assertEquals(0, filter(FujiRecipe.standard("Velvia", "Velvia"), 2).monoFilter);
  }

  /** The six v0.3.0 recipes carry their published settings, and every control shows them. */
  @Test
  public void publishedRecipesMatchFujiXWeekly() {
    FujiRecipe gold = FujiRecipe.KODAK_GOLD_200;
    assertEquals("CLASSIC-CHROME", gold.filmSimulation);
    assertEquals(FujiRecipe.WhiteBalance.DAYLIGHT, gold.wbMode);
    assertEquals(4, gold.wbShiftRed);
    assertEquals(-5, gold.wbShiftBlue);
    assertEquals(FujiRecipe.DynamicRange.DR400, gold.dynamicRange);
    assertEquals(-1.5, gold.highlight, 0);
    assertEquals(0.5, gold.shadow, 0);
    assertEquals(3, gold.color);
    assertEquals(2, gold.grainRoughness);
    assertFalse(gold.grainLarge);
    assertEquals(1, gold.colorChrome);
    assertEquals(0, gold.colorChromeBlue);
    assertEquals(-2, gold.sharpness);
    assertEquals(-4, gold.noiseReduction);
    assertEquals(-2, gold.clarity);

    FujiRecipe koda = FujiRecipe.KODACHROME_64;
    assertEquals(FujiRecipe.DynamicRange.DR200, koda.dynamicRange);
    assertEquals(2, koda.wbShiftRed);
    assertEquals(-5, koda.wbShiftBlue);
    assertEquals(3, koda.clarity);
    assertEquals(1, koda.sharpness);
    assertEquals(2, koda.colorChrome);

    FujiRecipe pacific = FujiRecipe.PACIFIC_BLUES;
    assertEquals("CLASSIC-Neg.", pacific.filmSimulation);
    assertEquals(FujiRecipe.WhiteBalance.KELVIN, pacific.wbMode);
    assertEquals(5800, pacific.kelvin);
    assertEquals("5800K", pacific.whiteBalance);
    assertEquals(-2, pacific.highlight, 0);
    assertEquals(3, pacific.shadow, 0);
    assertEquals(4, pacific.color);
    assertTrue(pacific.grainLarge);

    FujiRecipe arizona = FujiRecipe.VIBRANT_ARIZONA;
    assertEquals(FujiRecipe.DynamicRange.DRP_STRONG, arizona.dynamicRange);
    assertEquals(4350, arizona.kelvin);
    assertEquals(6, arizona.wbShiftRed);
    assertEquals(-8, arizona.wbShiftBlue);
    assertEquals(0, arizona.colorChrome);
    assertEquals(1, arizona.colorChromeBlue);
    assertFalse("Highlight is set by DR-P", control(arizona, "Highlight").enabled(arizona.tuning()));
    assertEquals("Set by DR-P", control(arizona, "Shadow").disabledLabel);
    assertEquals("DR-P Strong", control(arizona, "Dynamic range").labels[control(arizona, "Dynamic range").at(arizona.tuning())]);
    assertEquals("4350K", control(arizona, "Color temperature").labels[control(arizona, "Color temperature").at(arizona.tuning())]);

    FujiRecipe cine = FujiRecipe.CINESTILL_800T;
    assertEquals("ETERNA", cine.filmSimulation);
    assertEquals(FujiRecipe.WhiteBalance.FLUORESCENT_3, cine.wbMode);
    assertEquals(-6, cine.wbShiftRed);
    assertEquals(-4, cine.wbShiftBlue);
    assertEquals(-5, cine.clarity);
    assertEquals(-3, cine.sharpness);
    assertFalse("Color temperature is Kelvin only", control(cine, "Color temperature").enabled(cine.tuning()));

    FujiRecipe trix = FujiRecipe.KODAK_TRI_X_400;
    assertTrue(trix.monochrome());
    assertEquals(0, trix.monoFilter);
    assertEquals(9, trix.wbShiftRed);
    assertEquals(-9, trix.wbShiftBlue);
    assertEquals(4, trix.clarity);
    assertEquals(3, trix.shadow, 0);
    assertEquals("None", control(trix, "Filter").labels[control(trix, "Filter").at(trix.tuning())]);
    assertNull("no Color for black and white", control(trix, "Color"));
    assertNull("no Color Chrome Effect for black and white", control(trix, "Color Chrome Effect"));
    assertNull("no FX Blue for black and white", control(trix, "Color Chrome FX Blue"));
    assertNull("only black and white films have a filter", control(gold, "Filter"));

    for (FujiRecipe r : FujiRecipe.PUBLISHED) {
      assertTrue(
          r.key,
          r.source.startsWith("https://fujixweekly.com/")
              || r.source.startsWith("https://carlcruzsnaps.com/"));
      assertTrue(r.key + " is its own default", r.sameSettings(r.with(r.tuning())));
      for (dev.grain.photo.develop.TuningControl c : dev.grain.photo.develop.TuningControl.fujifilm(r))
        for (int p = 0; p < c.labels.length; p++) {
          FujiRecipe.Tuning t = r.tuning();
          c.set(t, p);
          assertEquals(r.key + " " + c.name + " position " + p, p, c.at(r.with(t).tuning()));
        }
      if (r.author.equals("Reggie Ballesteros"))
        assertTrue(r.key + ": no typical EV, at Reggie's request", r.exposureNote.isEmpty());
      else assertFalse(r.key + " typical EV", r.exposureNote.isEmpty());
    }
    // Reggie's receipt text is unchanged by the new white-balance model
    assertEquals("Auto; R +2, B −4", FujiRecipe.REGGIES_PORTRA.settings()[2][1]);
  }

  /** Out-of-range tuning is clamped to the camera ranges; labels parse like the camera menus. */
  @Test
  public void whiteBalanceAndFilterTuningClamps() {
    FujiRecipe.Tuning t = PLAIN.tuning();
    t.wbMode = FujiRecipe.WhiteBalance.KELVIN;
    t.kelvin = 1000;
    assertEquals(FujiRecipe.KELVIN_MIN, PLAIN.with(t).kelvin);
    t.kelvin = 25000;
    assertEquals(FujiRecipe.KELVIN_MAX, PLAIN.with(t).kelvin);
    t.kelvin = 4355;
    assertEquals("Fujifilm's 10 K steps", 4360, PLAIN.with(t).kelvin);
    assertEquals("4360K", PLAIN.with(t).whiteBalance);
    t.wbMode = null;
    assertEquals(FujiRecipe.WhiteBalance.AUTO, PLAIN.with(t).wbMode);
    FujiRecipe acros = FujiRecipe.standard("ACROS", "Acros");
    FujiRecipe.Tuning a = acros.tuning();
    a.monoFilter = 9;
    assertEquals(FujiRecipe.MONO_FILTERS.length - 1, acros.with(a).monoFilter);
    a.monoFilter = -3;
    assertEquals(0, acros.with(a).monoFilter);
    assertEquals(FujiRecipe.WhiteBalance.KELVIN, FujiRecipe.WhiteBalance.parse("5800K"));
    assertEquals(FujiRecipe.WhiteBalance.FLUORESCENT_3, FujiRecipe.WhiteBalance.parse("fluorescent 3"));
    assertEquals(FujiRecipe.WhiteBalance.AUTO, FujiRecipe.WhiteBalance.parse(null));
    assertEquals(FujiRecipe.WhiteBalance.AUTO, FujiRecipe.WhiteBalance.parse("As shot"));
    String[] labels = new String[FujiRecipe.DynamicRange.values().length];
    for (int i = 0; i < labels.length; i++) labels[i] = FujiRecipe.DynamicRange.values()[i].label;
    assertArrayEquals(new String[] {"DR-Auto", "DR100", "DR200", "DR400", "DR-P Weak", "DR-P Strong"}, labels);
    // the Kelvin value only counts while the white balance is Kelvin
    FujiRecipe.Tuning k = FujiRecipe.REGGIES_PORTRA.tuning();
    k.kelvin = 7000;
    assertTrue(FujiRecipe.REGGIES_PORTRA.with(k).sameSettings(FujiRecipe.REGGIES_PORTRA));
  }

  // ------------------------------------------------- v0.3.1: First version (Grain v0.2.0) development

  /** 6 x 4 colour patches (one clipped), shot noise, this phone's calibration, ISO 2742. */
  static RawFrame goldenFrame() {
    int w = 192, h = 128;
    double[] n = {0.4812030075, 1, 0.5707915273};
    short[] data = new short[w * h];
    RawFrame f = new RawFrame(data, w, h, w);
    f.cfa = RawFrame.GRBG;
    Random rnd = new Random(20261106);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int p = (y / 32) * 6 + x / 32, c = f.color(x, y);
        double hue = 2 * Math.PI * p / 24.0, sat = p % 4 == 3 ? 0 : 0.6;
        double level = 0.02 + 0.03 * (p % 6) + 0.012 * (p / 6);
        double k = 1 + sat * Math.cos(hue - (c == 0 ? 0 : c == 1 ? 2.094 : 4.189));
        double v = p == 7 ? 1.5 : level * k * n[c];
        v += rnd.nextGaussian() * Math.sqrt(6e-4 * Math.max(0, v) + 1.5e-6);
        data[y * w + x] = (short) Math.max(0, Math.min(1023, Math.round(64 + v * (1023 - 64))));
      }
    f.neutral = n;
    f.colorMatrix1 = PHONE_CM1;
    f.colorMatrix2 = PHONE_CM2;
    f.forwardMatrix1 = PHONE_FM1;
    f.forwardMatrix2 = PHONE_FM2;
    f.illuminant1 = 21;
    f.illuminant2 = 17;
    f.iso = 2742;
    return f;
  }

  /** A 17-point film-like LUT: contrast around mid-grey plus a little saturation. */
  static CubeLut goldenLut() {
    int m = 17;
    StringBuilder b = new StringBuilder("TITLE \"golden\"\nLUT_3D_SIZE " + m + "\n");
    for (int bl = 0; bl < m; bl++)
      for (int g = 0; g < m; g++)
        for (int r = 0; r < m; r++) {
          double[] c = {r / (m - 1.0), g / (m - 1.0), bl / (m - 1.0)};
          double y = 0.25 * (c[0] + 2 * c[1] + c[2]);
          for (int i = 0; i < 3; i++) {
            double v = 0.5 + 1.4 * (c[i] - 0.45) + 0.25 * (c[i] - y);
            b.append(Math.max(0, Math.min(1, v))).append(i < 2 ? ' ' : '\n');
          }
        }
    try {
      return CubeLut.parse(new StringReader(b.toString()));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  static int[] developGolden(FujiRecipe film, boolean grain, java.util.List<Map<String, Object>> stats)
      throws Exception {
    RawFrame f = goldenFrame();
    RawDeveloper.Options o = new RawDeveloper.Options();
    o.grainSeed = 7;
    o.grain = grain;
    o.threads = 2;
    int[] out = new int[f.width * f.height];
    Map<String, Object> s = RawDeveloper.develop(f, film, goldenLut(), o, (y, rows, argb, width) ->
        System.arraycopy(argb, 0, out, y * width, rows * width));
    if (stats != null) stats.add(0, s);
    return out;
  }

  static long fnv(int[] img) {
    long hash = 0xcbf29ce484222325L;
    for (int p : img) {
      hash ^= p;
      hash *= 0x100000001b3L;
    }
    return hash;
  }

  /** Green noise inside the flat grey patches (3, 7 is clipped, 11, 15 ...): away from patch edges. */
  static double patchNoise(int[] img) {
    double sum = 0;
    int k = 0;
    for (int p : new int[] {3, 11, 15, 19, 23}) {
      int x0 = (p % 6) * 32, y0 = (p / 6) * 32;
      sum += flatStd(img, 192, x0 + 8, x0 + 24, y0 + 8, y0 + 24);
      k++;
    }
    return sum / k;
  }

  static FujiRecipe developedAs(FujiRecipe film, FujiRecipe.Development d, int sharpness, int nr) {
    FujiRecipe.Tuning t = film.tuning();
    t.development = d;
    t.sharpness = sharpness;
    t.noiseReduction = nr;
    return film.with(t);
  }

  static FujiRecipe withFxBlue(FujiRecipe film, int level) {
    FujiRecipe.Tuning t = film.tuning();
    t.colorChromeBlue = level;
    return film.with(t);
  }

  /**
   * Reggie's Portra develops as Grain v0.2.0 did, except FX Blue: v0.3.3 replaced the v0.2.0 FX
   * Blue (at most 5 %, gated to very saturated blues) with the one fitted to Fujifilm camera
   * JPEGs, shared by both developments. With FX Blue Off the output must stay bit-identical to
   * Grain v0.3.2, so nothing but FX Blue changed: hashes computed by v0.3.2, whose First version
   * was bit-identical to v0.2.0 (golden 0xed66690e26edf621 at Portra's FX Blue Weak, computed by
   * the v0.2.0 engine with today's grain placement). The default hash pins the v0.3.3 FX Blue.
   */
  @Test
  public void firstVersionIsGrainV020ExceptTheFittedFxBlue() throws Exception {
    FujiRecipe portra = FujiRecipe.REGGIES_PORTRA;
    assertEquals(
        "First version without FX Blue changed: it must stay bit-identical to Grain v0.2.0",
        "ca376d33142387bf",
        Long.toHexString(fnv(developGolden(withFxBlue(portra, 0), true, null))));
    FujiRecipe clean = developedAs(portra, FujiRecipe.Development.CLEAN, -2, -4);
    assertEquals(
        "Clean without FX Blue changed",
        "661181fc72585967",
        Long.toHexString(fnv(developGolden(withFxBlue(clean, 0), true, null))));
    long v033 = fnv(developGolden(portra, true, null));
    assertEquals("the v0.3.3 FX Blue changed", "8f487b5241881196", Long.toHexString(v033));
    assertNotEquals("the v0.2.0 FX Blue is gone", 0xed66690e26edf621L, v033);
    assertNotEquals(v033, fnv(developGolden(clean, true, null)));
  }

  // ---------------------------------------------- v0.3.3: Color Chrome FX Blue fitted to Fujifilm

  /**
   * Mean sky colours (sRGB code values) of Fujifilm X100V camera JPEGs of one clear sky at FX Blue
   * Off / Weak / Strong (Fuji X Weekly, 2020-06-30, Kodachrome 64 recipe), ten 280 x 40 px bands
   * from the deep top of the sky to the pale bottom.
   */
  static final double[][] X100V_SKY_OFF = {
    {127.2, 156.5, 241.7}, {133.8, 161.9, 249.2}, {140.1, 167.4, 253.9}, {145.8, 173.2, 254.5},
    {151.3, 178.8, 254.4}, {156.8, 184.2, 254.4}, {161.8, 189.7, 254.4}, {166.9, 194.9, 254.4},
    {171.7, 200.3, 254.3}, {176.2, 205.2, 254.5}
  };
  static final double[][] X100V_SKY_WEAK = {
    {108.2, 138.1, 223.9}, {114.6, 143.8, 232.0}, {121.1, 149.1, 238.5}, {127.7, 155.3, 240.9},
    {134.2, 161.6, 241.4}, {140.5, 168.3, 242.2}, {147.0, 175.0, 243.5}, {154.0, 182.1, 245.0},
    {160.7, 189.4, 246.8}, {167.3, 196.1, 248.3}
  };
  static final double[][] X100V_SKY_STRONG = {
    {89.2, 119.4, 206.2}, {95.0, 124.9, 213.6}, {101.2, 130.1, 220.0}, {108.9, 137.2, 223.4},
    {116.7, 144.7, 225.0}, {124.2, 152.3, 226.7}, {132.0, 160.5, 229.3}, {141.0, 169.3, 232.7},
    {149.6, 178.6, 236.3}, {158.1, 187.1, 239.5}
  };

  /**
   * FX Blue reproduces Fujifilm's own on that sky: Weak and Strong within 2.5 code values in red
   * and green (fit: rms 0.7). Blue is checked on the two top bands only: in the others the Off
   * JPEG's blue is clipped at 255, so Fujifilm's blue there drops less than it would.
   */
  @Test
  public void fxBlueMatchesFujifilmX100vSky() {
    double darkenedBy = 0;
    for (int i = 0; i < X100V_SKY_OFF.length; i++)
      for (int level = 1; level <= 2; level++) {
        double[] off = X100V_SKY_OFF[i], want = level == 2 ? X100V_SKY_STRONG[i] : X100V_SKY_WEAK[i];
        float[] v = {(float) (off[0] / 255), (float) (off[1] / 255), (float) (off[2] / 255)};
        RawDeveloper.fxBlue(v, RawDeveloper.fxBlueDepth(level));
        for (int c = 0; c < (i < 2 ? 3 : 2); c++)
          assertEquals("band " + i + ", level " + level + ", channel " + c, want[c], v[c] * 255, c < 2 ? 2.5 : 3.0);
        if (i == 0 && level == 2) darkenedBy = 1 - lumaCode(v) * 255 / lumaCode(off);
      }
    assertEquals("Strong darkens the deep sky ~24 %, like a polarizer", 0.24, darkenedBy, 0.02);
  }

  static double lumaCode(double[] c) {
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  }

  static double lumaCode(float[] c) {
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  }

  /** Weak is half of Strong; neutrals, skin, reds, yellows, greens and grey-blues are untouched. */
  @Test
  public void fxBlueOnlyTouchesBlues() {
    float strong = RawDeveloper.fxBlueDepth(2);
    assertEquals(0f, RawDeveloper.fxBlueDepth(0), 0f);
    assertEquals(strong / 2, RawDeveloper.fxBlueDepth(1), 1e-7f);
    float[][] untouched = {
      {0.5f, 0.5f, 0.5f}, {0.95f, 0.95f, 0.95f}, {0.04f, 0.04f, 0.04f}, // greys
      {0.88f, 0.67f, 0.55f}, {0.55f, 0.38f, 0.29f}, // light and dark skin
      {0.8f, 0.2f, 0.15f}, {0.9f, 0.8f, 0.2f}, {0.25f, 0.65f, 0.3f}, // red, yellow, green
      {0.62f, 0.66f, 0.72f} // a grey-blue cloud, below the chroma gate
    };
    for (float[] c : untouched) {
      float[] v = c.clone();
      RawDeveloper.fxBlue(v, strong);
      assertArrayEquals(java.util.Arrays.toString(c), c, v, 0f);
    }
    float[] sky = {0.45f, 0.6f, 0.9f}, v = sky.clone();
    RawDeveloper.fxBlue(v, strong);
    assertTrue("a blue sky darkens", lumaCode(v) < 0.88 * lumaCode(sky));
    assertEquals("same hue: every channel drops by the same amount", sky[2] - sky[0], v[2] - v[0], 1e-6);
    assertEquals(sky[1] - sky[0], v[1] - v[0], 1e-6);
    float[] navy = {0.05f, 0.08f, 0.3f};
    RawDeveloper.fxBlue(navy, strong);
    assertTrue("dark blues deepen without crushing to black", navy[0] >= 0 && lumaCode(navy) > 0.7 * lumaCode(new float[] {0.05f, 0.08f, 0.3f}));
  }

  /**
   * In the engine, FX Blue Strong deepens the bluest golden patch by several percent in both
   * developments (v0.3.3: 6.5 % Clean, 7.1 % First version; v0.3.2's FX Blue: 0.4 % and 1.4 %)
   * and leaves grey and warm patches exactly alone.
   */
  @Test
  public void fxBlueDeepensBluesInTheEngine() throws Exception {
    for (FujiRecipe.Development d : FujiRecipe.Development.values()) {
      FujiRecipe superia = developedAs(FujiRecipe.REGGIES_SUPERIA, d, -2, -4);
      int[] off = developGolden(withFxBlue(superia, 0), false, null);
      int[] strong = developGolden(superia, false, null);
      double deepest = 0;
      for (int p = 0; p < 24; p++) {
        int x0 = (p % 6) * 32 + 8, y0 = (p / 6) * 32 + 8;
        double a = patchLuma(off, x0, y0), b = patchLuma(strong, x0, y0);
        double hue = 360.0 * p / 24;
        if (p % 4 == 3 || hue < 120) assertEquals(d + " patch " + p + " (grey or warm)", a, b, 0.01);
        deepest = Math.max(deepest, 1 - b / a);
      }
      assertTrue(d + ": FX Blue Strong deepens the bluest patch by " + deepest, deepest > 0.045);
    }
  }

  static double patchLuma(int[] img, int x0, int y0) {
    double sum = 0;
    for (int y = y0; y < y0 + 16; y++)
      for (int x = x0; x < x0 + 16; x++) {
        int p = img[y * 192 + x];
        sum += 0.2126 * ((p >> 16) & 255) + 0.7152 * ((p >> 8) & 255) + 0.0722 * (p & 255);
      }
    return sum / 256;
  }

  /** First version: no sharpening and the full grain at the defaults; NR and Sharpness still work. */
  @Test
  public void firstVersionControlsWork() throws Exception {
    java.util.List<Map<String, Object>> st = new java.util.ArrayList<>();
    FujiRecipe reggie = FujiRecipe.REGGIES_PORTRA;
    developGolden(reggie, true, st);
    Map<String, Object> first = st.get(0);
    assertTrue(String.valueOf(first.get("development")), String.valueOf(first.get("development")).startsWith("First version"));
    assertEquals(0.0, ((Number) first.get("sharpenAmount")).doubleValue(), 0);
    assertEquals(3, first.get("chromaNoiseRadius")); // ISO 2742, High ISO NR -4: the v0.2.0 rule
    assertEquals(
        ((Number) first.get("grainAmplitudeRecipe")).doubleValue(),
        ((Number) first.get("grainAmplitude")).doubleValue(),
        1e-6);
    developGolden(developedAs(reggie, FujiRecipe.Development.CLEAN, -2, -4), true, st);
    assertTrue("Clean tops grain up only", ((Number) st.get(0).get("grainAmplitude")).doubleValue() < 0.012);

    java.util.Set<Long> seen = new java.util.HashSet<>();
    double[] noise = new double[9];
    for (int nr = -4; nr <= 4; nr++) {
      int[] img = developGolden(developedAs(reggie, FujiRecipe.Development.FIRST_VERSION, -2, nr), false, null);
      assertTrue("High ISO NR " + nr + " changes the picture", seen.add(fnv(img)));
      noise[nr + 4] = patchNoise(img);
      if (nr > -4) assertTrue("NR " + nr + ": " + noise[nr + 4] + " vs " + noise[nr + 3], noise[nr + 4] < noise[nr + 3]);
    }
    assertTrue("NR +4 cleans " + noise[8] + " vs " + noise[0], noise[8] < 0.6 * noise[0]);
    seen.clear();
    double[] detail = new double[9];
    for (int sh = -4; sh <= 4; sh++) {
      int[] img = developGolden(developedAs(reggie, FujiRecipe.Development.FIRST_VERSION, sh, -4), false, null);
      assertTrue("Sharpness " + sh + " changes the picture", seen.add(fnv(img)));
      detail[sh + 4] = patchNoise(img);
      if (sh > -4) assertTrue("Sharpness " + sh + ": " + detail[sh + 4] + " vs " + detail[sh + 3], detail[sh + 4] > detail[sh + 3]);
    }
  }

  /**
   * Reggie's Portra and Reggie's Superia start on the First version, every other film on Clean;
   * tuning keeps it.
   */
  @Test
  public void developmentDefaultsAndTuning() {
    FujiRecipe reggie = FujiRecipe.REGGIES_PORTRA;
    assertEquals(FujiRecipe.Development.FIRST_VERSION, reggie.development);
    for (FujiRecipe r : FujiRecipe.PUBLISHED)
      assertEquals(
          r.key,
          r == reggie || r == FujiRecipe.REGGIES_SUPERIA
              ? FujiRecipe.Development.FIRST_VERSION
              : FujiRecipe.Development.CLEAN,
          r.development);
    java.util.Set<String> keys = new java.util.HashSet<>();
    for (FujiRecipe r : FujiRecipe.PUBLISHED) assertTrue("unique key " + r.key, keys.add(r.key));
    assertEquals(FujiRecipe.Development.CLEAN, FujiRecipe.standard("PROVIA", "Provia").development);
    String shown = null;
    for (String[] row : reggie.settings()) if (row[0].equals("Development (Grain)")) shown = row[1];
    assertEquals("First version (as Grain v0.2.0)", shown);
    dev.grain.photo.develop.TuningControl ctl = dev.grain.photo.develop.TuningControl.extras().get(0);
    assertEquals("Development", ctl.name);
    assertArrayEquals(new String[] {"Clean", "First version"}, ctl.labels);
    assertEquals(1, ctl.at(reggie.tuning()));
    FujiRecipe.Tuning t = reggie.tuning();
    ctl.set(t, 0);
    FujiRecipe clean = reggie.with(t);
    assertEquals(FujiRecipe.Development.CLEAN, clean.development);
    assertFalse(clean.sameSettings(reggie));
    assertEquals("Clean", ctl.labels[ctl.at(clean.tuning())]);
    t.development = null;
    assertEquals(FujiRecipe.Development.CLEAN, reggie.with(t).development);
    FujiRecipe.Tuning back = clean.tuning();
    back.development = FujiRecipe.Development.FIRST_VERSION;
    assertTrue(clean.with(back).sameSettings(reggie));
  }


  // --- face exposure (v0.3.4)

  /**
   * A backlit portrait: a bright neutral room (the window and walls) with a darker, skin-coloured
   * face in the centre that has a darker beard band and eyes. {@code faceStops} is the skin's level
   * relative to the room, in EV.
   */
  static RawFrame backlitPortrait(double faceStops, int[] faces, int[] scores) {
    int w = 256, h = 192;
    double[] n = {0.4812030075, 1, 0.5707915273}, skin = {1.45, 1, 0.78};
    short[] data = new short[w * h];
    RawFrame f = new RawFrame(data, w, h, w);
    f.cfa = RawFrame.GRBG;
    Random rnd = new Random(42);
    double room = 0.45, face = room * Math.pow(2, faceStops);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int c = f.color(x, y);
        double v = room * n[c];
        if (x >= 96 && x < 160 && y >= 56 && y < 136) {
          double level = face;
          if (y >= 112) level *= 0.25; // beard
          if (y >= 76 && y < 84 && (x >= 108 && x < 120 || x >= 136 && x < 148)) level *= 0.2; // eyes
          v = level * skin[c] * n[c];
        }
        v += rnd.nextGaussian() * Math.sqrt(4e-4 * Math.max(0, v) + 1e-6);
        data[y * w + x] = (short) Math.max(0, Math.min(1023, Math.round(64 + v * (1023 - 64))));
      }
    f.neutral = n;
    f.colorMatrix1 = PHONE_CM1;
    f.colorMatrix2 = PHONE_CM2;
    f.forwardMatrix1 = PHONE_FM1;
    f.forwardMatrix2 = PHONE_FM2;
    f.illuminant1 = 21;
    f.illuminant2 = 17;
    f.iso = 400;
    f.faces = faces;
    f.faceScores = scores;
    f.faceSource = faces == null ? "" : "test";
    return f;
  }

  static final int[] PORTRAIT_FACE = {96, 56, 160, 136};

  static int[] developPortrait(RawFrame f, boolean faceExposure, double ev, Map<String, Object> stats)
      throws Exception {
    RawDeveloper.Options o = new RawDeveloper.Options();
    o.grain = false;
    o.threads = 2;
    o.exposureEv = ev;
    o.faceExposure = faceExposure;
    int[] out = new int[f.width * f.height];
    Map<String, Object> s = RawDeveloper.develop(f, FujiRecipe.REGGIES_PORTRA, goldenLut(), o, (y, rows, argb, width) ->
        System.arraycopy(argb, 0, out, y * width, rows * width));
    if (stats != null) stats.putAll(s);
    return out;
  }

  /** Mean output luma code (0..255) of the face's cheeks and forehead. */
  static double skinLuma(int[] img) {
    double sum = 0;
    int n = 0;
    for (int y = 62; y < 108; y++)
      for (int x = 104; x < 152; x++) {
        if (y >= 74 && y < 86) continue; // eyes
        int p = img[y * 256 + x];
        sum += 0.2126 * ((p >> 16) & 255) + 0.7152 * ((p >> 8) & 255) + 0.0722 * (p & 255);
        n++;
      }
    return sum / n;
  }

  static double num(Map<String, Object> s, String key) {
    return ((Number) s.get(key)).doubleValue();
  }

  @Test
  public void faceExposureMetersTheFaceOfABacklitPortrait() throws Exception {
    Map<String, Object> off = new java.util.HashMap<>(), on = new java.util.HashMap<>();
    int[] dark = developPortrait(backlitPortrait(-3, PORTRAIT_FACE, new int[] {90}), false, 0, off);
    int[] lit = developPortrait(backlitPortrait(-3, PORTRAIT_FACE, new int[] {90}), true, 0, on);
    assertEquals("Off", off.get("faceExposure"));
    assertEquals("On: face metered", on.get("faceExposure"));
    assertArrayEquals(PORTRAIT_FACE, (int[]) on.get("faceRect"));
    assertEquals("test", on.get("faceSource"));
    assertEquals(90, ((Number) on.get("faceScore")).intValue());
    double ev = num(on, "faceExposureEv");
    // the correction is exactly what puts the face at FACE_KEY relative to the scene metering
    double wanted = Math.log((RawDeveloper.FACE_KEY / num(on, "faceMeter"))
        / (RawDeveloper.METERING_KEY / num(on, "meterLogAverage"))) / Math.log(2);
    assertEquals(wanted, ev, 2e-3);
    assertTrue("a backlit face is brightened a lot: " + ev, ev > 1.5 && ev < RawDeveloper.FACE_MAX_BRIGHTEN_EV);
    assertNull(on.get("faceExposureWanted"));
    assertEquals(num(off, "developmentGainEv") + ev, num(on, "developmentGainEv"), 2e-3);
    // after the gain, the face's metered level sits at the face key
    assertEquals(RawDeveloper.FACE_KEY, num(on, "faceMeter") * num(on, "developmentGain"), RawDeveloper.FACE_KEY * 0.01);
    double before = skinLuma(dark), after = skinLuma(lit);
    assertTrue("skin " + before + " -> " + after, after > before + 45 && after > 1.8 * before);
    // like a camera metering the face: the skin comes out the same however strong the backlight
    for (double stops : new double[] {-1, -2}) {
      double other = skinLuma(developPortrait(backlitPortrait(stops, PORTRAIT_FACE, null), true, 0, null));
      assertEquals("skin at " + stops + " EV", after, other, 3);
    }
    // the beard and eyes do not drive the metering: the skin's own level is measured
    Map<String, Object> clean = new java.util.HashMap<>();
    RawFrame f = backlitPortrait(-3, new int[] {104, 62, 152, 108}, null);
    developPortrait(f, true, 0, clean);
    double skinOnly = num(clean, "faceMeter"), withBeard = num(on, "faceMeter");
    assertEquals("percentile ignores beard and eyes", 0, Math.log(withBeard / skinOnly) / Math.log(2), 0.25);
    // the user's EV is applied on top of the face exposure
    Map<String, Object> plus = new java.util.HashMap<>();
    developPortrait(backlitPortrait(-3, PORTRAIT_FACE, new int[] {90}), true, 1, plus);
    assertEquals(num(on, "developmentGainEv") + 1, num(plus, "developmentGainEv"), 2e-3);
  }

  @Test
  public void faceExposureOffOrWithoutAUsableFaceLeavesThePictureUnchanged() throws Exception {
    Map<String, Object> none = new java.util.HashMap<>();
    long base = fnv(developPortrait(backlitPortrait(-3, null, null), true, 0, none));
    assertEquals("On: no face found", none.get("faceExposure"));
    assertNull(none.get("faceExposureEv"));
    assertEquals(base, fnv(developPortrait(backlitPortrait(-3, PORTRAIT_FACE, new int[] {90}), false, 0, null)));
    assertEquals(base, fnv(developPortrait(backlitPortrait(-3, new int[0], new int[0]), true, 0, null)));
    Map<String, Object> weak = new java.util.HashMap<>();
    assertEquals(base, fnv(developPortrait(backlitPortrait(-3, PORTRAIT_FACE, new int[] {30}), true, 0, weak)));
    assertEquals("On: no usable face (1 too small or low confidence)", weak.get("faceExposure"));
    int[] tiny = {120, 90, 124, 94}; // 4 px: below 2.5 % of the short side
    assertEquals(base, fnv(developPortrait(backlitPortrait(-3, tiny, null), true, 0, null)));
    // the golden frames (no faces) keep their v0.3.3 pictures
    assertEquals(0x8f487b5241881196L, fnv(developGolden(FujiRecipe.REGGIES_PORTRA, true, null)));
  }

  @Test
  public void faceExposureIsLimited() throws Exception {
    Map<String, Object> black = new java.util.HashMap<>();
    developPortrait(backlitPortrait(-9, PORTRAIT_FACE, null), true, 0, black);
    assertEquals(RawDeveloper.FACE_MAX_BRIGHTEN_EV, num(black, "faceExposureEv"), 1e-9);
    assertTrue(num(black, "faceExposureWanted") > RawDeveloper.FACE_MAX_BRIGHTEN_EV);
    // a face much brighter than its surroundings is darkened, within the limit
    Map<String, Object> spot = new java.util.HashMap<>();
    RawFrame f = backlitPortrait(0.9, PORTRAIT_FACE, null);
    for (int y = 0; y < f.height; y++) // dark surroundings
      for (int x = 0; x < f.width; x++)
        if (x < 80 || x >= 176 || y < 40 || y >= 152)
          f.data[y * f.width + x] = (short) (64 + ((f.data[y * f.width + x] - 64) >> 5));
    developPortrait(f, true, 0, spot);
    double ev = num(spot, "faceExposureEv");
    assertTrue("spot-lit face darkened: " + ev, ev < -0.5 && ev >= -RawDeveloper.FACE_MAX_DARKEN_EV);
  }

  @Test
  public void facesPickTheConfidentFaceClosestToTheCentre() {
    int w = 4080, h = 3060;
    int[] faces = {
      100, 100, 500, 560, // big, corner
      1900, 1300, 2200, 1700, // centre, confident
      1990, 1480, 2090, 1580, // exactly centred, only 100 px: 3.3 % of 3060 is still usable
      1950, 1350, 2150, 1650 // centre, low confidence
    };
    assertEquals(1, dev.grain.photo.develop.Faces.pick(faces, new int[] {99, 80, 20, 40}, w, h));
    assertEquals(2, dev.grain.photo.develop.Faces.pick(faces, new int[] {99, 80, 80, 40}, w, h));
    assertEquals(0, dev.grain.photo.develop.Faces.pick(faces, new int[] {99, 10, 10, 10}, w, h));
    assertEquals(-1, dev.grain.photo.develop.Faces.pick(faces, new int[] {1, 1, 1, 1}, w, h));
    assertEquals(-1, dev.grain.photo.develop.Faces.pick(new int[] {10, 10, 60, 60}, null, w, h)); // too small
    assertEquals(-1, dev.grain.photo.develop.Faces.pick(null, null, w, h));
  }

  @Test
  public void faceRectanglesMapToRawPixels() {
    // Camera2 rectangles: active-array coordinates -> RAW buffer
    assertArrayEquals(new int[] {1008, 516, 1408, 1016},
        dev.grain.photo.develop.Faces.activeToRaw(1000, 500, 1400, 1000, 4064, 3044, 8, 16, false, 4080, 3060));
    assertArrayEquals(new int[] {500, 250, 700, 500},
        dev.grain.photo.develop.Faces.activeToRaw(1000, 500, 1400, 1000, 8160, 6120, 0, 0, true, 4080, 3060));
    assertNull(dev.grain.photo.develop.Faces.activeToRaw(5000, 4000, 5200, 4200, 4080, 3060, 0, 0, false, 4080, 3060));
    // a face found in the upright, quarter-size reference JPEG -> RAW, for every orientation
    int sw = 1020, sh = 765; // the sensor image at 1/4
    int[] raw = {1000, 500, 1400, 1000};
    for (int degrees : new int[] {0, 90, 180, 270}) {
      double[] a = rotate(raw[0] / 4.0, raw[1] / 4.0, degrees, sw, sh);
      double[] b = rotate(raw[2] / 4.0, raw[3] / 4.0, degrees, sw, sh);
      int[] back = dev.grain.photo.develop.Faces.uprightToRaw(
          Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]),
          degrees, sw, sh, 4080, 3060);
      assertArrayEquals("rotation " + degrees, raw, back);
    }
    double[] box = dev.grain.photo.develop.Faces.boxFromEyes(500, 400, 100);
    assertArrayEquals(new double[] {390, 300, 610, 540}, box, 1e-9);
  }

  /** Clockwise rotation of a point of a w x h image by degrees (continuous coordinates). */
  static double[] rotate(double x, double y, int degrees, int w, int h) {
    switch (degrees) {
      case 90:
        return new double[] {h - y, x};
      case 180:
        return new double[] {w - x, h - y};
      case 270:
        return new double[] {y, w - x};
      default:
        return new double[] {x, y};
    }
  }
  // ---------------------------------------------------------------- v0.3.5: Sony s709

  /** Sony's S-Log3 (Technical Summary): black 95, 18 % grey 420, 90 % white 598 of 1023. */
  @Test
  public void slog3MatchesSonysTechnicalSummary() {
    assertEquals(95.0 / 1023, SLog3.encode(0), 1e-12);
    assertEquals(420.0 / 1023, SLog3.encode(0.18), 1e-12);
    assertEquals(SLog3.GREY_CODE, SLog3.encode(0.18), 1e-12);
    assertEquals(598.0 / 1023, SLog3.encode(0.90), 0.5 / 1023);
    assertEquals(SLog3.encode(0.01125 - 1e-12), SLog3.encode(0.01125), 1e-9); // continuous at the cut
    for (double x = -0.01; x < 40; x = x < 0.02 ? x + 0.0005 : x * 1.07)
      assertEquals("round trip " + x, x, SLog3.decode(SLog3.encode(x)), 1e-9 * Math.max(1, x));
  }

  /** S-Gamut3.Cine built from Sony's primaries equals Sony's published matrix; white stays white. */
  @Test
  public void sGamut3CineMatchesSonysPublishedMatrix() {
    double[] sony = {
      0.5990839208, 0.2489255161, 0.1024464902,
      0.2150758201, 0.8850685017, -0.1001443219,
      -0.0320658495, -0.0276583907, 1.1487819910
    };
    for (int i = 0; i < 9; i++) assertEquals("SGamut3.Cine to XYZ " + i, sony[i], SLog3.SGAMUT3_CINE_TO_XYZ[i], 1e-9);
    for (double[] m : new double[][] {SLog3.REC2020_TO_SGAMUT3_CINE, SLog3.REC2020_TO_SGAMUT3})
      for (int r = 0; r < 3; r++) assertEquals("neutral row " + r, 1, m[3 * r] + m[3 * r + 1] + m[3 * r + 2], 1e-9);
    // BT.2020 red is inside S-Gamut3.Cine; BT.2020 green is outside it (negative red)
    assertTrue(SLog3.REC2020_TO_SGAMUT3_CINE[0] > 0 && SLog3.REC2020_TO_SGAMUT3_CINE[3] > 0);
    assertTrue(SLog3.REC2020_TO_SGAMUT3_CINE[1] < 0);
  }

  /**
   * A Sony table converts each F-Log2 / F-Gamut sample exactly to S-Log3 / S-Gamut3.Cine before
   * the lookup: through an identity table the output is the S-Log3 code of the converted colour.
   */
  @Test
  public void sonyLutSamplesThroughAnExactSLog3Conversion() throws Exception {
    CubeLut plain = identity(33);
    CubeLut sony = plain.withInput(CubeLut.Input.SLOG3_SGAMUT3_CINE);
    assertEquals(CubeLut.Input.FLOG2_FGAMUT, plain.input);
    assertSame(plain.data, sony.data);
    assertSame(sony, sony.withInput(CubeLut.Input.SLOG3_SGAMUT3_CINE));
    double[] m = SLog3.REC2020_TO_SGAMUT3_CINE;
    Random rnd = new Random(35);
    float[] out = new float[3];
    double worst = 0;
    for (int i = 0; i < 20000; i++) {
      double y = 0.18 * Math.pow(2, rnd.nextDouble() * 13 - 7);
      double[] lin = {y * (0.3 + rnd.nextDouble()), y * (0.3 + rnd.nextDouble()), y * (0.3 + rnd.nextDouble())};
      sony.sample(
          (float) FLog2.encode(lin[0]), (float) FLog2.encode(lin[1]), (float) FLog2.encode(lin[2]), out);
      double[] sg = ColorMath.mul(m, lin, true);
      for (int c = 0; c < 3; c++) {
        double want = Math.max(0, Math.min(1, SLog3.encode(sg[c])));
        worst = Math.max(worst, Math.abs(out[c] - want));
      }
    }
    assertTrue("worst S-Log3 code error " + worst, worst < 2e-5);
    // F-Log2 tables are untouched by the conversion
    plain.sample(0.3f, 0.5f, 0.7f, out);
    assertArrayEquals(new float[] {0.3f, 0.5f, 0.7f}, out, 1e-6f);
    // Sony's s709 file names; not the P3 files or Fujifilm's LUTs
    java.util.regex.Matcher cine = CubeLut.SONY_S709.matcher("s709Cubes/SL3SG3Ctos709.cube");
    assertTrue(cine.find());
    assertEquals("C", cine.group(1));
    java.util.regex.Matcher sg3 = CubeLut.SONY_S709.matcher("SL3SG3tos709.cube");
    assertTrue(sg3.find());
    assertEquals("", sg3.group(1));
    assertFalse(CubeLut.SONY_S709.matcher("sP3Cubes/SL3SG3CtosP3D65.cube").find());
    assertFalse(CubeLut.SONY_S709.matcher("FLog2_to_PROVIA_33grid_V.1.00.cube").find());
    assertFalse(CubeLut.FUJI_FLOG2_33.matcher("s709Cubes/SL3SG3Ctos709.cube").find());
  }

  static RawFrame flatNeutral(int w, int h, double level) {
    double[] n = {0.5, 1, 0.625};
    short[] data = new short[w * h];
    RawFrame f = new RawFrame(data, w, h, w);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int c = f.color(x, y);
        double v = level * (c == 0 ? n[0] : c == 2 ? n[2] : 1);
        data[y * w + x] = (short) Math.round(64 + v * (1023 - 64));
      }
    f.neutral = n;
    f.colorMatrix1 = CM;
    f.iso = 100;
    return f;
  }

  static double meanGreen(int[] img) {
    double s = 0;
    for (int p : img) s += (p >> 8) & 255;
    return s / img.length;
  }

  /**
   * Sony's s709 develops through its own S-Log3 table, metered to Sony's 18 % grey, without the
   * Fujifilm stills offsets or dynamic range; a Sony film refuses a Fujifilm table and vice versa.
   */
  @Test
  public void sonyS709DevelopsAtSonysGreyWithoutFujifilmTone() throws Exception {
    RawFrame f = flatNeutral(256, 192, 0.10);
    CubeLut sony = identity(33).withInput(CubeLut.Input.SLOG3_SGAMUT3_CINE);
    RawDeveloper.Options o = new RawDeveloper.Options();
    o.grain = false;
    o.threads = 2;
    int[] out = new int[f.width * f.height];
    Map<String, Object> stats =
        RawDeveloper.develop(f, FujiRecipe.SONY_S709, sony, o, (y, rows, argb, width) ->
            System.arraycopy(argb, 0, out, y * width, rows * width));
    // a flat field is metered to the key: through an identity table it shows S-Log3's grey code
    assertEquals(SLog3.GREY_CODE * 255, meanGreen(out), 0.6);
    assertEquals(RawDeveloper.SONY_KEY, (Double) stats.get("meterKey"), 0);
    assertEquals("S-Log3 / S-Gamut3.Cine", stats.get("lutInput"));
    assertTrue(String.valueOf(stats.get("dynamicRange")).startsWith("Not used"));
    // the shoulder still maps the RAW clip point to the table's white
    assertTrue((Double) stats.get("shoulderScale") > 1);
    // the Sony curve is the LUT's own tone plus the shoulder: no Fujifilm offsets below the knee
    ToneModel.Curve sonyCurve = ToneModel.curve(0, 0, 0, true, 1, false);
    ToneModel.Curve fujiCurve = ToneModel.curve(0, 0, 0, true, 1);
    for (double ev = -8; ev <= 6; ev += 0.25) {
      double x = FLog2.encode(0.18 * Math.pow(2, ev));
      assertEquals("Sony curve at " + ev, x, sonyCurve.apply(x), 1e-12);
    }
    assertNotEquals(FLog2.encode(0.18 * 16), fujiCurve.apply(FLog2.encode(0.18 * 16)), 1e-3);
    assertTrue(fujiCurve.fujifilm && !sonyCurve.fujifilm);
    // tables and films must match
    FujiRecipe plain =
        new FujiRecipe("plain", "Plain", "", "", "LUT", "LUT", FujiRecipe.DynamicRange.DR100,
            0, 0, 0, 0, -4, 0, 0, false, 0, 0, "As shot", 0, 0);
    try {
      RawDeveloper.develop(f, FujiRecipe.SONY_S709, identity(33), o, (y, rows, argb, width) -> {});
      fail("Sony film with an F-Log2 table");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("S-Log3"));
    }
    try {
      RawDeveloper.develop(f, plain, sony, o, (y, rows, argb, width) -> {});
      fail("Fujifilm film with a Sony table");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("F-Log2"));
    }
  }

  /** Sony's s709: Fujifilm-only settings are not offered and stay off whatever the tuning holds. */
  @Test
  public void sonyS709HasNoFujifilmOnlySettings() {
    FujiRecipe s = FujiRecipe.SONY_S709;
    assertTrue(s.sony());
    assertFalse(FujiRecipe.REGGIES_PORTRA.sony());
    assertFalse(FujiRecipe.isTechnical(s.filmSimulation));
    assertTrue(FujiRecipe.isTechnical("FLog2-709"));
    assertTrue(FujiRecipe.isTechnical("WDR-709"));
    assertEquals(FujiRecipe.DynamicRange.DR100, s.dynamicRange);
    FujiRecipe.Tuning t = s.tuning();
    t.dynamicRange = FujiRecipe.DynamicRange.DRP_STRONG;
    t.colorChrome = 2;
    t.colorChromeBlue = 2;
    t.color = 3;
    t.highlight = 1;
    FujiRecipe tuned = s.with(t);
    assertEquals(FujiRecipe.DynamicRange.DR100, tuned.dynamicRange);
    assertEquals(0, tuned.colorChrome);
    assertEquals(0, tuned.colorChromeBlue);
    assertEquals(3, tuned.color);
    assertEquals(1.0, tuned.highlight, 0);
    java.util.Set<String> names = new java.util.HashSet<>();
    for (dev.grain.photo.develop.TuningControl c : dev.grain.photo.develop.TuningControl.fujifilm(s)) {
      names.add(c.name);
      // every control offered for Sony works: it survives with()
      for (int p = 0; p < c.labels.length; p++) {
        FujiRecipe.Tuning u = s.tuning();
        c.set(u, p);
        assertEquals(c.name + " position " + p, p, c.at(s.with(u).tuning()));
      }
      // and the standard settings are all 0 / Off / Auto
      if (c.enabled(s.tuning())) {
        String v = c.labels[c.at(s.tuning())];
        assertTrue(c.name + " standard is " + v, v.equals("0") || v.equals("Off") || v.equals("Auto"));
      }
    }
    for (String fujiOnly : new String[] {"Dynamic range", "Color Chrome Effect", "Color Chrome FX Blue", "Filter"})
      assertFalse(fujiOnly + " offered for Sony", names.contains(fujiOnly));
    for (String works : new String[] {"White balance", "WB shift red", "Highlight", "Shadow", "Color", "Sharpness",
        "High ISO NR", "Clarity", "Grain"})
      assertTrue(works + " missing for Sony", names.contains(works));
    String[][] rows = s.settings();
    assertEquals("Look", rows[0][0]);
    for (String[] row : rows) {
      assertNotEquals("Dynamic range", row[0]);
      assertNotEquals("Color Chrome Effect", row[0]);
      assertNotEquals("Color Chrome FX Blue", row[0]);
    }
    assertEquals(FujiRecipe.Development.CLEAN, s.development);
    assertEquals(0, s.grainRoughness);
  }

  @Test
  public void kodak800v3AndLeicaXCalibratePhoneSensorColorHighlightShadowAndTone() throws Exception {
    // 1) Monotonicity and true negative-Highlight roll-off + Portra vs Leica shadow toe
    ToneModel.Curve k800 =
        ToneModel.calibratedCurve(2, -2.0, -0.5, true, 1.0, FujiRecipe.KODAK_800_V3.key);
    ToneModel.Curve k800HardHi =
        ToneModel.calibratedCurve(2, 0.0, -0.5, true, 1.0, FujiRecipe.KODAK_800_V3.key);
    ToneModel.Curve leica =
        ToneModel.calibratedCurve(1, -1.0, 0.0, true, 1.0, FujiRecipe.LEICA_X.key);
    ToneModel.Curve leicaHardHi =
        ToneModel.calibratedCurve(1, 0.0, 0.0, true, 1.0, FujiRecipe.LEICA_X.key);

    double prevK = -1, prevL = -1;
    for (double ev = -8.0; ev <= 8.0; ev += 0.125) {
      double x = FLog2.encode(0.18 * Math.pow(2, ev));
      double ck = k800.apply(x), cl = leica.apply(x);
      assertTrue("Kodak 800 v3 curve strictly monotonic at EV " + ev, ck > prevK);
      assertTrue("Leica X curve strictly monotonic at EV " + ev, cl > prevL);
      prevK = ck;
      prevL = cl;
    }
    double hiCode = FLog2.encode(0.18 * Math.pow(2, 2.2));
    assertTrue(
        "Highlight -2 softens highlights relative to Highlight 0 on Kodak 800 v3",
        k800.apply(hiCode) < k800HardHi.apply(hiCode) - 0.02);
    assertTrue(
        "Highlight -1 softens highlights relative to Highlight 0 on Leica X",
        leica.apply(hiCode) < leicaHardHi.apply(hiCode) - 0.01);

    double shCode = FLog2.encode(0.18 * Math.pow(2, -2.0));
    assertTrue(
        "Kodak 800 v3 (Shadow -0.5) renders more open shadows than Leica X (grounded black toe)",
        k800.apply(shCode) > leica.apply(shCode) + 0.012);

    // 2) Full phone-sensor RAW development on goldenFrame()
    FujiRecipe plainCc = FujiRecipe.standard("CLASSIC-CHROME", "Classic Chrome");
    java.util.List<Map<String, Object>> stPlain = new java.util.ArrayList<>();
    java.util.List<Map<String, Object>> stKodak = new java.util.ArrayList<>();
    java.util.List<Map<String, Object>> stLeica = new java.util.ArrayList<>();

    int[] pxPlain = developGolden(plainCc, false, stPlain);
    int[] pxKodak = developGolden(FujiRecipe.KODAK_800_V3, true, stKodak);
    int[] pxLeica = developGolden(FujiRecipe.LEICA_X, true, stLeica);

    Map<String, Object> mk = stKodak.get(0), ml = stLeica.get(0);
    assertTrue(String.valueOf(mk.get("sensorCalibration")).contains("Kodak Portra 800 v3"));
    assertTrue(
        "Kodak 800 v3 maps Fuji 6600K to RAF-measured ~5693K DNG Planckian (below 6000K Shade)",
        String.valueOf(mk.get("whiteBalance")).contains("5693 K"));
    assertTrue(String.valueOf(ml.get("sensorCalibration")).contains("Leica X"));
    assertTrue(
        "Kodak 800 v3 applies gentle emulsion highlight micro-bloom",
        ((Number) mk.get("bloomShare")).doubleValue() > 0.03);
    assertTrue(
        "Kodak 800 v3 renders Strong / Large grain",
        ((Number) mk.get("grainAmplitude")).doubleValue() > 0.01);
    assertEquals(
        "Leica X keeps Grain Off",
        0.0,
        ((Number) ml.get("grainAmplitude")).doubleValue(),
        1e-6);
    assertTrue(
        "Leica X FX Blue Strong exceeds Kodak 800 v3 FX Blue Weak",
        ((Number) ml.get("colorChromeBlueDepth")).doubleValue()
            > ((Number) mk.get("colorChromeBlueDepth")).doubleValue());

    // Measure average chromatic richness on saturated patches vs plain Classic Chrome
    double satPlain = meanSaturation(pxPlain), satKodak = meanSaturation(pxKodak), satLeica = meanSaturation(pxLeica);
    assertTrue("Kodak 800 v3 boosts perceptual colour richness above plain Classic Chrome", satKodak > satPlain * 1.08);
    assertTrue("Leica X boosts perceptual colour richness above plain Classic Chrome", satLeica > satPlain * 1.12);
    assertNotEquals(fnv(pxKodak), fnv(pxLeica));

    // 3) Film-sheet LutPreview differentiates Kodak 800 v3, Leica X and plain Classic Chrome
    CubeLut lut = goldenLut();
    int[] prevBase = {0xffb84842, 0xffb88f78, 0xff759942, 0xff528acc, 0xffeaeaea};
    int[] tileK = prevBase.clone(), tileL = prevBase.clone(), tileC = prevBase.clone();
    dev.grain.photo.develop.LutPreview.apply(tileC, lut);
    dev.grain.photo.develop.LutPreview.apply(tileK, lut, FujiRecipe.KODAK_800_V3);
    dev.grain.photo.develop.LutPreview.apply(tileL, lut, FujiRecipe.LEICA_X);
    assertNotEquals(fnv(tileC), fnv(tileK));
    assertNotEquals(fnv(tileC), fnv(tileL));
    assertNotEquals(fnv(tileK), fnv(tileL));
  }

  @Test
  public void cinestill800tDeliversTungstenTealSplitVision3ColorAndRedHalation() throws Exception {
    ToneModel.Curve cs =
        ToneModel.calibratedCurve(2, 0.0, 2.0, true, 1.0, FujiRecipe.CINESTILL_800T.key);
    double prev = -1;
    for (double ev = -8.0; ev <= 8.0; ev += 0.125) {
      double c = cs.apply(FLog2.encode(0.18 * Math.pow(2, ev)));
      assertTrue("CineStill 800T curve strictly monotonic at EV " + ev, c > prev);
      prev = c;
    }

    FujiRecipe plainEterna = FujiRecipe.standard("ETERNA", "Eterna");
    java.util.List<Map<String, Object>> stEterna = new java.util.ArrayList<>();
    java.util.List<Map<String, Object>> stCine = new java.util.ArrayList<>();
    int[] pxEterna = developGolden(plainEterna, false, stEterna);
    int[] pxCine = developGolden(FujiRecipe.CINESTILL_800T, true, stCine);
    Map<String, Object> mc = stCine.get(0);

    assertTrue(String.valueOf(mc.get("sensorCalibration")).contains("CineStill 800T"));
    assertTrue(
        "CineStill 800T develops at true 3450K tungsten white point",
        String.valueOf(mc.get("whiteBalance")).contains("3450 K"));
    assertTrue(
        "CineStill 800T enables remjet-free red-orange halation",
        ((Number) mc.get("halationRed")).doubleValue() >= 0.085);
    assertTrue(
        "CineStill 800T boosts Vision3 colour richness well above flat Eterna",
        meanSaturation(pxCine) > meanSaturation(pxEterna) * 1.15);

    // Night point-light scene: dark neutral background with a bright unclipped lamp in the centre
    int w = 160, h = 160;
    short[] data = new short[w * h];
    RawFrame night = new RawFrame(data, w, h, w);
    night.cfa = RawFrame.GRBG;
    double[] n = {0.55, 1.0, 0.58}; // ~3400K tungsten scene neutral
    night.neutral = n;
    night.colorMatrix1 = PHONE_CM1;
    night.colorMatrix2 = PHONE_CM2;
    night.forwardMatrix1 = PHONE_FM1;
    night.forwardMatrix2 = PHONE_FM2;
    night.illuminant1 = 21;
    night.illuminant2 = 17;
    night.iso = 800;
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int c = night.color(x, y);
        boolean lamp = Math.hypot(x - 80, y - 80) <= 6.0;
        double level = lamp ? 0.78 : 0.035; // bright unclipped lamp vs dark night street
        data[y * w + x] = (short) Math.round(64 + Math.min(1.0, level * n[c]) * (1023 - 64));
      }
    RawDeveloper.Options opt = new RawDeveloper.Options();
    opt.grain = false;
    opt.fixedGain = 1.6;
    int[] outCine = new int[w * h], outEterna = new int[w * h];
    RawDeveloper.develop(
        night,
        FujiRecipe.CINESTILL_800T,
        goldenLut(),
        opt,
        (y, rows, argb, width) -> System.arraycopy(argb, 0, outCine, y * width, rows * width));
    RawDeveloper.develop(
        night,
        plainEterna,
        goldenLut(),
        opt,
        (y, rows, argb, width) -> System.arraycopy(argb, 0, outEterna, y * width, rows * width));

    // Ring just outside the lamp (radius 8..13 px) vs far dark background (radius > 48 px):
    // CineStill 800T's dark background leans cyan-teal (bgB > bgR), while the ring around the
    // bright lamp glows with remjet-free red-orange halation (ringR rises strongly over ringB).
    double ringR = 0, ringB = 0, bgR = 0, bgB = 0;
    int ringCnt = 0, bgCnt = 0;
    for (int y = 16; y < h - 16; y++)
      for (int x = 16; x < w - 16; x++) {
        double d = Math.hypot(x - 80, y - 80);
        int pc = outCine[y * w + x];
        if (d >= 8.0 && d <= 13.0) {
          ringR += (pc >> 16) & 255;
          ringB += pc & 255;
          ringCnt++;
        } else if (d >= 48.0) {
          bgR += (pc >> 16) & 255;
          bgB += pc & 255;
          bgCnt++;
        }
      }
    ringR /= ringCnt;
    ringB /= ringCnt;
    bgR /= bgCnt;
    bgB /= bgCnt;
    double haloRedLift = ringR - bgR, haloBlueLift = ringB - bgB;
    assertTrue(
        "Dark night shadows lean cyan-teal (B=" + bgB + " > R=" + bgR + ")",
        bgB > bgR + 2.0);
    assertTrue(
        "Remjet-free red-orange halation glows around bright night lamps (redLift="
            + haloRedLift
            + ", blueLift="
            + haloBlueLift
            + ")",
        haloRedLift > 12.0 && haloRedLift > haloBlueLift + 8.0);
  }

  @Test
  public void bandedHighResDevelopmentMatchesFullFrameWithoutSeams() throws Exception {
    int w = 192, h = 512;
    short[] data = new short[w * h];
    RawFrame frame = new RawFrame(data, w, h, w);
    frame.cfa = RawFrame.RGGB;
    double[] n = {0.58, 1.0, 0.62};
    frame.neutral = n;
    frame.colorMatrix1 = PHONE_CM1;
    frame.colorMatrix2 = PHONE_CM2;
    frame.forwardMatrix1 = PHONE_FM1;
    frame.forwardMatrix2 = PHONE_FM2;
    frame.illuminant1 = 21;
    frame.illuminant2 = 17;
    frame.iso = 400;
    // Include lens-shading gains that vary smoothly vertically so any band row-offset bug shows up.
    frame.shadingColumns = 4;
    frame.shadingRows = 4;
    frame.shadingWidth = w;
    frame.shadingHeight = h;
    frame.shading = new float[4 * 4 * 4];
    for (int ry = 0; ry < 4; ry++)
      for (int rx = 0; rx < 4; rx++)
        for (int c = 0; c < 4; c++)
          frame.shading[(ry * 4 + rx) * 4 + c] = 1.0f + 0.18f * (ry - 1.5f) * (ry - 1.5f);

    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int c = frame.color(x, y);
        boolean lamp = Math.hypot(x - 96, y - 256) <= 7.0;
        double grad = 0.08 + 0.45 * (y / (double) h) + 0.12 * Math.sin(x * 0.09);
        double level = lamp ? 1.0 : grad;
        data[y * w + x] = (short) Math.round(64 + Math.min(1.0, level * n[c]) * (1023 - 64));
      }

    FujiRecipe recipe = extras(FujiRecipe.CINESTILL_800T, 4, 3, 3);
    int[] full = new int[w * h];
    RawDeveloper.Options optFull = new RawDeveloper.Options();
    optFull.grain = true;
    optFull.grainSeed = 424242L;
    optFull.maxBandPixels = Long.MAX_VALUE;
    RawDeveloper.develop(
        frame,
        recipe,
        goldenLut(),
        optFull,
        (y, rows, argb, width) -> System.arraycopy(argb, 0, full, y * width, rows * width));

    int[] banded = new int[w * h];
    RawDeveloper.Options optBand = new RawDeveloper.Options();
    optBand.grain = true;
    optBand.grainSeed = 424242L;
    optBand.maxBandPixels = 36_000L; // forces multiple overlapping horizontal bands
    Map<String, Object> statsBand =
        RawDeveloper.develop(
            frame,
            recipe,
            goldenLut(),
            optBand,
            (y, rows, argb, width) -> System.arraycopy(argb, 0, banded, y * width, rows * width));

    assertTrue("Expected >= 3 bands, got " + statsBand.get("bands"), ((Number) statsBand.get("bands")).intValue() >= 3);
    int maxDiff = 0;
    for (int i = 0; i < w * h; i++) {
      int a = full[i], b = banded[i];
      maxDiff = Math.max(maxDiff, Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)));
      maxDiff = Math.max(maxDiff, Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)));
      maxDiff = Math.max(maxDiff, Math.abs((a & 255) - (b & 255)));
    }
    assertTrue("Banded development differed from full-frame by " + maxDiff + " codes", maxDiff <= 1);
  }

  private static double meanSaturation(int[] argb) {
    double sum = 0;
    int cnt = 0;
    for (int p : argb) {
      int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
      int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
      if (mx > 35 && mx < 245) {
        sum += (mx - mn) / (double) mx;
        cnt++;
      }
    }
    return cnt == 0 ? 0 : sum / cnt;
  }
}
