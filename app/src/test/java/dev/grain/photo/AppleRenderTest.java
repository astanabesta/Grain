package dev.grain.photo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.grain.photo.develop.AppleRender;
import dev.grain.photo.develop.CubeLut;
import dev.grain.photo.develop.FLog2;
import dev.grain.photo.develop.FujiRecipe;
import dev.grain.photo.develop.LutPreview;
import dev.grain.photo.develop.RawDeveloper;
import dev.grain.photo.develop.RawFrame;
import dev.grain.photo.develop.TuningControl;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class AppleRenderTest {
  private static final double[] PHONE_CM1 = {
    0.85, -0.25, -0.05, -0.45, 1.25, 0.22, -0.08, 0.18, 0.72
  };
  private static final double[] PHONE_CM2 = {
    1.15, -0.45, -0.10, -0.55, 1.35, 0.22, -0.15, 0.30, 0.95
  };
  private static final double[] PHONE_FM1 = {
    0.58, 0.28, 0.104, 0.22, 0.72, 0.060, 0.02, 0.08, 0.725
  };
  private static final double[] PHONE_FM2 = {
    0.52, 0.34, 0.104, 0.20, 0.74, 0.060, 0.03, 0.10, 0.695
  };

  @Test
  public void displayP3MatricesPreserveD65NeutralWhiteAndInvertCleanly() {
    for (float[] m :
        new float[][] {
          AppleRender.REC2020_TO_P3, AppleRender.P3_TO_REC709, AppleRender.REC709_TO_P3
        }) {
      for (int row = 0; row < 3; row++) {
        float sum = m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2];
        assertEquals("row " + row + " sum", 1.0f, sum, 1e-6f);
      }
    }
    float[] a = AppleRender.P3_TO_REC709, b = AppleRender.REC709_TO_P3;
    for (int r = 0; r < 3; r++) {
      for (int c = 0; c < 3; c++) {
        float v = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
        assertEquals(r == c ? 1f : 0f, v, 1e-5f);
      }
    }
  }

  @Test
  public void appleLogRoundTripsAndSamplesThroughCubeLut() throws Exception {
    assertEquals(0.0, AppleRender.appleLogEncode(-0.05641088), 1e-7);
    assertEquals(
        AppleRender.appleLogEncode(0.01 - 1e-9), AppleRender.appleLogEncode(0.01 + 1e-9), 1e-6);
    double mid = AppleRender.appleLogEncode(0.18);
    assertTrue("mid grey code " + mid, mid > 0.47 && mid < 0.50);
    for (double r = -0.05; r <= 16.0; r = r < 0.02 ? r + 0.002 : r * 1.19) {
      double p = AppleRender.appleLogEncode(r);
      assertEquals("round-trip at " + r, r, AppleRender.appleLogDecode(p), 1e-6 * Math.max(1.0, r));
    }

    CubeLut id = identityLut(17).withInput(CubeLut.Input.APPLE_LOG_REC2020);
    float[] out = new float[3];
    id.sample((float) FLog2.encode(0.18), (float) FLog2.encode(0.50), (float) FLog2.encode(0.04), out);
    assertEquals((float) AppleRender.appleLogEncode(0.18), out[0], 3e-4f);
    assertEquals((float) AppleRender.appleLogEncode(0.50), out[1], 3e-4f);
    assertEquals((float) AppleRender.appleLogEncode(0.04), out[2], 3e-4f);
  }

  @Test
  public void appleToneTablesAreStrictlyMonotonicAcrossAllStylesAndTuning() {
    for (AppleRender.Style style : AppleRender.Style.values()) {
      for (double hl = FujiRecipe.TONE_MIN; hl <= FujiRecipe.TONE_MAX; hl += 1.0) {
        for (double sh = FujiRecipe.TONE_MIN; sh <= FujiRecipe.TONE_MAX; sh += 1.0) {
          AppleRender.ToneTable tt = new AppleRender.ToneTable(style, 1.75, hl, sh);
          assertEquals(0f, tt.eval(0f), 0f);
          assertEquals(1f, tt.eval(1.75f), 0f);
          float prev = -1f;
          for (int i = 0; i <= 500; i++) {
            float x = 1.75f * (i / 500f);
            float y = tt.eval(x);
            assertTrue(style + " H=" + hl + " S=" + sh + " monotonic at " + x, y >= prev - 1e-7f);
            prev = y;
          }
        }
      }
    }
    AppleRender.ToneTable std = new AppleRender.ToneTable(AppleRender.Style.STANDARD, 1.75, 0, 0);
    AppleRender.ToneTable rich =
        new AppleRender.ToneTable(AppleRender.Style.RICH_CONTRAST, 1.75, 0, 0);
    AppleRender.ToneTable rose =
        new AppleRender.ToneTable(AppleRender.Style.NATURAL_ROSE, 1.75, 0, 0);
    assertTrue("Rich Contrast anchors deeper shadows than Standard", rich.eval(0.04f) < std.eval(0.04f));
    assertTrue("Natural Rose opens portrait midtones gently", rose.eval(0.12f) > rich.eval(0.12f));
  }

  @Test
  public void refBaselineRgbTonePreservesHueAngleAcrossExposureRange() {
    AppleRender.ToneTable tt = new AppleRender.ToneTable(AppleRender.Style.STANDARD, 1.80, 0, 0);
    float[][] baseColors = {
      {0.80f, 0.52f, 0.40f},
      {0.22f, 0.48f, 0.92f},
      {0.85f, 0.18f, 0.14f},
      {0.34f, 0.68f, 0.22f},
      {0.88f, 0.72f, 0.20f}
    };
    float[] out = new float[3];
    for (float[] base : baseColors) {
      double wantHue = rgbHueDegrees(base[0], base[1], base[2]);
      for (double scale = 0.04; scale <= 2.2; scale *= 1.45) {
        AppleRender.applyRgbTone(
            (float) (base[0] * scale),
            (float) (base[1] * scale),
            (float) (base[2] * scale),
            tt,
            out);
        if (out[0] < 0.999f || out[1] < 0.999f || out[2] < 0.999f) {
          double gotHue = rgbHueDegrees(out[0], out[1], out[2]);
          double diff = Math.abs(gotHue - wantHue);
          if (diff > 180) diff = 360 - diff;
          assertTrue("Hue drift " + diff + " deg at scale " + scale, diff < 0.05);
        }
      }
    }
  }

  @Test
  public void allFiveIphoneProfilesRegisterCleanlyAndDevelopWithoutLut() throws Exception {
    assertEquals(33, Recipe.ALL.length);
    assertEquals("iphone-16-standard", Recipe.ALL[28].fuji.key);
    assertEquals("iphone-16-natural-rose", Recipe.ALL[29].fuji.key);
    assertEquals("iphone-16-rich-contrast", Recipe.ALL[30].fuji.key);
    assertEquals("iphone-16-amber-cozy", Recipe.ALL[31].fuji.key);
    assertEquals("iphone-xs-max-warm", Recipe.ALL[32].fuji.key);

    assertEquals("iphone-16-standard", Recipe.DISPLAY_ORDER[4].fuji.key);
    assertEquals("iphone-xs-max-warm", Recipe.DISPLAY_ORDER[5].fuji.key);
    assertEquals("iphone-16-natural-rose", Recipe.DISPLAY_ORDER[6].fuji.key);
    assertEquals("iphone-16-rich-contrast", Recipe.DISPLAY_ORDER[7].fuji.key);
    assertEquals("iphone-16-amber-cozy", Recipe.DISPLAY_ORDER[8].fuji.key);

    for (FujiRecipe r : FujiRecipe.APPLE_STYLES) {
      assertTrue(r.apple());
      assertFalse(r.sony());
      assertFalse(FujiRecipe.isTechnical(r.filmSimulation));
      assertEquals(-1f / 3f, Recipe.rawCaptureBias(r), 1e-6f);
      assertEquals(0, r.noiseReduction);
      assertTrue(r.sharpness >= 2);
      assertTrue(r.clarity >= 1);

      Set<String> controls = new HashSet<>();
      List<TuningControl> list = TuningControl.fujifilm(r);
      for (TuningControl c : list) {
        controls.add(c.name);
        for (int p = 0; p < c.labels.length; p++) {
          FujiRecipe.Tuning t = r.tuning();
          c.set(t, p);
          assertEquals(r.key + " " + c.name, p, c.at(r.with(t).tuning()));
        }
      }
      assertFalse(controls.contains("Dynamic range"));
      assertFalse(controls.contains("Color Chrome Effect"));
      assertFalse(controls.contains("Color Chrome FX Blue"));
      assertTrue(controls.contains("Highlight") && controls.contains("Shadow") && controls.contains("Color"));
    }

    RawFrame grey = flatNeutral(160, 120, 0.12);
    for (FujiRecipe r : FujiRecipe.APPLE_STYLES) {
      RawDeveloper.Options opt = new RawDeveloper.Options();
      opt.grain = false;
      opt.threads = 2;
      int[] px = new int[grey.width * grey.height];
      Map<String, Object> stats =
          RawDeveloper.develop(
              grey,
              r,
              null,
              opt,
              (y, rows, argb, width) -> System.arraycopy(argb, 0, px, y * width, rows * width));
      assertEquals("sRGB", stats.get("colorSpace"));
      double mr = mean(px, grey.width, 16, 144, 16, 104, 0);
      double mg = mean(px, grey.width, 16, 144, 16, 104, 1);
      double mb = mean(px, grey.width, 16, 144, 16, 104, 2);
      assertTrue(r.key + " mid-grey luma " + mg, mg > 95 && mg < 160);
      if (r == FujiRecipe.IPHONE_AMBER_COZY || r == FujiRecipe.IPHONE_XS_MAX) {
        assertTrue(r.key + " warms ambient grey (R > B)", mr > mb + 5.0);
      } else {
        assertEquals(r.key + " neutral R vs G", mg, mr, 1.2);
        assertEquals(r.key + " neutral B vs G", mg, mb, 1.2);
      }
    }
  }

  @Test
  public void crispDetailPreservesSurfaceTextureOverSmoothBaselineWithoutHalos() throws Exception {
    // 1) Textured foliage/architecture scene (8 px + 16 px local detail with mild sensor noise)
    int w = 192, h = 128;
    double[] n = {0.5, 1.0, 0.625};
    short[] texData = new short[w * h];
    RawFrame texFrame = new RawFrame(texData, w, h, w);
    long rng = 1234567L;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int c = texFrame.color(x, y);
        rng = rng * 6364136223846793005L + 1442695040888963407L;
        double fineNoise = (((rng >>> 33) & 1023) / 1023.0 - 0.5) * 0.006;
        double detail =
            0.14
                * Math.exp(
                    0.26 * Math.sin(2.0 * Math.PI * x / 10.0) * Math.cos(2.0 * Math.PI * y / 10.0)
                        + 0.16 * Math.sin(2.0 * Math.PI * x / 4.0));
        double v = Math.max(0.005, Math.min(1.0, (detail + fineNoise) * n[c]));
        texData[y * w + x] = (short) Math.round(64 + v * (1023 - 64));
      }
    }
    texFrame.neutral = n;
    texFrame.colorMatrix1 = PHONE_CM1;
    texFrame.colorMatrix2 = PHONE_CM2;
    texFrame.forwardMatrix1 = PHONE_FM1;
    texFrame.forwardMatrix2 = PHONE_FM2;
    texFrame.illuminant1 = 21;
    texFrame.illuminant2 = 17;
    texFrame.iso = 400;

    // Compare crisp default IPHONE_XS_MAX / IPHONE_STANDARD vs artificially detuned NR=0, Sharp=0, Clarity=0
    FujiRecipe.Tuning smoothT = FujiRecipe.IPHONE_STANDARD.tuning();
    smoothT.noiseReduction = 0;
    smoothT.sharpness = 0;
    smoothT.clarity = 0;
    FujiRecipe smoothRecipe = FujiRecipe.IPHONE_STANDARD.with(smoothT);

    int[] pxCrisp = new int[w * h], pxXs = new int[w * h], pxSmooth = new int[w * h];
    RawDeveloper.Options opt = new RawDeveloper.Options();
    opt.grain = false;
    RawDeveloper.develop(
        texFrame,
        FujiRecipe.IPHONE_STANDARD,
        null,
        opt,
        (y, rows, argb, width) -> System.arraycopy(argb, 0, pxCrisp, y * width, rows * width));
    RawDeveloper.develop(
        texFrame,
        FujiRecipe.IPHONE_XS_MAX,
        null,
        opt,
        (y, rows, argb, width) -> System.arraycopy(argb, 0, pxXs, y * width, rows * width));
    RawDeveloper.Options optSmooth = new RawDeveloper.Options();
    optSmooth.grain = false;
    optSmooth.lumaNr = 2.08; // exact v0.9.0 pre-Deep-Fusion NR strength
    optSmooth.sharpen = 0.45 / 0.62;
    RawDeveloper.develop(
        texFrame,
        smoothRecipe,
        null,
        optSmooth,
        (y, rows, argb, width) -> System.arraycopy(argb, 0, pxSmooth, y * width, rows * width));

    double acutanceCrisp = gradientRms(pxCrisp, w, 24, w - 24, 24, h - 24);
    double acutanceXs = gradientRms(pxXs, w, 24, w - 24, 24, h - 24);
    double acutanceSmooth = gradientRms(pxSmooth, w, 24, w - 24, 24, h - 24);
    assertTrue(
        "Crisp i16 detail (" + acutanceCrisp + ") must significantly exceed NR=0 smooth (" + acutanceSmooth + ")",
        acutanceCrisp > acutanceSmooth * 1.18);
    assertTrue(
        "Crisp iXS detail (" + acutanceXs + ") must significantly exceed NR=0 smooth (" + acutanceSmooth + ")",
        acutanceXs > acutanceSmooth * 1.18);

    // 2) 5-stop step silhouette check: LTM lifts shadows & recovers highlights without halos
    int sw = 256, sh = 128;
    short[] stepData = new short[sw * sh];
    RawFrame hdr = new RawFrame(stepData, sw, sh, sw);
    for (int y = 0; y < sh; y++) {
      for (int x = 0; x < sw; x++) {
        int c = hdr.color(x, y);
        double level = x < 128 ? 0.025 : 0.80;
        stepData[y * sw + x] = (short) Math.round(64 + Math.min(1.0, level * n[c]) * (1023 - 64));
      }
    }
    hdr.neutral = n;
    hdr.colorMatrix1 = PHONE_CM1;
    hdr.colorMatrix2 = PHONE_CM2;
    hdr.forwardMatrix1 = PHONE_FM1;
    hdr.forwardMatrix2 = PHONE_FM2;
    hdr.illuminant1 = 21;
    hdr.illuminant2 = 17;
    hdr.iso = 100;

    int[] withLtm = new int[sw * sh], noLtm = new int[sw * sh];
    RawDeveloper.Options optOn = new RawDeveloper.Options();
    optOn.grain = false;
    optOn.stillsTone = true;
    optOn.fixedGain = 1.6;
    RawDeveloper.develop(
        hdr,
        FujiRecipe.IPHONE_STANDARD,
        null,
        optOn,
        (y, rows, argb, width) -> System.arraycopy(argb, 0, withLtm, y * width, rows * width));

    RawDeveloper.Options optOff = new RawDeveloper.Options();
    optOff.grain = false;
    optOff.stillsTone = false;
    optOff.fixedGain = 1.6;
    RawDeveloper.develop(
        hdr,
        FujiRecipe.IPHONE_STANDARD,
        null,
        optOff,
        (y, rows, argb, width) -> System.arraycopy(argb, 0, noLtm, y * width, rows * width));

    double darkOn = mean(withLtm, sw, 24, 88, 24, 104, 1);
    double darkOff = mean(noLtm, sw, 24, 88, 24, 104, 1);
    double brightOn = mean(withLtm, sw, 168, 232, 24, 104, 1);
    double brightOff = mean(noLtm, sw, 168, 232, 24, 104, 1);

    assertTrue("LTM lifts dark shadows (" + darkOff + " -> " + darkOn + ")", darkOn > darkOff + 10.0);
    assertTrue(
        "LTM recovers bright highlights (" + brightOff + " -> " + brightOn + ")",
        brightOn < brightOff - 8.0);

    double darkNearEdge = mean(withLtm, sw, 112, 120, 24, 104, 1);
    double brightNearEdge = mean(withLtm, sw, 136, 144, 24, 104, 1);
    assertTrue(
        "No bright halo on dark side of silhouette (" + darkNearEdge + " vs " + darkOn + ")",
        Math.abs(darkNearEdge - darkOn) < 8.0);
    assertTrue(
        "No dark halo on bright side of silhouette (" + brightNearEdge + " vs " + brightOn + ")",
        Math.abs(brightNearEdge - brightOn) < 8.0);
  }

  @Test
  public void backgroundVibranceBoostsFoliageSkyAndRedWhileProtectingSkinAndDifferentiatingStyles() {
    // Helper: convert linear sRGB patch -> linear Display P3 -> grade -> Universal Vibrant sRGB
    float[] skinIn = srgbToLinearP3(0.78f, 0.58f, 0.47f);
    float[] leafIn = srgbToLinearP3(0.36f, 0.56f, 0.26f);
    float[] skyIn = srgbToLinearP3(0.32f, 0.56f, 0.84f);
    float[] redIn = srgbToLinearP3(0.76f, 0.26f, 0.24f);

    float[] skinStd = skinIn.clone(), skinXs = skinIn.clone(), skinRose = skinIn.clone();
    AppleRender.gradeDisplayP3(skinStd, AppleRender.Style.STANDARD, 0, false);
    AppleRender.gradeDisplayP3(skinXs, AppleRender.Style.XS_MAX_WARM, 0, false);
    AppleRender.gradeDisplayP3(skinRose, AppleRender.Style.NATURAL_ROSE, 0, false);

    // Skin stays protected (< 12% saturation change) while Natural Rose tames yellow cast vs XS Max Warm
    double skinBaseSat = hsvSat(0.78f, 0.58f, 0.47f);
    assertTrue("Skin stays natural on Standard", hsvSat(skinStd[0], skinStd[1], skinStd[2]) < skinBaseSat * 1.12);
    assertTrue("Skin stays protected on XS Max", hsvSat(skinXs[0], skinXs[1], skinXs[2]) < skinBaseSat * 1.15);
    assertTrue("Natural Rose reduces yellow cast vs XS Max", skinRose[2] / skinRose[1] > skinXs[2] / skinXs[1]);

    // Background foliage, sky, and red gain vibrant iPhone saturation (> +14% to +35%)
    for (AppleRender.Style st : new AppleRender.Style[] {AppleRender.Style.STANDARD, AppleRender.Style.XS_MAX_WARM, AppleRender.Style.RICH_CONTRAST}) {
      float[] leaf = leafIn.clone(), sky = skyIn.clone(), red = redIn.clone();
      AppleRender.gradeDisplayP3(leaf, st, 0, false);
      AppleRender.gradeDisplayP3(sky, st, 0, false);
      AppleRender.gradeDisplayP3(red, st, 0, false);
      assertTrue(st + " boosts lush foliage saturation", hsvSat(leaf[0], leaf[1], leaf[2]) > hsvSat(0.36f, 0.56f, 0.26f) * 1.14);
      assertTrue(st + " boosts cerulean sky saturation", hsvSat(sky[0], sky[1], sky[2]) > hsvSat(0.32f, 0.56f, 0.84f) * 1.24);
      assertTrue(st + " boosts street red saturation", hsvSat(red[0], red[1], red[2]) > hsvSat(0.76f, 0.26f, 0.24f) * 1.15);
    }

    // LutPreview works with lut == null and differentiates all 5 iPhone profiles (including iXS)
    int[] sample = {0xffc8967c, 0xff4c86d4, 0xff629c3e, 0xffd84438, 0xff808080};
    Set<Long> hashes = new HashSet<>();
    for (FujiRecipe r : FujiRecipe.APPLE_STYLES) {
      int[] copy = sample.clone();
      LutPreview.apply(copy, null, r);
      assertTrue("Distinct preview for " + r.key, hashes.add(fnv(copy)));
    }
  }

  @Test
  public void ultraHdrGainmapBoostsOnlyHighlightsAndMatchesAcross112MpBands() throws Exception {
    assertEquals(0, AppleRender.computeGainmapByte(0.18f, 0.18f) & 0xFF);
    assertEquals(0, AppleRender.computeGainmapByte(0.42f, 0.35f) & 0xFF);
    int brightGm = AppleRender.computeGainmapByte(2.10f, 0.85f) & 0xFF;
    assertTrue("Bright unclipped highlight produces strong Ultra HDR gainmap: " + brightGm, brightGm > 120);

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
    frame.iso = 200;

    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int c = frame.color(x, y);
        boolean sunPatch = Math.hypot(x - 96, y - 256) <= 18.0;
        double level = sunPatch ? 0.95 : (0.06 + 0.12 * (y / (double) h));
        data[y * w + x] = (short) Math.round(64 + Math.min(1.0, level * n[c]) * (1023 - 64));
      }
    }

    int[] fullPx = new int[w * h];
    RawDeveloper.Options optFull = new RawDeveloper.Options();
    optFull.grain = true;
    optFull.grainSeed = 98765L;
    optFull.computeGainmap = true;
    optFull.maxBandPixels = Long.MAX_VALUE;
    RawDeveloper.develop(
        frame,
        FujiRecipe.IPHONE_XS_MAX,
        null,
        optFull,
        (y, rows, argb, width) -> System.arraycopy(argb, 0, fullPx, y * width, rows * width));
    assertNotNull(optFull.outGainmap);
    assertEquals((w + 3) / 4, optFull.outGainmapW);
    assertEquals((h + 3) / 4, optFull.outGainmapH);

    assertEquals(0, optFull.outGainmap[2 * optFull.outGainmapW + 2] & 0xFF);
    int centreGm = optFull.outGainmap[(256 / 4) * optFull.outGainmapW + (96 / 4)] & 0xFF;
    assertTrue("Sun patch gainmap > 40, got " + centreGm, centreGm > 40);

    int[] bandPx = new int[w * h];
    RawDeveloper.Options optBand = new RawDeveloper.Options();
    optBand.grain = true;
    optBand.grainSeed = 98765L;
    optBand.computeGainmap = true;
    optBand.maxBandPixels = 38_000L;
    Map<String, Object> bandStats =
        RawDeveloper.develop(
            frame,
            FujiRecipe.IPHONE_XS_MAX,
            null,
            optBand,
            (y, rows, argb, width) -> System.arraycopy(argb, 0, bandPx, y * width, rows * width));
    assertTrue(((Number) bandStats.get("bands")).intValue() >= 3);

    int maxPxDiff = 0;
    for (int i = 0; i < w * h; i++) {
      int a = fullPx[i], b = bandPx[i];
      maxPxDiff = Math.max(maxPxDiff, Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)));
      maxPxDiff = Math.max(maxPxDiff, Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)));
      maxPxDiff = Math.max(maxPxDiff, Math.abs((a & 255) - (b & 255)));
    }
    assertTrue("Apple banded pixel diff " + maxPxDiff, maxPxDiff <= 1);

    int maxGmDiff = 0;
    for (int i = 0; i < optFull.outGainmap.length; i++) {
      int d = Math.abs((optFull.outGainmap[i] & 0xFF) - (optBand.outGainmap[i] & 0xFF));
      maxGmDiff = Math.max(maxGmDiff, d);
    }
    assertTrue("Apple banded gainmap diff " + maxGmDiff, maxGmDiff <= 1);
  }

  @Test
  public void ultraHdrMpfDirectoryRepairsStalePrimarySizeAfterExifInsert() throws Exception {
    for (boolean littleEndian : new boolean[] {true, false}) {
      for (boolean exifBeforeMpf : new boolean[] {true, false}) {
        byte[] jpeg = buildSyntheticUltraHdrJpeg(littleEndian, exifBeforeMpf, 314);
        File tmp = File.createTempFile("mpf-test-", ".jpg");
        try {
          try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(jpeg);
          }
          assertTrue(UltraHdrJpeg.repairMpfAfterExifSave(tmp));
          byte[] fixed = Files.readAllBytes(tmp.toPath());
          int[] parsed = readMpfPrimaryAndSecondary(fixed, littleEndian);
          int primarySize = parsed[0];
          int gainmapStart = parsed[1];
          int gainmapSize = parsed[2];
          assertEquals("Primary size equals gainmap start", gainmapStart, primarySize);
          assertEquals("File ends right after gainmap", fixed.length, gainmapStart + gainmapSize);
          assertEquals(0xFF, fixed[gainmapStart - 2] & 0xFF);
          assertEquals(0xD9, fixed[gainmapStart - 1] & 0xFF);
          assertEquals(0xFF, fixed[gainmapStart] & 0xFF);
          assertEquals(0xD8, fixed[gainmapStart + 1] & 0xFF);
        } finally {
          tmp.delete();
        }
      }
    }
  }

  private static float[] srgbToLinearP3(float r, float g, float b) {
    float lr = srgbDec(r), lg = srgbDec(g), lb = srgbDec(b);
    float[] m = AppleRender.REC709_TO_P3;
    return new float[] {
      m[0] * lr + m[1] * lg + m[2] * lb,
      m[3] * lr + m[4] * lg + m[5] * lb,
      m[6] * lr + m[7] * lg + m[8] * lb
    };
  }

  @Test
  public void indoorPortraitAgainstBrightWallProducesLuminous3dFaceAndGlossyBlackHair()
      throws Exception {
    // Verify skin locus separates human skin from walls, hair, and warm wood
    assertTrue(AppleRender.isPortraitSkinLinearP3(0.45f, 0.30f, 0.21f));
    assertFalse("Warm wall is not skin", AppleRender.isPortraitSkinLinearP3(0.75f, 0.72f, 0.62f));
    assertFalse("Warm wood is not skin", AppleRender.isPortraitSkinLinearP3(0.32f, 0.21f, 0.06f));

    // Indoor portrait against a bright cream wall (60% of frame): tests both boxed face metering
    // and 3/4-angle unboxed portrait skin fallback.
    int w = 240, h = 320;
    double[] n = {0.5, 1.0, 0.625};
    short[] data = new short[w * h];
    RawFrame portrait = new RawFrame(data, w, h, w);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int c = portrait.color(x, y);
        double rMul = 1.0, gMul = 1.0, bMul = 1.0, lum;
        boolean inHair = x >= 76 && x < 164 && y >= 76 && y < 112;
        boolean inFace = x >= 76 && x < 164 && y >= 112 && y < 208;
        boolean inWood = y >= 248;
        if (inHair) {
          lum = 0.012; // jet-black hair
        } else if (inFace) {
          // Forehead (y 112..144), lit left cheek (x < 120), shadow right cheek (x >= 120)
          lum = y < 144 ? 0.165 : (x < 120 ? 0.132 : 0.086);
          rMul = 1.66;
          gMul = 1.00;
          bMul = 0.66;
        } else if (inWood) {
          lum = 0.095;
          rMul = 1.62;
          gMul = 1.00;
          bMul = 0.24;
        } else {
          // Bright indoor cream wall filling the upper/side background
          lum = 0.43;
          rMul = 1.04;
          gMul = 1.00;
          bMul = 0.88;
        }
        double chMul = c == 0 ? rMul : c == 1 ? gMul : bMul;
        double v = Math.max(0.002, Math.min(1.0, lum * chMul * n[c]));
        data[y * w + x] = (short) Math.round(64 + v * (1023 - 64));
      }
    }
    portrait.neutral = n;
    portrait.colorMatrix1 = PHONE_CM1;
    portrait.colorMatrix2 = PHONE_CM2;
    portrait.forwardMatrix1 = PHONE_FM1;
    portrait.forwardMatrix2 = PHONE_FM2;
    portrait.illuminant1 = 21;
    portrait.illuminant2 = 17;
    portrait.iso = 1200;

    // Case A: 3/4-angle portrait where frontal face detector missed (faces == null)
    int[] pxUnboxed = new int[w * h];
    RawDeveloper.Options optUnboxed = new RawDeveloper.Options();
    optUnboxed.grain = false;
    optUnboxed.faceExposure = true;
    Map<String, Object> statsUnboxed =
        RawDeveloper.develop(
            portrait,
            FujiRecipe.IPHONE_XS_MAX,
            null,
            optUnboxed,
            (y, rows, argb, width) -> System.arraycopy(argb, 0, pxUnboxed, y * width, rows * width));
    assertTrue(
        "Unboxed portrait triggers skin metering fallback: " + statsUnboxed.get("faceExposure"),
        String.valueOf(statsUnboxed.get("faceExposure")).contains("portrait skin metered"));

    // Case B: Hardware face box reported
    portrait.faces = new int[] {76, 108, 164, 208};
    portrait.faceScores = new int[] {85};
    portrait.faceSource = "camera face detection";
    int[] pxBoxed = new int[w * h];
    RawDeveloper.Options optBoxed = new RawDeveloper.Options();
    optBoxed.grain = false;
    optBoxed.faceExposure = true;
    RawDeveloper.develop(
        portrait,
        FujiRecipe.IPHONE_XS_MAX,
        null,
        optBoxed,
        (y, rows, argb, width) -> System.arraycopy(argb, 0, pxBoxed, y * width, rows * width));

    for (int[][] pair : new int[][][] {{pxUnboxed}, {pxBoxed}}) {
      int[] px = pair[0];
      double wallL = lumaMean(px, w, 16, 60, 24, 180);
      double foreheadL = lumaMean(px, w, 88, 152, 118, 138);
      double litCheekL = lumaMean(px, w, 86, 114, 152, 196);
      double shdCheekL = lumaMean(px, w, 126, 154, 152, 196);
      double hairL = lumaMean(px, w, 88, 152, 82, 104);

      assertTrue("Bright indoor wall luminous (" + wallL + ")", wallL > 218.0);
      assertTrue("Forehead bright (" + foreheadL + ", not 150)", foreheadL > 166.0 && foreheadL < 204.0);
      assertTrue("Lit cheek bright (" + litCheekL + ", not 130)", litCheekL > 148.0 && litCheekL < 190.0);
      assertTrue(
          "3D facial contrast preserved (" + (litCheekL - shdCheekL) + " codes)",
          litCheekL - shdCheekL > 16.0);
      assertTrue("Black hair stays glossy dark (" + hairL + ", not grey 71)", hairL < 60.0);
    }
  }

  @Test
  public void highIsoSnrGatedDetailKeepsFlatWallsSilkCleanWhileSharpeningFineEdges()
      throws Exception {
    int w = 224, h = 144;
    double[] n = {0.5, 1.0, 0.625};
    short[] data = new short[w * h];
    RawFrame noisy = new RawFrame(data, w, h, w);
    long rng = 987654321L;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int c = noisy.color(x, y);
        // Left half: flat indoor cream wall; Right half: crisp fine hair/text bars (4 px period)
        double base =
            x < 112
                ? 0.28
                : (0.18 + 0.11 * (((x / 3) & 1) == 0 ? 1.0 : -1.0));
        // Realistic ISO 1600 shot + read noise
        rng = rng * 6364136223846793005L + 1442695040888963407L;
        double u1 = (((rng >>> 32) & 0xFFFF) + 0.5) / 65536.0;
        rng = rng * 6364136223846793005L + 1442695040888963407L;
        double u2 = (((rng >>> 32) & 0xFFFF) + 0.5) / 65536.0;
        double gauss = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
        double sigma = Math.sqrt(0.00018 * base + 0.000012);
        double v = Math.max(0.002, Math.min(1.0, (base + gauss * sigma) * n[c]));
        data[y * w + x] = (short) Math.round(64 + v * (1023 - 64));
      }
    }
    noisy.neutral = n;
    noisy.colorMatrix1 = PHONE_CM1;
    noisy.colorMatrix2 = PHONE_CM2;
    noisy.forwardMatrix1 = PHONE_FM1;
    noisy.forwardMatrix2 = PHONE_FM2;
    noisy.illuminant1 = 21;
    noisy.illuminant2 = 17;
    noisy.iso = 1600;

    int[] pxV092 = new int[w * h];
    RawDeveloper.Options opt = new RawDeveloper.Options();
    opt.grain = false;
    RawDeveloper.develop(
        noisy,
        FujiRecipe.IPHONE_XS_MAX,
        null,
        opt,
        (y, rows, argb, width) -> System.arraycopy(argb, 0, pxV092, y * width, rows * width));

    // Simulate noisy ungated NR-off development
    int[] pxRawNoisy = new int[w * h];
    RawDeveloper.Options optNoNr = new RawDeveloper.Options();
    optNoNr.grain = false;
    optNoNr.lumaNr = 0.05;
    RawDeveloper.develop(
        noisy,
        FujiRecipe.IPHONE_XS_MAX,
        null,
        optNoNr,
        (y, rows, argb, width) -> System.arraycopy(argb, 0, pxRawNoisy, y * width, rows * width));

    double wallNoiseV092 = diff1Mean(pxV092, w, 20, 96, 20, h - 20);
    double wallNoiseRaw = diff1Mean(pxRawNoisy, w, 20, 96, 20, h - 20);
    double edgeGradV092 = gradientRms(pxV092, w, 128, w - 20, 20, h - 20);

    assertTrue(
        "SNR-gated NR cleans high-ISO flat wall >3x (" + wallNoiseRaw + " -> " + wallNoiseV092 + ")",
        wallNoiseV092 < wallNoiseRaw * 0.33 && wallNoiseV092 < 1.8);
    assertTrue("Fine edge acutance stays razor-sharp (" + edgeGradV092 + ")", edgeGradV092 > 22.0);
  }

  private static double lumaMean(int[] argb, int w, int x0, int x1, int y0, int y1) {
    double sum = 0;
    long n = 0;
    for (int y = y0; y < y1; y++)
      for (int x = x0; x < x1; x++) {
        int p = argb[y * w + x];
        sum += 0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255);
        n++;
      }
    return sum / Math.max(1, n);
  }

  private static double diff1Mean(int[] argb, int w, int x0, int x1, int y0, int y1) {
    double sum = 0;
    long n = 0;
    for (int y = y0; y < y1; y++)
      for (int x = x0; x < x1; x++) {
        int p0 = argb[y * w + x], p1 = argb[y * w + x + 1];
        double l0 = 0.299 * ((p0 >> 16) & 255) + 0.587 * ((p0 >> 8) & 255) + 0.114 * (p0 & 255);
        double l1 = 0.299 * ((p1 >> 16) & 255) + 0.587 * ((p1 >> 8) & 255) + 0.114 * (p1 & 255);
        sum += Math.abs(l1 - l0);
        n++;
      }
    return sum / Math.max(1, n);
  }

  private static float srgbDec(float c) {
    return (float) (c <= 0.04045f ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
  }

  private static double hsvSat(float r, float g, float b) {
    double mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
    return mx <= 1e-6 ? 0.0 : (mx - mn) / mx;
  }

  private static double gradientRms(int[] argb, int w, int x0, int x1, int y0, int y1) {
    double sumSq = 0;
    long cnt = 0;
    for (int y = y0; y < y1; y++) {
      for (int x = x0; x < x1; x++) {
        int g = (argb[y * w + x] >> 8) & 255;
        int gx = ((argb[y * w + x + 1] >> 8) & 255) - g;
        int gy = ((argb[(y + 1) * w + x] >> 8) & 255) - g;
        sumSq += gx * gx + gy * gy;
        cnt++;
      }
    }
    return Math.sqrt(sumSq / Math.max(1, cnt));
  }

  private static CubeLut identityLut(int n) throws Exception {
    StringBuilder sb = new StringBuilder();
    sb.append("TITLE \"identity\"\nLUT_3D_SIZE ").append(n).append('\n');
    for (int b = 0; b < n; b++)
      for (int g = 0; g < n; g++)
        for (int r = 0; r < n; r++)
          sb.append(
              String.format(
                  Locale.US,
                  "%.6f %.6f %.6f\n",
                  r / (double) (n - 1),
                  g / (double) (n - 1),
                  b / (double) (n - 1)));
    return CubeLut.parse(new StringReader(sb.toString()));
  }

  private static RawFrame flatNeutral(int w, int h, double level) {
    double[] n = {0.5, 1.0, 0.625};
    short[] data = new short[w * h];
    RawFrame f = new RawFrame(data, w, h, w);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int c = f.color(x, y);
        data[y * w + x] = (short) Math.round(64 + level * n[c] * (1023 - 64));
      }
    f.neutral = n;
    f.colorMatrix1 = PHONE_CM1;
    f.colorMatrix2 = PHONE_CM2;
    f.forwardMatrix1 = PHONE_FM1;
    f.forwardMatrix2 = PHONE_FM2;
    f.illuminant1 = 21;
    f.illuminant2 = 17;
    return f;
  }

  private static double mean(int[] argb, int w, int x0, int x1, int y0, int y1, int ch) {
    long sum = 0, n = 0;
    int shift = ch == 0 ? 16 : ch == 1 ? 8 : 0;
    for (int y = y0; y < y1; y++)
      for (int x = x0; x < x1; x++) {
        sum += (argb[y * w + x] >> shift) & 255;
        n++;
      }
    return sum / (double) n;
  }

  private static long fnv(int[] a) {
    long h = 0xcbf29ce484222325L;
    for (int v : a) {
      h ^= v & 0xffffffffL;
      h *= 0x100000001b3L;
    }
    return h;
  }

  private static byte[] buildSyntheticUltraHdrJpeg(
      boolean le, boolean app1BeforeApp2, int app1Delta) throws Exception {
    byte[] tiff = new byte[82];
    if (le) {
      tiff[0] = 'I';
      tiff[1] = 'I';
      tiff[2] = 0x2A;
      tiff[3] = 0x00;
    } else {
      tiff[0] = 'M';
      tiff[1] = 'M';
      tiff[2] = 0x00;
      tiff[3] = 0x2A;
    }
    put32(tiff, 4, 8, le);
    put16(tiff, 8, 3, le);
    put16(tiff, 10, 0xB000, le);
    put16(tiff, 12, 7, le);
    put32(tiff, 14, 4, le);
    tiff[18] = '0';
    tiff[19] = '1';
    tiff[20] = '0';
    tiff[21] = '0';
    put16(tiff, 22, 0xB001, le);
    put16(tiff, 24, 4, le);
    put32(tiff, 26, 1, le);
    put32(tiff, 30, 2, le);
    put16(tiff, 34, 0xB002, le);
    put16(tiff, 36, 7, le);
    put32(tiff, 38, 32, le);
    put32(tiff, 42, 50, le);
    put32(tiff, 46, 0, le);

    int app2SegLen = 2 + 4 + tiff.length;
    byte[] scanAndEoi = {(byte) 0xFF, (byte) 0xDA, 0x00, 0x04, 0x00, 0x00, 0x11, 0x22, (byte) 0xFF, (byte) 0xD9};
    byte[] gainmapJpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDA, 0x00, 0x02, 0x33, (byte) 0xFF, (byte) 0xD9};

    int origTiffBase = 2 + 4 + 4;
    int origPrimarySize = 2 + 2 + app2SegLen + scanAndEoi.length;
    int origGainmapRel = origPrimarySize - origTiffBase;

    put32(tiff, 50 + 4, origPrimarySize, le);
    put32(tiff, 50 + 8, 0, le);
    put32(tiff, 50 + 16 + 4, gainmapJpeg.length, le);
    put32(tiff, 50 + 16 + 8, origGainmapRel, le);

    byte[] app1 = new byte[app1Delta];
    app1[0] = (byte) 0xFF;
    app1[1] = (byte) 0xE1;
    app1[2] = (byte) ((app1Delta - 2) >>> 8);
    app1[3] = (byte) (app1Delta - 2);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0xFF);
    out.write(0xD8);
    if (app1BeforeApp2) out.write(app1);
    out.write(0xFF);
    out.write(0xE2);
    out.write(app2SegLen >>> 8);
    out.write(app2SegLen & 0xFF);
    out.write(new byte[] {'M', 'P', 'F', 0});
    out.write(tiff);
    if (!app1BeforeApp2) out.write(app1);
    out.write(scanAndEoi);
    out.write(gainmapJpeg);
    return out.toByteArray();
  }

  private static int[] readMpfPrimaryAndSecondary(byte[] jpeg, boolean le) {
    for (int i = 2; i + 10 < jpeg.length; ) {
      int marker = jpeg[i + 1] & 0xFF;
      int len = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
      if (marker == 0xE2 && jpeg[i + 4] == 'M' && jpeg[i + 5] == 'P' && jpeg[i + 6] == 'F' && jpeg[i + 7] == 0) {
        int tiffBase = i + 8;
        int mpOff = tiffBase + 50;
        int pSize = get32(jpeg, mpOff + 4, le);
        int gmSize = get32(jpeg, mpOff + 16 + 4, le);
        int gmRel = get32(jpeg, mpOff + 16 + 8, le);
        return new int[] {pSize, tiffBase + gmRel, gmSize};
      }
      i += 2 + len;
    }
    throw new AssertionError("MPF segment not found");
  }

  private static void put16(byte[] b, int o, int v, boolean le) {
    if (le) {
      b[o] = (byte) v;
      b[o + 1] = (byte) (v >>> 8);
    } else {
      b[o] = (byte) (v >>> 8);
      b[o + 1] = (byte) v;
    }
  }

  private static void put32(byte[] b, int o, int v, boolean le) {
    if (le) {
      b[o] = (byte) v;
      b[o + 1] = (byte) (v >>> 8);
      b[o + 2] = (byte) (v >>> 16);
      b[o + 3] = (byte) (v >>> 24);
    } else {
      b[o] = (byte) (v >>> 24);
      b[o + 1] = (byte) (v >>> 16);
      b[o + 2] = (byte) (v >>> 8);
      b[o + 3] = (byte) v;
    }
  }

  private static int get32(byte[] b, int o, boolean le) {
    return le
        ? ((b[o] & 0xFF)
            | ((b[o + 1] & 0xFF) << 8)
            | ((b[o + 2] & 0xFF) << 16)
            | ((b[o + 3] & 0xFF) << 24))
        : (((b[o] & 0xFF) << 24)
            | ((b[o + 1] & 0xFF) << 16)
            | ((b[o + 2] & 0xFF) << 8)
            | (b[o + 3] & 0xFF));
  }

  private static double rgbHueDegrees(float r, float g, float b) {
    double mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
    double d = mx - mn;
    if (d < 1e-7) return 0;
    double h;
    if (mx == r) h = ((g - b) / d) % 6.0;
    else if (mx == g) h = (b - r) / d + 2.0;
    else h = (r - g) / d + 4.0;
    h *= 60.0;
    return h < 0 ? h + 360.0 : h;
  }
}
