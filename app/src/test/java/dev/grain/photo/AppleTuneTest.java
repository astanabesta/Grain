package dev.grain.photo;

import static org.junit.Assert.*;

import dev.grain.photo.develop.AppleRender;
import dev.grain.photo.develop.AppleTune;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Pins the iPhone editor tuner's semantics. Every assertion is relative: the same pixel graded
 * with and without the tune, so the style's own grading never confuses what a slider promises.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AppleTuneTest {

  private static float[] grade(float r, float g, float b, AppleTune.Plan plan) {
    float[] px = {r, g, b};
    AppleRender.gradeDisplayP3(px, AppleRender.Style.XS_MAX_WARM, 0, false, plan);
    return px;
  }

  private static float sat(float[] c) {
    float mx = Math.max(c[0], Math.max(c[1], c[2]));
    float mn = Math.min(c[0], Math.min(c[1], c[2]));
    return mx < 1e-6f ? 0f : (mx - mn) / mx;
  }

  private static float luma(float[] c) {
    return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
  }

  private static AppleTune.Plan planOf(java.util.function.Consumer<AppleTune> fill) {
    AppleTune t = new AppleTune();
    fill.accept(t);
    return new AppleTune.Plan(t);
  }

  @Test
  public void defaultTuneIsBitIdenticalToNoTune() {
    AppleTune.Plan empty = new AppleTune.Plan(new AppleTune());
    float[] a = grade(0.30f, 0.22f, 0.15f, empty);
    float[] px = {0.30f, 0.22f, 0.15f};
    AppleRender.gradeDisplayP3(px, AppleRender.Style.XS_MAX_WARM, 0, false, null);
    assertArrayEquals(px, a, 1e-4f);
    assertEquals(1f, empty.expoGain, 1e-6f);
    assertEquals(1f, empty.satK, 1e-6f);
    assertEquals(0f, empty.vibK, 1e-6f);
  }

  @Test
  public void vibranceBoostsMutedMostAndProtectsSkinWhileSaturationIsUniform() {
    // Muted blue-grey, vivid red, realistic skin — all in linear P3 codes
    float[] muted = {0.10f, 0.12f, 0.17f}, vivid = {0.38f, 0.05f, 0.05f}, skin = {0.567f, 0.30f, 0.187f};

    AppleTune.Plan vib = planOf(t -> t.vibrance = 70);
    float vibMuted = sat(grade(muted[0], muted[1], muted[2], vib)) / sat(grade(muted[0], muted[1], muted[2], null));
    float vibVivid = sat(grade(vivid[0], vivid[1], vivid[2], vib)) / sat(grade(vivid[0], vivid[1], vivid[2], null));
    float vibSkin = sat(grade(skin[0], skin[1], skin[2], vib)) / sat(grade(skin[0], skin[1], skin[2], null));
    assertTrue("vibrance wakes muted colours first: " + vibMuted, vibMuted > 1.05f);
    assertTrue("vibrance leaves already-vivid colours alone: " + vibVivid, vibVivid < vibMuted - 0.03f);
    assertTrue("vibrance protects skin: " + vibSkin + " vs muted " + vibMuted, vibSkin < vibMuted - 0.03f);

    AppleTune.Plan satP = planOf(t -> t.saturation = 70);
    float satMuted = sat(grade(muted[0], muted[1], muted[2], satP)) / sat(grade(muted[0], muted[1], muted[2], null));
    float satVivid = sat(grade(vivid[0], vivid[1], vivid[2], satP)) / sat(grade(vivid[0], vivid[1], vivid[2], null));
    assertTrue(
        "saturation is (near-)uniform, unlike vibrance: muted " + satMuted + " vs vivid " + satVivid,
        Math.abs(satMuted - satVivid) < Math.max(0.06f, (vibMuted - vibVivid)));
  }

  @Test
  public void toneZonesActWhereNamed() {
    float[] dark = {0.02f, 0.02f, 0.02f}, mid = {0.18f, 0.18f, 0.18f}, bright = {0.62f, 0.62f, 0.62f};

    AppleTune.Plan shadowsUp = planOf(t -> t.shadows = 80);
    float dSh = luma(grade(dark[0], dark[1], dark[2], shadowsUp)) - luma(grade(dark[0], dark[1], dark[2], null));
    float bSh = luma(grade(bright[0], bright[1], bright[2], shadowsUp)) - luma(grade(bright[0], bright[1], bright[2], null));
    assertTrue("Shadows lifts dark tones", dSh > 0.004f);
    assertTrue("Shadows leaves bright tones alone", Math.abs(bSh) < dSh * 0.25f);

    AppleTune.Plan highUp = planOf(t -> t.highlights = 80);
    float bHi = luma(grade(bright[0], bright[1], bright[2], highUp)) - luma(grade(bright[0], bright[1], bright[2], null));
    float dHi = luma(grade(dark[0], dark[1], dark[2], highUp)) - luma(grade(dark[0], dark[1], dark[2], null));
    assertTrue("Highlights lifts bright tones", bHi > 0.004f);
    assertTrue("Highlights leaves dark tones alone", Math.abs(dHi) < bHi * 0.25f);

    float[] low = {0.004f, 0.004f, 0.004f}; // true shadow L≈0.19 — where Lightness shows itself
    AppleTune.Plan bri = planOf(t -> t.brightness = 60);
    AppleTune.Plan lit = planOf(t -> t.lightness = 60);
    float briLow = luma(grade(low[0], low[1], low[2], bri)) - luma(grade(low[0], low[1], low[2], null));
    float litLow = luma(grade(low[0], low[1], low[2], lit)) - luma(grade(low[0], low[1], low[2], null));
    float briMid = luma(grade(mid[0], mid[1], mid[2], bri)) - luma(grade(mid[0], mid[1], mid[2], null));
    float litMid = luma(grade(mid[0], mid[1], mid[2], lit)) - luma(grade(mid[0], mid[1], mid[2], null));
    assertTrue("Brightness is mid-tone-weighted", briMid > briLow);
    assertTrue("Lightness is airier and reaches shadows more than brightness does", litLow > briLow);
    assertTrue("and brightness still wins in the mid-tones", briMid > litMid);

    AppleTune.Plan con = planOf(t -> t.contrast = 60);
    float dC = luma(grade(dark[0], dark[1], dark[2], con)) - luma(grade(dark[0], dark[1], dark[2], null));
    float bC = luma(grade(bright[0], bright[1], bright[2], con)) - luma(grade(bright[0], bright[1], bright[2], null));
    assertTrue("Contrast darkens darks", dC < -0.002f);
    assertTrue("Contrast brightens brights", bC > 0.002f);
  }

  @Test
  public void exposureIsTrueLinearEv() {
    AppleTune.Plan plus = planOf(t -> t.exposure = 100), minus = planOf(t -> t.exposure = -100);
    assertEquals(Math.pow(2, 1.3), plus.expoGain, 1e-4);
    assertEquals(Math.pow(2, -1.3), minus.expoGain, 1e-4);
    float[] g = grade(0.18f, 0.18f, 0.18f, planOf(t -> t.exposure = 40));
    float[] base = grade(0.18f, 0.18f, 0.18f, null);
    assertTrue("exposure brightens in linear light", luma(g) > luma(base) * 1.15f);
  }

  @Test
  public void curveIsMonotoneAndIdentityWhenDefault() {
    float[] xs = {0f, 0.3f, 0.72f, 1f}, ys = {0f, 0.42f, 0.66f, 1f};
    float prev = -1f;
    for (int i = 0; i <= 100; i++) {
      float y = AppleTune.curveAt(xs, ys, i / 100f);
      assertTrue("curve non-decreasing", y >= prev - 1e-5f);
      prev = y;
    }
    assertEquals(0.42f, AppleTune.curveAt(xs, ys, 0.3f), 1e-5f);
    assertEquals(0f, AppleTune.curveAt(new float[0], new float[0], 0f), 1e-6f);
    assertEquals(0.37f, AppleTune.curveAt(new float[0], new float[0], 0.37f), 1e-6f);

    AppleTune.Plan tone = new AppleTune.Plan(new AppleTune());
    prev = -1f;
    for (int i = 0; i <= 64; i++) {
      float y = tone.toneAt(i / 64f);
      assertTrue("tone LUT identity when neutral", Math.abs(y - i / 64f) < 0.01f);
      assertTrue("tone LUT non-decreasing", y >= prev);
      prev = y;
    }
  }

  @Test
  public void warmthAndTintMoveTheRightAxes() {
    float[] neutral = {0.18f, 0.18f, 0.18f};
    float[] warm = grade(neutral[0], neutral[1], neutral[2], planOf(t -> t.warmth = 60));
    float[] cool = grade(neutral[0], neutral[1], neutral[2], planOf(t -> t.warmth = -60));
    assertTrue("Warmth +60 shifts blue->yellow (R/B up)", warm[0] / warm[2] > cool[0] / cool[2] + 0.05f);

    float[] mag = grade(neutral[0], neutral[1], neutral[2], planOf(t -> t.tint = 60));
    float[] green = grade(neutral[0], neutral[1], neutral[2], planOf(t -> t.tint = -60));
    float magAxis = (mag[0] + mag[2]) * 0.5f - mag[1];
    float greenAxis = (green[0] + green[2]) * 0.5f - green[1];
    assertTrue("Tint +60 moves toward magenta", magAxis > greenAxis + 0.004f);
  }

  @Test
  public void persistenceRoundTripsAndDefaultsStayEmpty() throws Exception {
    AppleTune t = new AppleTune();
    t.exposure = 12;
    t.vibrance = 24;
    t.warmth = 5;
    t.shadows = -3;
    t.clarity = 33;
    t.curveX = new float[] {0f, 0.33f, 1f};
    t.curveY = new float[] {0f, 0.41f, 1f};
    AppleTune back = AppleTune.read(new org.json.JSONObject(t.write().toString()));
    assertTrue(t.sameSettings(back));
    assertFalse(back.isDefault());
    assertTrue(AppleTune.read(new org.json.JSONObject("{}")).isDefault());
    assertTrue(new AppleTune().sameSettings(new AppleTune()));
  }
}
