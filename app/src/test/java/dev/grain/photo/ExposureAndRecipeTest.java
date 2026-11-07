package dev.grain.photo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.grain.photo.develop.FujiRecipe;
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

  private static Recipe findRecipe(String key) {
    for (Recipe r : Recipe.ALL) {
      if (r.fuji != null && key.equals(r.fuji.key)) return r;
    }
    throw new AssertionError("Missing recipe " + key);
  }
}
