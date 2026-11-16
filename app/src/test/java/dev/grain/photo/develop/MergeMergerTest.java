package dev.grain.photo.develop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** JVM tests for the MERGE drive modes: alignment, canvas coverage, seams, ghosts and super-res. */
public class MergeMergerTest {

  private static final int W = 512, H = 384;

  /** Smooth, deterministic low-frequency scene keyed by GLOBAL coordinates. Real photographs have
   * this kind of structure; pure per-pixel white noise would be averaged flat by the alignment
   * pyramids. */
  private static int hash(int a, int b) {
    int h = (a * 73856093) ^ (b * 19349663);
    h ^= h >>> 13;
    return h & 255;
  }

  private static short scene(int gx, int gy) {
    final int c = 16;
    int cx = Math.floorDiv(gx, c), cy = Math.floorDiv(gy, c);
    float fx = (gx - cx * c) / (float) c, fy = (gy - cy * c) / (float) c;
    fx = fx * fx * (3f - 2f * fx);
    fy = fy * fy * (3f - 2f * fy);
    float n00 = hash(cx, cy) / 255f, n10 = hash(cx + 1, cy) / 255f;
    float n01 = hash(cx, cy + 1) / 255f, n11 = hash(cx + 1, cy + 1) / 255f;
    float v = 0.20f + 0.60f * ((1 - fx) * (1 - fy) * n00 + fx * (1 - fy) * n10
        + (1 - fx) * fy * n01 + fx * fy * n11);
    return (short) Math.round(v * 8191f);
  }

  private static RawFrame frame(int offsetX, int offsetY) {
    short[] data = new short[W * H];
    for (int y = 0; y < H; y++)
      for (int x = 0; x < W; x++) data[y * W + x] = scene(x + offsetX, y + offsetY);
    RawFrame f = new RawFrame(data, W, H, W);
    Arrays.fill(f.black, 0f);
    f.white = 8191;
    f.cfa = RawFrame.RGGB;
    return f;
  }

  private static List<RawFrame> pan(int... offsets) {
    List<RawFrame> frames = new ArrayList<>();
    for (int o : offsets) frames.add(frame(o, 0));
    return frames;
  }

  @Test
  public void panoRecoversAKnownPanAndProducesAWiderFrame() {
    MergeMerger.Result r = MergeMerger.pano(pan(0, 256, 512, 768));
    assertEquals(4, r.framesUsed);
    for (int i = 0; i < 4; i++) {
      assertEquals("frame " + (i + 1) + " x offset", i * 256, r.offsetX[i], 8);
      assertEquals("frame " + (i + 1) + " y offset", 0, r.offsetY[i], 8);
      assertTrue("offsets must stay even to preserve Bayer phase", (r.offsetX[i] & 1) == 0);
    }
    assertEquals(W + 768, r.frame.width);
    assertEquals(H, r.frame.height);
    assertEquals(0f, r.frame.black[0], 0f);
    assertEquals(8191f, r.frame.white, 0f);
  }

  @Test
  public void panoCanvasHasNoUncoveredHoles() {
    MergeMerger.Result r = MergeMerger.pano(pan(0, 300, 600));
    short[] px = r.frame.data;
    for (int i = 0; i < px.length; i++)
      assertTrue("uncovered pixel at " + i, (px[i] & 0xffff) > 0);
  }

  @Test
  public void panoOverlapMatchesTheSceneInsteadOfShowingASeam() {
    MergeMerger.Result r = MergeMerger.pano(pan(0, 256));
    int canvasX = 380; // inside the overlap of both frames
    for (int y : new int[] {40, 120, 250, 340}) {
      int expected = scene(canvasX, y) & 0xffff;
      int actual = r.frame.data[y * r.frame.width + canvasX] & 0xffff;
      assertTrue("seam at y=" + y + " expected " + expected + " got " + actual,
          Math.abs(expected - actual) < 40);
    }
  }

  @Test
  public void panoMotionGateRejectsAMovingSubjectInsteadOfGhosting() {
    List<RawFrame> frames = pan(0, 256);
    RawFrame second = frames.get(1);
    // A bright blob only in frame 2, inside the region frame 1 also covers.
    for (int y = 90; y < 130; y++)
      for (int x = 90; x < 130; x++) second.data[y * W + x] = (short) 8191;
    MergeMerger.Result r = MergeMerger.pano(frames);
    int canvasX = 256 + 110, y = 110;
    int expected = scene(canvasX, y) & 0xffff;
    int actual = r.frame.data[y * r.frame.width + canvasX] & 0xffff;
    assertTrue("ghost leaked into the merge: expected " + expected + " got " + actual,
        Math.abs(expected - actual) < 60);
    assertTrue("gate should report rejections", r.gatedFraction > 0f);
  }

  @Test
  public void panoDropsFramesWithTooLittleOverlap() {
    MergeMerger.Result r = MergeMerger.pano(pan(0, 256, 900));
    assertTrue("an unregisterable frame must be dropped", r.framesUsed < 3);
    assertTrue(r.shifts.contains("rejected"));
  }

  @Test
  public void detailMergesFourFramesIntoALargerSuperResolvedFrame() {
    List<RawFrame> frames = new ArrayList<>();
    for (int i = 0; i < 4; i++) frames.add(frame(0, 0));
    MergeMerger.Result r = MergeMerger.detail(frames, 36_000_000L, 2.0);
    assertEquals(4, r.framesUsed);
    assertTrue("2x linear output expected", r.frame.width >= W * 2);
    assertTrue(r.frame.black[0] == 0f);
    assertTrue(r.frame.white > 1000f);
  }
}
