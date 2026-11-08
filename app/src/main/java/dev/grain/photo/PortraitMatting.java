package dev.grain.photo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;
import org.tensorflow.lite.Interpreter;

/**
 * Hair-level person alpha matte via a bundled Robust Video Matting (MobileNetV3) TFLite model,
 * fully offline on the CPU. The network takes a 480x320 source plus 4 recurrent-state tensors
 * (zero-filled for every still: only the first-frame alpha output is consumed) and returns a soft
 * alpha matte whose hair-refinement stage follows flyaway strands far better than a plain
 * segmentation mask. Input RGB is /255; output alpha is 0..1 in the same letterbox space.
 */
final class PortraitMatting {
  private static final String TAG = "PortraitMatting";
  private static final String ASSET = "rvm_matting.tflite";
  private static final int MW = 480, MH = 320; // model input (width, height)
  private static final int THREADS = 6;

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
        Log.w(TAG, "matting model unavailable: " + e.getMessage());
        interpreter = null;
      }
    }
    return interpreter;
  }

  /**
   * Computes the alpha matte of an upright-oriented bitmap, returned on the upright quarter grid
   * ({@code quW x quH}; the letterbox padding is background 0). Null when the model is missing or
   * the matte is implausible (person coverage outside 1..90%).
   */
  static float[] matteUprightQuarter(Context ctx, Bitmap upright, int quW, int quH) {
    if (upright == null) return null;
    try {
      Interpreter it = get(ctx);
      if (it == null) return null;

      // Letterbox the upright picture into 480x320 (keeps proportions; black bars = background).
      float fit = Math.min(MW / (float) upright.getWidth(), MH / (float) upright.getHeight());
      int cw = Math.max(1, Math.round(upright.getWidth() * fit));
      int ch = Math.max(1, Math.round(upright.getHeight() * fit));
      int ox = (MW - cw) / 2, oy = (MH - ch) / 2;
      Bitmap box = Bitmap.createBitmap(MW, MH, Bitmap.Config.ARGB_8888);
      Canvas cv = new Canvas(box);
      cv.drawColor(Color.BLACK);
      Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
      cv.drawBitmap(upright, null, new Rect(ox, oy, ox + cw, oy + ch), paint);
      int[] pix = new int[MW * MH];
      box.getPixels(pix, 0, MW, 0, 0, MW, MH);
      box.recycle();

      // Inputs: src [1,320,480,3] /255; recurrent states zero-filled.
      float[][][][] src = new float[1][MH][MW][3];
      for (int y = 0; y < MH; y++) {
        int o = y * MW;
        for (int x = 0; x < MW; x++) {
          int p = pix[o + x];
          src[0][y][x][0] = ((p >>> 16) & 255) / 255f;
          src[0][y][x][1] = ((p >>> 8) & 255) / 255f;
          src[0][y][x][2] = (p & 255) / 255f;
        }
      }
      int inCount = it.getInputTensorCount();
      Object[] inputs = new Object[inCount];
      int srcIdx = -1;
      for (int i = 0; i < inCount; i++) {
        int[] sh = it.getInputTensor(i).shape();
        if (sh.length == 4 && sh[1] == MH && sh[2] == MW && sh[3] == 3) {
          inputs[i] = src;
          srcIdx = i;
        } else {
          int sz = 4;
          for (int d : sh) sz *= d;
          inputs[i] = ByteBuffer.allocateDirect(sz).order(ByteOrder.nativeOrder());
        }
      }
      if (srcIdx < 0) return null;

      // Outputs: alpha [1,320,480,1]; the other 5 (foreground colors + new states) discarded.
      Map<Integer, Object> outputs = new HashMap<>();
      float[][][][] alpha = new float[1][MH][MW][1];
      int alphaIdx = -1;
      for (int i = 0; i < it.getOutputTensorCount(); i++) {
        int[] sh = it.getOutputTensor(i).shape();
        if (sh.length == 4 && sh[1] == MH && sh[2] == MW && sh[3] == 1) {
          outputs.put(i, alpha);
          alphaIdx = i;
        } else {
          int sz = 4;
          for (int d : sh) sz *= d;
          outputs.put(i, ByteBuffer.allocateDirect(sz).order(ByteOrder.nativeOrder()));
        }
      }
      if (alphaIdx < 0 || outputs.size() != it.getOutputTensorCount()) return null;
      it.runForMultipleInputsOutputs(inputs, outputs);

      // Plausibility: person coverage somewhere between 1% and 90% of the frame.
      int cov = 0;
      for (int y = 0; y < MH; y++)
        for (int x = 0; x < MW; x++) if (alpha[0][y][x][0] > 0.5f) cov++;
      float frac = cov / (float) (MW * MH);
      if (frac < 0.01f || frac > 0.90f) return null;

      // Unproject onto the upright quarter grid (cell centers at upright 4*qx+2).
      float[] m = new float[quW * quH];
      for (int qy = 0; qy < quH; qy++) {
        float my = (qy * 4f + 2f) * fit + oy - 0.5f;
        int y0 = (int) Math.floor(my);
        float fy = my - y0;
        int yc0 = Math.max(0, Math.min(MH - 1, y0));
        int yc1 = Math.max(0, Math.min(MH - 1, y0 + 1));
        for (int qx = 0; qx < quW; qx++) {
          float mx = (qx * 4f + 2f) * fit + ox - 0.5f;
          int x0 = (int) Math.floor(mx);
          float fx = mx - x0;
          int xc0 = Math.max(0, Math.min(MW - 1, x0));
          int xc1 = Math.max(0, Math.min(MW - 1, x0 + 1));
          m[qy * quW + qx] =
              alpha[0][yc0][xc0][0] * (1 - fx) * (1 - fy)
                  + alpha[0][yc0][xc1][0] * fx * (1 - fy)
                  + alpha[0][yc1][xc0][0] * (1 - fx) * fy
                  + alpha[0][yc1][xc1][0] * fx * fy;
        }
      }
      return m;
    } catch (RuntimeException | Error e) {
      Log.w(TAG, "matte run failed: " + e);
      return null;
    }
  }
}
