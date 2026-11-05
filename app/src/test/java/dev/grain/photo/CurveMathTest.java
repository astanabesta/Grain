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
}
