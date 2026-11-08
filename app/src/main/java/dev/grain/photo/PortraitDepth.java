package dev.grain.photo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import org.tensorflow.lite.Interpreter;

/**
 * Monocular relative-depth via a bundled int8-quantized MiDaS-small v2 TFLite model, fully offline
 * on the CPU (XNNPACK int8, ~6 threads). Input: letterboxed 256x256 RGB normalized with the ImageNet
 * mean/std the model was trained with. Output: 256x256 relative disparity (bigger = closer),
 * robust-normalized to [0,1] using the 2nd/98th percentiles so a few dip/peak outliers do not
 * collapse the defocus scale.
 */
final class PortraitDepth {
  private static final String TAG = "PortraitDepth";
  private static final String ASSET = "midas_depth.tflite";
  private static final int DS = 256;
  private static final int THREADS = 6;
  private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
  private static final float[] STD = {0.229f, 0.224f, 0.225f};

  private static Interpreter interpreter;
  private static boolean tried;

  private static Interpreter get(Context ctx) {
    if (!tried) {
      tried = true;
      try (android.content.res.AssetFileDescriptor fd = ctx.getAssets().openFd(ASSET);
          FileChannel ch = fd.createInputStream().getChannel()) {
        MappedByteBuffer buf =
            ch.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        Interpreter.Options opts = new Interpreter.Options();
        opts.setNumThreads(THREADS);
        interpreter = new Interpreter(buf, opts);
      } catch (IOException | RuntimeException e) {
        Log.w(TAG, "depth model unavailable: " + e.getMessage());
        interpreter = null;
      }
    }
    return interpreter;
  }

  /**
   * Computes the relative closeness map (0 far .. 1 near) on the upright quarter grid
   * ({@code quW x quH}). Null when the model is missing or the output is degenerate (variance too
   * small to be a real scene).
   */
  static float[] depthUprightQuarter(Context ctx, Bitmap upright, int quW, int quH) {
    if (upright == null) return null;
    try {
      Interpreter it = get(ctx);
      if (it == null) return null;

      float fit = Math.min(DS / (float) upright.getWidth(), DS / (float) upright.getHeight());
      int cw = Math.max(1, Math.round(upright.getWidth() * fit));
      int ch = Math.max(1, Math.round(upright.getHeight() * fit));
      int ox = (DS - cw) / 2, oy = (DS - ch) / 2;
      Bitmap box = Bitmap.createBitmap(DS, DS, Bitmap.Config.ARGB_8888);
      Canvas cv = new Canvas(box);
      cv.drawColor(Color.BLACK);
      Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
      cv.drawBitmap(upright, null, new Rect(ox, oy, ox + cw, oy + ch), paint);
      int[] pix = new int[DS * DS];
      box.getPixels(pix, 0, DS, 0, 0, DS, DS);
      box.recycle();

      float[][][][] in = new float[1][DS][DS][3];
      for (int y = 0; y < DS; y++) {
        int o = y * DS;
        for (int x = 0; x < DS; x++) {
          int p = pix[o + x];
          in[0][y][x][0] = ((((p >>> 16) & 255) / 255f) - MEAN[0]) / STD[0];
          in[0][y][x][1] = ((((p >>> 8) & 255) / 255f) - MEAN[1]) / STD[1];
          in[0][y][x][2] = (((p & 255) / 255f) - MEAN[2]) / STD[2];
        }
      }
      float[][] out = new float[1][DS * DS];
      it.run(in, out);
      float[] d = out[0];

      // Robust normalize: 2nd..98th percentile to [0,1], preserving "closer is bigger".
      int[] hist = new int[512];
      float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
      for (float v : d) {
        if (v < lo) lo = v;
        if (v > hi) hi = v;
      }
      if (!(hi - lo > 1e-5f)) return null;
      float span = hi - lo;
      for (float v : d) hist[(int) Math.min(511, ((v - lo) / span) * 511.99f)]++;
      int p2 = histIdx(hist, d.length / 50), p98 = histIdx(hist, d.length * 49 / 50);
      float slo = lo + span * p2 / 511f, shi = lo + span * p98 / 511f;
      if (!(shi - slo > 0.02f * span)) return null; // flat depth = useless scene
      float[] n8 = new float[d.length];
      float inv = 1f / (shi - slo);
      for (int i = 0; i < d.length; i++)
        n8[i] = Math.max(0f, Math.min(1f, (d[i] - slo) * inv));

      // Unproject onto the upright quarter grid (cell centers at 4*qx+2).
      float[] m = new float[quW * quH];
      for (int qy = 0; qy < quH; qy++) {
        float my = (qy * 4f + 2f) * fit + oy - 0.5f;
        int y0 = (int) Math.floor(my);
        float fy = my - y0;
        int yc0 = Math.max(0, Math.min(DS - 1, y0));
        int yc1 = Math.max(0, Math.min(DS - 1, y0 + 1));
        for (int qx = 0; qx < quW; qx++) {
          float mx = (qx * 4f + 2f) * fit + ox - 0.5f;
          int x0 = (int) Math.floor(mx);
          float fx = mx - x0;
          int xc0 = Math.max(0, Math.min(DS - 1, x0));
          int xc1 = Math.max(0, Math.min(DS - 1, x0 + 1));
          m[qy * quW + qx] =
              n8[yc0 * DS + xc0] * (1 - fx) * (1 - fy)
                  + n8[yc0 * DS + xc1] * fx * (1 - fy)
                  + n8[yc1 * DS + xc0] * (1 - fx) * fy
                  + n8[yc1 * DS + xc1] * fx * fy;
        }
      }
      return m;
    } catch (RuntimeException | Error e) {
      Log.w(TAG, "depth run failed: " + e);
      return null;
    }
  }

  private static int histIdx(int[] hist, int target) {
    int acc = 0;
    for (int i = 0; i < hist.length; i++) {
      acc += hist[i];
      if (acc > target) return i;
    }
    return hist.length - 1;
  }
}
