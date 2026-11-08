package dev.grain.photo;

import static org.junit.Assert.*;

import org.junit.Test;

public class FramingTest {
  @Test
  public void squareCrop() {
    assertArrayEquals(new int[] {0, 510, 3060, 3060}, Framing.crop(3060, 4080, "1:1", 1));
  }

  @Test
  public void storyCrop() {
    int[] crop = Framing.crop(3060, 4080, "9:16", 1);
    assertEquals(4080, crop[3]);
    assertEquals(.5625, (double) crop[2] / crop[3], .0003);
  }

  @Test
  public void zoomUsesRealPixels() {
    int[] crop = Framing.crop(3060, 4080, "3:4", 2);
    assertArrayEquals(new int[] {765, 1020, 1530, 2040}, crop);
  }

  @Test
  public void noUpscaling() {
    int[] crop = Framing.crop(3060, 4080, "9:16", 5);
    assertTrue(crop[2] <= 3060);
    assertTrue(crop[3] <= 4080);
  }

  @Test
  public void sourceFocusMapping() {
    double[] p = Framing.sourcePoint(.5, .5, 3060, 4080, "1:1", 2);
    assertEquals(.5, p[0], .001);
    assertEquals(.5, p[1], .001);
  }

  @Test
  public void fitRespectsBothBounds() {
    double[] fit = Framing.fit(350, 466, "9:16");
    assertEquals(.5625, fit[0] / fit[1], .0001);
    assertTrue(fit[0] <= 350);
    assertTrue(fit[1] <= 466);
  }

  @Test
  public void rejectCorruptPrefs() {
    assertEquals("3:4", Framing.ratio("bad"));
    assertEquals(1, Framing.zoom(Double.NaN), 0);
    assertEquals(5, Framing.zoom(100), 0);
  }

  @Test
  public void sensorCropRectMapsUprightCropAcrossAllRotations() {
    // 112 MP sensor frame (12220 x 9164). Portrait (orientation 6 / 8) makes upright 9164 x 12220.
    int rawW = 12220, rawH = 9164;
    int[] sqPortrait = Framing.crop(rawH, rawW, "1:1", 1.0); // [0, 1528, 9164, 9164]
    assertArrayEquals(new int[] {0, 1528, 9164, 9164}, sqPortrait);

    int[] r6 = Framing.sensorCropRect(rawW, rawH, 6, sqPortrait);
    assertArrayEquals(new int[] {1528, 0, 9164, 9164}, r6);

    int[] r8 = Framing.sensorCropRect(rawW, rawH, 8, sqPortrait);
    assertArrayEquals(new int[] {1528, 0, 9164, 9164}, r8);

    // Asymmetric zoomed crop to verify non-symmetric corner mapping on 1, 3, 6, 8.
    int[] asymUp = new int[] {10, 20, 30, 40}; // inside upright 60 x 100 (raw 100 x 60 for 6/8)
    assertArrayEquals(new int[] {10, 20, 30, 40}, Framing.sensorCropRect(60, 100, 1, asymUp));
    assertArrayEquals(new int[] {20, 40, 30, 40}, Framing.sensorCropRect(60, 100, 3, asymUp));
    assertArrayEquals(new int[] {20, 20, 40, 30}, Framing.sensorCropRect(100, 60, 6, asymUp));
    assertArrayEquals(new int[] {40, 10, 40, 30}, Framing.sensorCropRect(100, 60, 8, asymUp));
    assertNull(Framing.sensorCropRect(100, 60, 2, asymUp)); // mirrored -> fallback path
  }
}
