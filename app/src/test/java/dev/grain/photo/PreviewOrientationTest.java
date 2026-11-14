package dev.grain.photo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * The GPU viewfinder must sample exactly the same camera texels as the TextureView fallback,
 * including all four display rotations and zoom levels. HWUI draws a SurfaceTexture layer with UV
 * quad Rect(0, 1, 1, 0) — (0,0) is bottom-left in GL convention — so the GL quad's corner UVs
 * must be (0,1),(1,1),(1,0),(0,0). Binding (0,0) to the top-left corner rotates/mirrors the
 * preview (v0.9.37–v0.9.39 bug: upside-down viewfinder in portrait, motion reversed).
 */
public class PreviewOrientationTest {

  // AOSP GLConsumer::computeTransformMatrix with NATIVE_WINDOW_TRANSFORM_ROT_90:
  // mtxFlipV * mtxRot90 => (s,t) -> (1-t, 1-s). Column-major, like glUniformMatrix4fv.
  private static final float[] M_ROT90 = {
    0, -1, 0, 0,
    -1, 0, 0, 0,
    0, 0, 1, 0,
    1, 1, 0, 1
  };
  // Front camera stream: mtxFlipV * mtxFlipH => (s,t) -> (1-s, 1-t).
  private static final float[] M_FRONT = {
    -1, 0, 0, 0,
    0, -1, 0, 0,
    0, 0, 1, 0,
    1, 1, 0, 1
  };
  private static final float[] M_IDENTITY = {
    1, 0, 0, 0,
    0, 1, 0, 0,
    0, 0, 1, 0,
    0, 0, 0, 1
  };

  @Test
  public void glQuadSamplesTheSameTexelsAsTextureViewAtEveryRotationAndZoom() {
    int w = 300, h = 400;
    float[][] points = {
      {w * .5f, h * .5f}, {0, 0}, {w, 0}, {w, h}, {0, h},
      {w * .2f, h * .3f}, {w * .8f, h * .7f}
    };
    for (int rot : new int[] {0, 90, 180, 270}) {
      for (double zoom : new double[] {1, 2}) {
        float[] dst = PreviewMath.destinationCorners(w, h, 1440, 1080, 90, rot, zoom);
        for (float[] p : points) {
          float[] ab = inverseAffine(dst, w, h, p[0], p[1]);
          // TextureView: quad (0,0,w,0,w,h,0,h) with HWUI UV quad Rect(0,1,1,0).
          float[] expected = {ab[0], 1f - ab[1]};
          float[] actual = glQuadUv(LivePreviewRenderer.QUAD_UV, ab);
          assertArrayEquals("UV mismatch at rotation " + rot + " zoom " + zoom, expected, actual, 1e-5f);
          for (float[] m : new float[][] {M_IDENTITY, M_ROT90, M_FRONT}) {
            assertArrayEquals(
                "buffer sample mismatch at rotation " + rot,
                sample(m, expected), sample(m, actual), 1e-5f);
          }
        }
      }
    }
  }

  @Test
  public void theOldTopLeftOriginQuadWouldHaveFailedThisTest() {
    float[] wrong = {0, 0, 1, 0, 1, 1, 0, 1};
    float[] ab = {0.25f, 0.75f};
    float[] expected = {ab[0], 1f - ab[1]};
    float[] wrongUv = glQuadUv(wrong, ab);
    assertFalse("a top-left-origin quad must be rejected by the mapping test",
        Math.abs(expected[1] - wrongUv[1]) < 1e-5f);
  }

  /** Where a screen point falls inside the source rect, as fractions (x/w, y/h). */
  private static float[] inverseAffine(float[] dst, int w, int h, float px, float py) {
    float ax = dst[2] - dst[0], ay = dst[3] - dst[1]; // TL -> TR
    float bx = dst[6] - dst[0], by = dst[7] - dst[1]; // TL -> BL
    float dx = px - dst[0], dy = py - dst[1];
    float det = ax * by - ay * bx;
    return new float[] {(dx * by - dy * bx) / det, (ax * dy - ay * dx) / det};
  }

  /** UV at (a,b) inside the GL quad (affine interpolation of the four corner UVs). */
  private static float[] glQuadUv(float[] q, float[] ab) {
    return new float[] {
      q[0] + ab[0] * (q[2] - q[0]) + ab[1] * (q[6] - q[0]),
      q[1] + ab[0] * (q[3] - q[1]) + ab[1] * (q[7] - q[1])
    };
  }

  private static float[] sample(float[] m, float[] uv) {
    float s = uv[0], t = uv[1];
    return new float[] {m[0] * s + m[4] * t + m[12], m[1] * s + m[5] * t + m[13]};
  }
}
