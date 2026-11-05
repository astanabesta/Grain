package dev.grain.photo;

import static org.junit.Assert.*;

import org.junit.Test;

public class CurveMathTest {
  @Test
  public void allCurvesAreLegalAndMonotonic() {
    for (int r = 0; r < 4; r++)
      for (int c = 0; c < 3; c++)
        for (int s : new int[] {0, 25, 80, 100}) {
          float[] a = CurveMath.channel(r, c, s, 32);
          assertEquals(64, a.length);
          for (int i = 0; i < 32; i++) {
            assertTrue(Float.isFinite(a[i * 2 + 1]));
            assertTrue(a[i * 2 + 1] >= 0 && a[i * 2 + 1] <= 1);
            if (i > 0) {
              assertTrue(a[i * 2] > a[i * 2 - 2]);
              assertTrue(a[i * 2 + 1] >= a[i * 2 - 1]);
            }
          }
          assertEquals(0, a[0], 0);
          assertEquals(1, a[62], 0);
        }
  }

  @Test
  public void zeroStrengthUsesSameNeutralTransfer() {
    assertArrayEquals(CurveMath.channel(0, 0, 0, 16), CurveMath.channel(3, 2, 0, 16), 0);
  }

  @Test
  public void pointCountCanBeLimitedByCamera() {
    assertEquals(4, CurveMath.channel(0, 0, 80, 2).length);
  }
  private static float interpolate(float[] curve, float x) {
    for (int i = 2; i < curve.length; i += 2) {
      if (x <= curve[i]) {
        float t = (x - curve[i - 2]) / (curve[i] - curve[i - 2]);
        return curve[i - 1] + t * (curve[i + 1] - curve[i - 1]);
      }
    }
    return curve[curve.length - 1];
  }

  @Test
  public void afterhoursDoesNotCrushShadowToeByUndersampling() {
    // Independent reference: analytic Afterhours red response at linear input 0.01.
    // The old uniform-input curve yielded 0.05478 rather than approximately 0.08624.
    assertEquals(.086242, interpolate(CurveMath.channel(3, 0, 80, 32), .01f), .001);
  }

  @Test
  public void neutralTransferApproximatesSrgbAcrossShadows() {
    float[] curve = CurveMath.channel(0, 0, 0, 32);
    for (float x : new float[] {.0005f, .001f, .0031308f, .005f, .01f, .02f, .05f, .1f}) {
      double expected = x <= .0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1.0 / 2.4) - .055;
      assertEquals(expected, interpolate(curve, x), .002);
    }
  }

}
