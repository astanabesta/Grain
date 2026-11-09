package dev.grain.photo;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import org.robolectric.RuntimeEnvironment;
import dev.grain.photo.GrainCamera.RawCapture;
import dev.grain.photo.develop.FujiRecipe;
import dev.grain.photo.develop.RawFrame;
import java.io.File;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * End-to-end develop smoke on a tiny synthetic RAW, through the real {@link RawDevelopment} EXIF /
 * encode stage. Born from a v0.9.17-wide regression: the stack label read {@code frame.stackCount}
 * after {@code frame = null} (the 225 MB release), so every RAW capture died at the EXIF stage.
 */
@RunWith(RobolectricTestRunner.class)
// Ultra HDR gainmaps need real Bitmaps; below API 34 production skips them, so the smoke runs at 33.
@Config(sdk = 33)
public class RawDevelopSmokeTest {

  private static FujiRecipe appleRecipe() {
    for (Recipe r : Recipe.ALL) if (r.raw && r.fuji.apple()) return r.fuji;
    throw new IllegalStateException("no iPhone recipe registered");
  }

  private static RawFrame tinyFrame() {
    int w = 192, h = 128;
    short[] data = new short[w * h];
    RawFrame f = new RawFrame(data, w, h, w);
    f.cfa = RawFrame.GRBG;
    Random rnd = new Random(20261110);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        double level = 0.03 + 0.25 * x / w + 0.15 * Math.sin(y * 0.05);
        int c = f.color(x, y);
        double k = c == 1 ? 1.0 : c == 0 ? 0.85 : 0.75;
        data[y * w + x] =
            (short) Math.max(0, Math.min(1023, Math.round(64 + level * k * (1023 - 64)
                + rnd.nextGaussian() * 3)));
      }
    f.neutral = new double[] {0.48, 1.0, 0.57};
    f.colorMatrix1 = RawEngineTest.PHONE_CM1;
    f.colorMatrix2 = RawEngineTest.PHONE_CM2;
    f.forwardMatrix1 = RawEngineTest.PHONE_FM1;
    f.forwardMatrix2 = RawEngineTest.PHONE_FM2;
    f.illuminant1 = 21;
    f.illuminant2 = 17;
    f.iso = 2742;
    return f;
  }

  @Test
  public void rawDevelopCompletesAndTagsTheStack() throws Exception {
    Context ctx = RuntimeEnvironment.getApplication();
    RawFrame f = tinyFrame();
    f.stackCount = 3;
    RawCapture capture = new RawCapture(f, null, null, 0, 0);
    RawDevelopment.Output out =
        RawDevelopment.develop(ctx, capture, appleRecipe(), false, null, null, 0f, 424242L,
            false, true);
    assertNotNull(out);
    File jpg = out.jpegFile;
    assertTrue(jpg.exists() && jpg.length() > 4000);
    androidx.exifinterface.media.ExifInterface exif =
        new androidx.exifinterface.media.ExifInterface(jpg);
    String desc = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_DESCRIPTION);
    assertNotNull(desc);
    assertTrue("EXIF description: " + desc, desc.contains("3-frame stack"));
    String stats = out.stats.toString();
    assertTrue(stats.length() > 40);
    //noinspection ResultOfMethodCallIgnored
    jpg.delete();
  }

  @Test
  public void plainSingleFramesStayUntagged() throws Exception {
    Context ctx = RuntimeEnvironment.getApplication();
    RawCapture capture = new RawCapture(tinyFrame(), null, null, 0, 0);
    RawDevelopment.Output out =
        RawDevelopment.develop(ctx, capture, appleRecipe(), false, null, null, 0f, 99L,
            false, true);
    File jpg = out.jpegFile;
    androidx.exifinterface.media.ExifInterface exif =
        new androidx.exifinterface.media.ExifInterface(jpg);
    String desc = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_DESCRIPTION);
    assertNotNull(desc);
    assertTrue("EXIF must not claim a stack: " + desc, !desc.contains("frame stack"));
    //noinspection ResultOfMethodCallIgnored
    jpg.delete();
  }
}
