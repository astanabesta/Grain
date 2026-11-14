package dev.grain.photo;

import static org.junit.Assert.*;

import dev.grain.photo.develop.LiveLook;
import org.junit.Test;

/**
 * The GPU viewfinder path samples the baked 3D LUT with blue as the fastest axis and texel-center
 * sampling; the bake must be lossless-identical to the develop-side preview transform per texel.
 */
public class LiveLookTest {

  @Test
  public void noLutNoRecipeIsIdentity() {
    LiveLook.Look look = LiveLook.bake(null, null);
    assertEquals(LiveLook.SIZE, look.size);
    int n = look.size;
    for (int b = 0; b < n; b++)
      for (int g = 0; g < n; g++)
        for (int r = 0; r < n; r++) {
          int i = ((b * n) + g) * n + r; // blue fastest, matching the shader's grid walk
          int R = r * 255 / (n - 1), G = g * 255 / (n - 1), B = b * 255 / (n - 1);
          assertEquals(R, look.rgb[i * 3] & 0xff);
          assertEquals(G, look.rgb[i * 3 + 1] & 0xff);
          assertEquals(B, look.rgb[i * 3 + 2] & 0xff);
        }
  }

  @Test
  public void cornersSurviveJustDecodeBoundsStylePrecision() {
    LiveLook.Look look = LiveLook.bake(null, null);
    int n = look.size;
    // pure black and pure white corners stay exact (they anchor the shader interpolation range)
    assertEquals(0, look.rgb[0] & 0xff);
    int last = (n * n * n - 1) * 3;
    assertEquals(255, look.rgb[last] & 0xff);
    assertEquals(255, look.rgb[last + 1] & 0xff);
    assertEquals(255, look.rgb[last + 2] & 0xff);
  }
}
