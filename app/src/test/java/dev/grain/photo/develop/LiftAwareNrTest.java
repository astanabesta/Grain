package dev.grain.photo.develop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Lift-aware NR (iPhone looks): the raw-stage noise model is built on the identity FLog2 code, but
 * the iPhone tone map (+ tuner) amplifies shadows downstream. The tables near mid-grey must sit at
 * 1 (current behaviour pinned), deep-shadow bins must get a clear boost, highlights little to
 * none, chroma at least as strong as luma, and tuner tone edits must move the tables along.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LiftAwareNrTest {

  private static ToneModel.Curve appleCurve() {
    return ToneModel.curve(0, 0, 0, false, 1, false); // the exact curve the apple code path builds
  }

  private static float[][] tables(AppleTune tune) {
    ToneModel.Curve curve = appleCurve();
    AppleRender.ToneTable tone =
        new AppleRender.ToneTable(AppleRender.Style.XS_MAX_WARM, 1.2, 0, 0);
    return RawDeveloper.liftTables(
        curve,
        tone,
        AppleRender.Style.XS_MAX_WARM,
        0,
        false,
        tune == null ? null : new AppleTune.Plan(tune));
  }

  private static int bin(ToneModel.Curve curve, double x) {
    float ti = (float) RawDeveloper.toneCode(curve, x) * RawDeveloper.NR_TABLE;
    int t = (int) ti;
    return Math.max(0, Math.min(RawDeveloper.NR_TABLE, t));
  }

  @Test
  public void shadowZonesGetTheBoostMidtonesStayNeutral() {
    ToneModel.Curve curve = appleCurve();
    float[][] lt = tables(null);
    assertNotNull(lt);
    float[] luma = lt[0], chroma = lt[1];
    assertEquals(RawDeveloper.NR_TABLE + 1, luma.length);
    int deep = bin(curve, 0.005), mid = bin(curve, 0.18), high = bin(curve, 0.5);
    // mid-grey is the reference: weight 1 (no behaviour change in zones at/above it)
    assertTrue("mid luma weight " + luma[mid], luma[mid] >= 1f && luma[mid] <= 1.25f);
    // XS Max Warm lifts deep shadows hard: NR must follow
    assertTrue(
        "shadow luma boost " + luma[deep] + " vs mid " + luma[mid], luma[deep] >= 1.5f * luma[mid]);
    assertTrue(luma[deep] >= 1.5f);
    // compressed/highlight zones never lose the NR they had today
    assertTrue("highlight " + luma[high], luma[high] >= 0.95f && luma[high] <= luma[deep]);
    // broad monotonicity: weight falls from shadows to highlights
    assertTrue(luma[bin(curve, 0.02)] >= luma[bin(curve, 0.2)] - 1e-3f);
    assertTrue(luma[bin(curve, 0.2)] >= luma[bin(curve, 0.6)] - 1e-3f);
    // caps respected everywhere
    for (int t = 0; t <= RawDeveloper.NR_TABLE; t++) {
      assertTrue(luma[t] >= 1f && luma[t] <= RawDeveloper.LIFT_NR_LUMA_MAX + 1e-4f);
      assertTrue(chroma[t] >= 1f && chroma[t] <= RawDeveloper.LIFT_NR_CHROMA_MAX + 1e-4f);
    }
    // chroma shadows get at least as much denoise as luma shadows (blotches read worse than grain)
    assertTrue(chroma[deep] >= luma[deep]);
  }

  @Test
  public void tunerEditsMoveTheWeights() {
    // x = 0.01 (a true shadow, below the cap zone): Shadows+ lifts L additively which flattens the
    // slope, so the NR weight follows it down; Shadows- does the opposite. Measured margins are
    // ~0.5, asserts keep 0.2-0.3 of headroom.
    ToneModel.Curve curve = appleCurve();
    AppleTune up = new AppleTune();
    up.shadows = 40;
    AppleTune down = new AppleTune();
    down.shadows = -40;
    int zone = bin(curve, 0.01);
    float wNull = tables(null)[0][zone];
    float wUp = tables(up)[0][zone];
    float wDown = tables(down)[0][zone];
    assertTrue("shad+40 " + wUp + " < null " + wNull, wUp + 0.2f <= wNull);
    assertTrue("shad-40 " + wDown + " > null " + wNull, wDown >= wNull + 0.3f);
    assertTrue(wDown > wUp);
  }

  @Test
  public void defaultTunerMatchesNoTuner() {
    float[][] none = tables(null);
    float[][] def = tables(new AppleTune());
    assertArrayEquals(none[0], def[0], 0.02f);
    assertArrayEquals(none[1], def[1], 0.02f);
  }

  @Test
  public void nonAppleHasNoTables() {
    // belt & braces for the guard: liftTables is only meaningful with an iPhone tone table, and
    // the Job keeps the arrays null for Fuji recipes so every eps site defaults to weight 1.
    assertTrue(RawDeveloper.LIFT_NR_LUMA_MAX >= 1f);
    assertTrue(RawDeveloper.LIFT_NR_CHROMA_MAX >= RawDeveloper.LIFT_NR_LUMA_MAX);
  }
}
