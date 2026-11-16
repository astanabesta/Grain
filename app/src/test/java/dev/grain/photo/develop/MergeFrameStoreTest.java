package dev.grain.photo.develop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The private RAW spool must round-trip pixels AND calibration metadata exactly. */
public class MergeFrameStoreTest {

  private static final int W = 64, H = 48;

  private static RawFrame sample() {
    short[] data = new short[W * H];
    for (int y = 0; y < H; y++)
      for (int x = 0; x < W; x++) data[y * W + x] = (short) ((x * 7 + y * 131) & 0x1fff);
    RawFrame f = new RawFrame(data, W, H, W);
    f.cfa = RawFrame.GRBG;
    f.black[0] = 63f;
    f.black[1] = 64f;
    f.black[2] = 65f;
    f.black[3] = 66f;
    f.white = 4095f;
    f.neutral = new double[] {0.9, 1.0, 1.1};
    f.colorMatrix1 = new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    f.colorMatrix2 = new double[] {0.8, 0.1, 0, 0, 0.9, 0, 0, 0, 1.2};
    f.forwardMatrix1 = new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    f.calibration1 = new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    f.noiseS = new double[] {1e-4, 1.1e-4, 1.2e-4, 1.3e-4};
    f.noiseO = new double[] {1e-6, 0, 0, 0};
    f.illuminant1 = 21;
    f.illuminant2 = 55;
    f.iso = 400;
    f.exposureNs = 12_345_678L;
    f.stackCount = 3;
    f.shadingColumns = 2;
    f.shadingRows = 2;
    f.shadingLeft = 4;
    f.shadingTop = 6;
    f.shadingWidth = W;
    f.shadingHeight = H;
    f.shading = new float[] {1, 1, 1, 1, 1.1f, 1.1f, 1.1f, 1.1f, 1, 1, 1, 1, 1, 1, 1, 1};
    f.faces = new int[] {1, 2, 30, 40};
    f.faceScores = new int[] {90};
    f.faceSource = "camera";
    f.faceEyes = 1;
    f.faceIds = 1;
    f.faceMouths = 0;
    return f;
  }

  @Test
  public void spoolRoundTripsPixelsAndMetadata() throws Exception {
    File dir = Files.createTempDirectory("grain-merge-spool").toFile();
    try {
      RawFrame original = sample();
      File file = MergeFrameStore.write(dir, 2, original);
      assertTrue(file.isFile());
      MergeFrameStore.Reader r = MergeFrameStore.open(file);
      try {
        assertEquals(W, r.width());
        assertEquals(H, r.height());
        assertEquals(RawFrame.GRBG, r.cfa());
        assertArrayEquals(original.black, r.black(), 0f);
        assertEquals(original.white, r.white(), 0f);
        short[] all = r.readAll();
        assertArrayEquals(original.data, all);
        short[] rows = new short[5 * W];
        r.readRows(10, 15, rows);
        for (int i = 0; i < rows.length; i++)
          assertEquals("row " + (10 + i / W), original.data[10 * W + i], rows[i]);

        RawFrame rebuilt = new RawFrame(all, W, H, W);
        r.copyMetadataTo(rebuilt);
        assertEquals(original.cfa, rebuilt.cfa);
        assertArrayEquals(original.black, rebuilt.black, 0f);
        assertEquals(original.white, rebuilt.white, 0f);
        assertArrayEquals(original.neutral, rebuilt.neutral, 0.0);
        assertArrayEquals(original.colorMatrix2, rebuilt.colorMatrix2, 0.0);
        assertArrayEquals(original.noiseS, rebuilt.noiseS, 0.0);
        assertEquals(original.iso, rebuilt.iso);
        assertEquals(original.exposureNs, rebuilt.exposureNs);
        assertEquals(original.stackCount, rebuilt.stackCount);
        assertArrayEquals(original.shading, rebuilt.shading, 0f);
        assertArrayEquals(original.faces, rebuilt.faces);
        assertEquals(original.faceSource, rebuilt.faceSource);
      } finally {
        r.close();
      }
    } finally {
      MergeFrameStore.deleteDir(dir);
    }
  }

  @Test
  public void panoFromSpoolMatchesInMemoryStitch() throws Exception {
    File dir = Files.createTempDirectory("grain-merge-pano").toFile();
    try {
      // Same smooth global scene as MergeMergerTest, but small enough for a fast JVM test.
      List<RawFrame> frames = new ArrayList<>();
      for (int o : new int[] {0, 128, 256, 384}) frames.add(smallScene(o));
      MergeMerger.Result inMemory = MergeMerger.pano(frames);

      List<MergeMerger.FrameSource> sources = new ArrayList<>();
      for (int i = 0; i < frames.size(); i++)
        sources.add(MergeFrameStore.open(MergeFrameStore.write(dir, i, frames.get(i))));
      MergeMerger.Result spooled = MergeMerger.panoSources(sources);

      assertEquals(inMemory.framesUsed, spooled.framesUsed);
      assertEquals(inMemory.frame.width, spooled.frame.width);
      assertEquals(inMemory.frame.height, spooled.frame.height);
      assertArrayEquals(inMemory.offsetX, spooled.offsetX);
      assertArrayEquals(inMemory.offsetY, spooled.offsetY);
      assertArrayEquals(inMemory.frame.data, spooled.frame.data);
      assertNotNull(spooled.frame.colorMatrix2 == null ? spooled.frame.colorMatrix1 : spooled.frame.colorMatrix2);
    } finally {
      MergeFrameStore.deleteDir(dir);
    }
  }

  private static final int SW = 256, SH = 192;

  private static int hash(int a, int b) {
    int h = (a * 73856093) ^ (b * 19349663);
    h ^= h >>> 13;
    return h & 255;
  }

  private static short smallScene(int gx, int gy) {
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

  private static RawFrame smallScene(int offsetX) {
    short[] data = new short[SW * SH];
    for (int y = 0; y < SH; y++)
      for (int x = 0; x < SW; x++) data[y * SW + x] = smallScene(x + offsetX, y);
    RawFrame f = new RawFrame(data, SW, SH, SW);
    java.util.Arrays.fill(f.black, 0f);
    f.white = 8191f;
    f.colorMatrix1 = new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    f.noiseS = new double[] {1e-4, 1e-4, 1e-4, 1e-4};
    f.noiseO = new double[] {1e-6, 1e-6, 1e-6, 1e-6};
    return f;
  }
}
