package dev.grain.photo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FocusFeaturesTest {

  @Test
  public void bracketFractionsAreMonotonicAndBounded() {
    for (int count : FocusMath.BKT_COUNTS) {
      for (int step = 0; step < FocusMath.BKT_STEP_NAMES.length; step++) {
        for (float start : new float[] {0f, 0.03f, 0.25f, 0.5f, 0.85f, 0.99f, 1f}) {
          float[] plan = FocusMath.bracketFractions(start, count, step);
          assertEquals(count, plan.length);
          for (int i = 0; i < plan.length; i++) {
            assertTrue("in [0,1]", plan[i] >= 0f && plan[i] <= 1f);
            if (i > 0) {
              assertTrue("strictly increasing near->far or far->near", plan[i] > plan[i - 1]);
            }
          }
        }
      }
    }
    float[] centered = FocusMath.bracketFractions(0.5f, 5, FocusMath.BKT_STEP_MEDIUM);
    assertEquals(0.5f, centered[2], 1e-4f);
  }

  @Test
  public void stickyFaceTrackingMaintainsSubjectInsteadOfJumping() {
    float[] twoFaces = {
      0.16f, 0.28f, 0.34f, 0.52f, // face 0 on left (previously tracked)
      0.44f, 0.35f, 0.56f, 0.55f // face 1 near frame center
    };
    int[] noIds = {-1, -1};
    // Initial acquisition with no prior lock picks the central face (index 1).
    int initial = FocusMath.pickTrackedFace(twoFaces, 2, noIds, -1, 0.5f, 0.5f, false);
    assertEquals(1, initial);

    // Sticky tracking keeps face 0 when previously locked at (0.24, 0.40).
    int sticky = FocusMath.pickTrackedFace(twoFaces, 2, noIds, -1, 0.24f, 0.40f, true);
    assertEquals(0, sticky);

    // Hardware face ID match wins immediately when present.
    int[] withIds = {42, 99};
    int byId = FocusMath.pickTrackedFace(twoFaces, 2, withIds, 42, 0.80f, 0.80f, true);
    assertEquals(0, byId);
  }

  @Test
  public void smoothBoxAndMovedEnoughBehaveCleanly() {
    float[] a = {0.20f, 0.30f, 0.40f, 0.50f};
    float[] b = {0.205f, 0.305f, 0.405f, 0.505f};
    float[] c = {0.32f, 0.30f, 0.52f, 0.50f};
    assertFalse(FocusMath.movedEnough(a, b, 0.025f));
    assertTrue(FocusMath.movedEnough(a, c, 0.025f));
    float[] s = FocusMath.smoothBox(a, c, 0.5f);
    assertEquals(0.26f, s[0], 1e-4f);
    assertEquals(0.46f, s[2], 1e-4f);
  }

  @Test
  public void sensorToUprightAndPointRoundTripAcrossOrientations() {
    for (int rot : new int[] {0, 90, 180, 270}) {
      for (boolean front : new boolean[] {false, true}) {
        double ux = 0.31, uy = 0.64;
        double[] sensorPt = FocusMath.uprightPointToSensor(ux, uy, rot, front);
        float eps = 0.02f;
        double[] uprightBox =
            FocusMath.sensorBoxToUpright(
                sensorPt[0] - eps,
                sensorPt[1] - eps,
                sensorPt[0] + eps,
                sensorPt[1] + eps,
                rot,
                front);
        float cx = (float) ((uprightBox[0] + uprightBox[2]) * 0.5);
        float cy = (float) ((uprightBox[1] + uprightBox[3]) * 0.5);
        assertEquals("rot=" + rot + " front=" + front, (float) ux, cx, 1e-3f);
        assertEquals("rot=" + rot + " front=" + front, (float) uy, cy, 1e-3f);
      }
    }
  }

  @Test
  public void uprightBoxToOsdClipsAndRotatesIntoLandscape() {
    double[] upright = {0.35, 0.25, 0.65, 0.55};
    float[] portrait = FocusMath.uprightBoxToOsd(upright, 3060, 4080, "3:4", 1.0, 0);
    assertNotNull(portrait);
    assertEquals(0.35f, portrait[0], 1e-3f);
    assertEquals(0.25f, portrait[1], 1e-3f);

    float[] landscape = FocusMath.uprightBoxToOsd(upright, 3060, 4080, "3:4", 1.0, 90);
    assertNotNull(landscape);
    assertTrue(landscape[2] > landscape[0] && landscape[3] > landscape[1]);

    // A face completely outside a 3x zoomed crop returns null so no ghost box is drawn.
    double[] offscreen = {0.01, 0.01, 0.08, 0.08};
    assertNull(FocusMath.uprightBoxToOsd(offscreen, 3060, 4080, "1:1", 3.0, 0));
  }

  @Test
  public void magnifierBoxStaysInsideImageBounds() {
    for (float fx : new float[] {0f, 0.08f, 0.5f, 0.94f, 1f}) {
      for (float fy : new float[] {0f, 0.08f, 0.5f, 0.94f, 1f}) {
        float[] m = FocusMath.magnifierBox(40f, 100f, 600f, 800f, fx, fy, 12f);
        assertTrue(m[0] >= 40f && m[1] >= 100f);
        assertTrue(m[2] <= 640f && m[3] <= 900f);
        assertEquals(m[2] - m[0], m[3] - m[1], 1e-2f);
      }
    }
  }
}
