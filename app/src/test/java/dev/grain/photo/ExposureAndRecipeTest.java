package dev.grain.photo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.grain.photo.develop.FujiRecipe;
import dev.grain.photo.develop.HiResMerger;
import dev.grain.photo.develop.RawFrame;
import java.util.Arrays;
import org.junit.Test;

public class ExposureAndRecipeTest {
  @Test
  public void brightKodakMatchesPublishedFujiXWeeklyRecipe() {
    FujiRecipe r = FujiRecipe.BRIGHT_KODAK;
    assertEquals("bright-kodak", r.key);
    assertEquals("Bright Kodak", r.name);
    assertEquals("CLASSIC-CHROME", r.filmSimulation);
    assertEquals("Classic Chrome", r.filmLabel);
    assertEquals(FujiRecipe.DynamicRange.DR400, r.dynamicRange);
    assertEquals(-2.0, r.highlight, 1e-6);
    assertEquals(-2.0, r.shadow, 1e-6);
    assertEquals(4, r.color);
    assertEquals(-2, r.sharpness);
    assertEquals(-4, r.noiseReduction);
    assertEquals(-3, r.clarity);
    assertEquals(2, r.grainRoughness);
    assertTrue(r.grainLarge);
    assertEquals(0, r.colorChrome);
    assertEquals(0, r.colorChromeBlue);
    assertEquals(FujiRecipe.WhiteBalance.DAYLIGHT, r.wbMode);
    assertEquals(3, r.wbShiftRed);
    assertEquals(-7, r.wbShiftBlue);
    assertEquals("+1 to +1 2/3 EV", r.exposureNote);
    assertTrue(r.source.startsWith("https://fujixweekly.com/"));
  }

  @Test
  public void recipeIdsRemainAppendOnlyAndDisplayOrderIncludesBrightKodak() {
    assertEquals("Sony s709 stays at id 24", 24, findRecipe("sony-s709").id);
    Recipe bkd = Recipe.ALL[25];
    assertSame(FujiRecipe.BRIGHT_KODAK, bkd.fuji);
    assertTrue(bkd.raw);
    assertFalse(bkd.standard);
    int goldIdx = -1, bkdIdx = -1;
    for (int i = 0; i < Recipe.DISPLAY_ORDER.length; i++) {
      Recipe item = Recipe.DISPLAY_ORDER[i];
      if (item.fuji == FujiRecipe.KODAK_GOLD_200) goldIdx = i;
      if (item.fuji == FujiRecipe.BRIGHT_KODAK) bkdIdx = i;
    }
    assertTrue("Bright Kodak appears right after Kodak Gold 200", goldIdx >= 0 && bkdIdx == goldIdx + 1);
  }

  @Test
  public void kodak800v3AndLeicaXMatchPublishedSettingsAndPhoneCaptureHeadroom() {
    FujiRecipe k800 = FujiRecipe.KODAK_800_V3;
    assertEquals("kodak-800-v3", k800.key);
    assertEquals("Kodak 800 v3", k800.name);
    assertEquals("CLASSIC-CHROME", k800.filmSimulation);
    assertEquals(FujiRecipe.DynamicRange.DR400, k800.dynamicRange);
    assertEquals(FujiRecipe.WhiteBalance.KELVIN, k800.wbMode);
    assertEquals(6600, k800.kelvin);
    assertEquals(-1, k800.wbShiftRed);
    assertEquals(-3, k800.wbShiftBlue);
    assertEquals(-2.0, k800.highlight, 1e-6);
    assertEquals(-0.5, k800.shadow, 1e-6);
    assertEquals(3, k800.color);
    assertEquals(-2, k800.sharpness);
    assertEquals(-4, k800.noiseReduction);
    assertEquals(-3, k800.clarity);
    assertEquals(2, k800.grainRoughness);
    assertTrue(k800.grainLarge);
    assertEquals(2, k800.colorChrome);
    assertEquals(1, k800.colorChromeBlue);
    assertEquals("+1/3 to +1 1/3 EV", k800.exposureNote);
    assertTrue(k800.sensorCalibrated());
    assertEquals(-1.25f, Recipe.rawCaptureBias(k800), 1e-5f);

    FujiRecipe leica = FujiRecipe.LEICA_X;
    assertEquals("leica-x", leica.key);
    assertEquals("Leica X", leica.name);
    assertEquals("CLASSIC-CHROME", leica.filmSimulation);
    assertEquals(FujiRecipe.DynamicRange.AUTO, leica.dynamicRange);
    assertEquals(FujiRecipe.WhiteBalance.AUTO, leica.wbMode);
    assertEquals(1, leica.wbShiftRed);
    assertEquals(-2, leica.wbShiftBlue);
    assertEquals(-1.0, leica.highlight, 1e-6);
    assertEquals(0.0, leica.shadow, 1e-6);
    assertEquals(4, leica.color);
    assertEquals(0, leica.sharpness);
    assertEquals(-4, leica.noiseReduction);
    assertEquals(0, leica.clarity);
    assertEquals(0, leica.grainRoughness);
    assertFalse(leica.grainLarge);
    assertEquals(2, leica.colorChrome);
    assertEquals(2, leica.colorChromeBlue);
    assertEquals("0 to +1/3 EV", leica.exposureNote);
    assertTrue(leica.sensorCalibrated());
    assertEquals(-2f / 3f, Recipe.rawCaptureBias(leica), 1e-5f);

    FujiRecipe cine = FujiRecipe.CINESTILL_800T;
    assertTrue(cine.sensorCalibrated());
    assertEquals(-1.0f, Recipe.rawCaptureBias(cine), 1e-5f);
    assertEquals(
        android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
        Recipe.awbModeFor(cine));
    assertEquals(
        android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
        Recipe.awbModeFor(k800));

    assertSame(FujiRecipe.KODAK_800_V3, Recipe.ALL[26].fuji);
    assertSame(FujiRecipe.LEICA_X, Recipe.ALL[27].fuji);
    int bkdIdx = -1, k800Idx = -1, lcxIdx = -1;
    for (int i = 0; i < Recipe.DISPLAY_ORDER.length; i++) {
      Recipe item = Recipe.DISPLAY_ORDER[i];
      if (item.fuji == FujiRecipe.BRIGHT_KODAK) bkdIdx = i;
      if (item.fuji == FujiRecipe.KODAK_800_V3) k800Idx = i;
      if (item.fuji == FujiRecipe.LEICA_X) lcxIdx = i;
    }
    assertTrue(bkdIdx >= 0 && k800Idx == bkdIdx + 1 && lcxIdx == k800Idx + 1);
  }

  @Test
  public void exposurePlanCoversProgramShutterIsoAndManualModes() {
    double neutral = 16_000_000.0 * 100.0; // 1/60s @ ISO 100
    assertNull(ExposureMath.planStill(0, 0, 0, 0, neutral, 10_000L, 1_000_000_000L, 50, 6400));
    assertEquals("P", ExposureMath.modeLabel(0, 0));
    assertEquals("S", ExposureMath.modeLabel(4_000_000L, 0));
    assertEquals("ISO", ExposureMath.modeLabel(0, 400));
    assertEquals("M", ExposureMath.modeLabel(4_000_000L, 400));

    // Shutter priority: 1/250s (4 ms) needs ISO 400 at 0 EV, ISO 800 at +1 EV
    ExposureMath.Plan s0 =
        ExposureMath.planStill(4_000_000L, 0, 0.0, 0.0, neutral, 10_000L, 1_000_000_000L, 50, 6400);
    assertNotNull(s0);
    assertEquals("shutter priority", s0.mode);
    assertEquals(4_000_000L, s0.shutterNs);
    assertEquals(400, s0.iso);
    assertFalse(s0.outOfRange);

    ExposureMath.Plan sPlus1 =
        ExposureMath.planStill(4_000_000L, 0, 1.0, -2.0, neutral, 10_000L, 1_000_000_000L, 50, 6400);
    assertEquals(800, sPlus1.iso);
    assertEquals("DR400 -2 EV headroom lowers capture ISO without changing shutter", 200, sPlus1.captureIso);

    // ISO priority: ISO 50 needs 32 ms (1/30s) at 0 EV
    ExposureMath.Plan isoP =
        ExposureMath.planStill(0, 50, 0.0, 0.0, neutral, 10_000L, 1_000_000_000L, 50, 6400);
    assertNotNull(isoP);
    assertEquals("ISO priority", isoP.mode);
    assertEquals(32_000_000L, isoP.shutterNs);
    assertEquals(50, isoP.iso);

    // Manual: 1/1000s (1 ms) @ ISO 100 is -4 EV under neutral
    ExposureMath.Plan m =
        ExposureMath.planStill(1_000_000L, 100, 0.0, 0.0, neutral, 10_000L, 1_000_000_000L, 50, 6400);
    assertNotNull(m);
    assertEquals("manual", m.mode);
    assertEquals(-4.0, m.developEv, 1e-4);
  }

  @Test
  public void slowShutterPreviewCapsFrameDurationAndBoostsPreviewIso() {
    // 1" @ ISO 100 -> preview runs at 1/8s (125 ms) @ ISO 800 so preview brightness matches 1"
    long[] pv = ExposureMath.planPreview(1_000_000_000L, 100, 10_000L, 1_000_000_000L, 50, 6400);
    assertEquals(ExposureMath.MAX_PREVIEW_EXPOSURE_NS, pv[0]);
    assertEquals(800L, pv[1]);
    assertEquals(ExposureMath.MAX_PREVIEW_EXPOSURE_NS, pv[2]);

    // Fast shutter 1/500s (2 ms) @ ISO 400 -> preview runs at exact 2 ms @ ISO 400, 33.3 ms frame
    long[] fast = ExposureMath.planPreview(2_000_000L, 400, 10_000L, 1_000_000_000L, 50, 6400);
    assertEquals(2_000_000L, fast[0]);
    assertEquals(400L, fast[1]);
    assertEquals(33_333_333L, fast[2]);
  }

  @Test
  public void closedLoopPreviewMeterConvergesTowardTrueSceneNeutral() {
    // Suppose true scene neutral is 20_000_000 * 400 = 8e9 ns*ISO, but seed was 1e9.
    // Preview applied 10_000_000 * 200 = 2e9 (2 stops underexposed -> linear Y = 0.18 / 4 = 0.045).
    double est = 1.0e9;
    double applied = 2.0e9;
    for (int i = 0; i < 16; i++) {
      est = ExposureMath.refineSceneNeutral(est, applied, 0.045, 500_000.0, 1.0e13);
    }
    assertEquals(8.0e9, est, 8.0e9 * 0.03);

    int[] midGrey = new int[48 * 64];
    Arrays.fill(midGrey, 0xff777777); // sRGB ~119 -> linear ~0.184
    double y = ExposureMath.meanLinearLuma(midGrey, 48, 64);
    assertEquals(0.184, y, 0.01);
  }

  @Test
  public void meterPreservesSceneNeutralDuringManualPreviewAndSnapshotClampsFlash() {
    GrainCamera.Meter manualMeter =
        new GrainCamera.Meter(1_000_000L, 100, 0.0, 4, 0, false, 1.2f, null, 6_400_000_000.0);
    assertEquals(6_400_000_000.0, manualMeter.neutralNsIso(), 1.0);

    GrainCamera.Snapshot s =
        new GrainCamera.Snapshot(0, 80, 1.0, "3:4", true, 0f, null, 4_000_000L, 200, 99);
    assertEquals(ExposureMath.FLASH_TORCH, s.flashMode);
    assertEquals("torch", ExposureMath.FLASH_RECEIPT[s.flashMode]);
  }

  @Test
  public void focusLeverAnalogMathAndHysteresis() {
    // Inside deadzone -> zero velocity, non-zero smooth visual cap offset
    float[] dz = FocusMath.leverResponse(3f, 0f, 30f, 0.13f);
    assertEquals(0f, dz[0], 1e-6f);
    assertEquals(0f, dz[4], 1e-6f);
    assertTrue(dz[2] > 0.09f);

    // Diagonal 45-degree full deflection -> unit speed split equally across X and Y
    float[] diag = FocusMath.leverResponse(30f, -30f, 30f, 0.13f);
    assertEquals(1.0f, diag[4], 1e-4f);
    assertEquals(Math.sqrt(0.5), diag[0], 1e-3);
    assertEquals(-Math.sqrt(0.5), diag[1], 1e-3);

    // Floating origin leash follows excessive thumb travel so direction reversal is instant
    float[] origin = FocusMath.leverFloatingOrigin(50f, 50f, 150f, 50f, 40f);
    assertEquals(110f, origin[0], 1e-4f);
    assertEquals(50f, origin[1], 1e-4f);

    // Glide step advances smoothly and clamps cleanly inside [0.06, 0.94]
    float[] glided = FocusMath.leverGlideStep(0.50f, 0.50f, 0.7f, -0.7f, 1f / 60f, 0.90f);
    assertTrue(glided[0] > 0.50f && glided[1] < 0.50f);
    float[] clamped = FocusMath.leverGlideStep(0.93f, 0.07f, 1f, -1f, 0.05f, 0.90f);
    assertEquals(0.94f, clamped[0], 1e-6f);
    assertEquals(0.06f, clamped[1], 1e-6f);

    // Cardinal hysteresis holds current axis across mild diagonal wobble and releases cleanly
    assertEquals(-1, FocusMath.leverCardinal(0.15f, 0.05f, 0.32f, -1));
    assertEquals(2, FocusMath.leverCardinal(0.50f, 0.10f, 0.32f, -1)); // RIGHT
    assertEquals(2, FocusMath.leverCardinal(0.45f, 0.48f, 0.32f, 2)); // stays RIGHT on mild diagonal
    assertEquals(3, FocusMath.leverCardinal(0.30f, 0.60f, 0.32f, 2)); // switches DOWN when decisive
    assertEquals(3, FocusMath.leverCardinal(0.0f, 0.25f, 0.32f, 3)); // holds above release hysteresis
    assertEquals(-1, FocusMath.leverCardinal(0.0f, 0.18f, 0.32f, 3)); // releases cleanly
  }

  @Test
  public void playbackZoomMatrixFitsCenterAndClampsPan() {
    // 1x zoom fits 3000x4000 inside 900x1200 with scale 0.3 and zero pan
    float[] fit = FocusMath.playbackMatrixValues(900f, 1200f, 3000f, 4000f, 1.0f, 150f, -80f);
    assertEquals(0.30f, fit[0], 1e-5f);
    assertEquals(0f, fit[1], 1e-4f);
    assertEquals(0f, fit[2], 1e-4f);
    assertEquals(1.0f, fit[3], 1e-5f);
    assertEquals(0f, fit[4], 1e-5f);

    // 3x zoom -> scaled size 2700x3600, maxPanX = 900, maxPanY = 1200
    float[] zoomed = FocusMath.playbackMatrixValues(900f, 1200f, 3000f, 4000f, 3.0f, 2000f, -2000f);
    assertEquals(0.90f, zoomed[0], 1e-4f);
    assertEquals(3.0f, zoomed[3], 1e-5f);
    assertEquals(900f, zoomed[4], 1e-3f);
    assertEquals(-1200f, zoomed[5], 1e-3f);
  }

  @Test
  public void exportFilenamesIncludeRecipeNameInsteadOfBareNumbers() {
    assertEquals("Kodak_Portra_800_v3", PhotoStore.recipeFileSlug("Kodak Portra 800 v3"));
    assertEquals("Reggies_Portra", PhotoStore.recipeFileSlug("Reggie's Portra"));
    assertEquals("CineStill_800T", PhotoStore.recipeFileSlug("CineStill 800T"));
    assertEquals("Leica_X", PhotoStore.recipeFileSlug("Leica X"));

    String base =
        PhotoStore.exportBaseName("Kodak Portra 800 v3", 1762583422123L, "1762583422123-a1b2c3d4");
    assertTrue(base, base.startsWith("Grain_Kodak_Portra_800_v3_"));
    assertTrue(base, base.endsWith("_a1b2"));
  }

  @Test
  public void hiResMergerStacksSubPixelFramesAndTargets36Mp() {
    int[] dims = HiResMerger.targetDimensions(4000, 3000);
    assertEquals(0, dims[0] & 3);
    assertEquals(0, dims[1] & 3);
    long totalPx = (long) dims[0] * dims[1];
    assertTrue("Expected ~36MP, got " + totalPx, totalPx >= 35_500_000L && totalPx <= 36_500_000L);

    // Synthesize 4 Bayer RGGB frames (128x96 -> 256x192) with sub-pixel offsets & slanted edge
    int w = 128, h = 96;
    float[][] shifts = {{0f, 0f}, {0.45f, -0.30f}, {-0.35f, 0.50f}, {0.25f, 0.40f}};
    HiResMerger.Accumulator acc = null;
    for (int f = 0; f < 4; f++) {
      short[] raw = new short[w * h];
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
          float sx = x - shifts[f][0];
          float sy = y - shifts[f][1];
          // Smooth slanted edge + gentle gradient
          float edge = 1f / (1f + (float) Math.exp(-(sx - 64f + 0.25f * (sy - 48f)) * 1.2f));
          int c = (((y & 1) << 1) | (x & 1));
          float chGain = c == 0 ? 0.72f : (c == 3 ? 0.55f : 0.90f);
          float linear = (0.15f + 0.65f * edge) * chGain;
          raw[y * w + x] = (short) Math.round(64f + linear * (1023f - 64f));
        }
      }
      RawFrame rf = new RawFrame(raw, w, h, w);
      rf.cfa = RawFrame.RGGB;
      rf.noiseS = new double[] {1e-4, 1e-4, 1e-4, 1e-4};
      rf.noiseO = new double[] {1e-5, 1e-5, 1e-5, 1e-5};
      if (acc == null) acc = new HiResMerger.Accumulator(rf);
      acc.addFrame(rf);
    }
    assertEquals(4, acc.framesAdded());
    RawFrame merged = acc.finish();
    assertEquals(256, merged.width);
    assertEquals(192, merged.height);
    assertTrue(merged.noiseS[0] < 3.5e-5);
    assertTrue(acc.shiftsSummary().contains("f4:"));
  }

  private static Recipe findRecipe(String key) {
    for (Recipe r : Recipe.ALL) {
      if (r.fuji != null && key.equals(r.fuji.key)) return r;
    }
    throw new AssertionError("Missing recipe " + key);
  }
}
