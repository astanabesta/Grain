package dev.grain.photo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.ByteBufferExtractor;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter.ImageSegmenterOptions;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * On-device person segmentation for portrait mode (MediaPipe selfie segmenter, fully offline; the
 * 256x256 model is bundled in assets). Produces an upright-orientation person-confidence mask on
 * the upright quarter-resolution grid {@code ((uprightW + 3) / 4) x ((uprightH + 3) / 4)}. Fails
 * safe: returns null when the model is unavailable or finds no plausible person, letting the
 * caller fall back to the camera-reported face silhouette.
 */
final class PortraitSegmenter {
  private static final String MODEL = "selfie_segmenter.tflite";
  private static ImageSegmenter segmenter;
  private static boolean unavailable;

  private static synchronized ImageSegmenter segmenter(Context context) {
    if (unavailable) return null;
    if (segmenter == null) {
      try {
        ImageSegmenterOptions options =
            ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setOutputConfidenceMasks(true)
                .build();
        segmenter = ImageSegmenter.createFromOptions(context, options);
      } catch (Throwable t) {
        unavailable = true;
        return null;
      }
    }
    return segmenter;
  }

  /**
   * Person probability mask on the upright quarter grid, or null. {@code uprightDegCw} is the
   * clockwise rotation that makes the sensor {@code bitmap} display upright (the value written to
   * EXIF orientation).
   */
  static float[] maskUpright(Context context, Bitmap bitmap, int uprightDegCw) {
    int ws = bitmap.getWidth(), hs = bitmap.getHeight();
    int deg = ((uprightDegCw % 360) + 360) % 360;
    int wu = (deg == 90 || deg == 270) ? hs : ws, hu = (deg == 90 || deg == 270) ? ws : hs;
    return maskUpright(context, bitmap, uprightDegCw, wu, hu);
  }

  /**
   * Same, but the caller supplies the FULL-resolution upright dimensions ({@code fullW x fullH}):
   * required when {@code bitmap} is a pre-rotated small preview (the quarter grid indexes full-res
   * upright pixels, not the bitmap's pixels). With {@code uprightDegCw == 0} the bitmap is used
   * as-is and only the supplied full dims define the output grid.
   */
  static float[] maskUpright(
      Context context, Bitmap bitmap, int uprightDegCw, int fullW, int fullH) {
    ImageSegmenter s = segmenter(context.getApplicationContext());
    if (s == null) return null;
    int ws = bitmap.getWidth(), hs = bitmap.getHeight();
    int deg = ((uprightDegCw % 360) + 360) % 360;
    int wu, hu;
    Bitmap upright;
    Bitmap small = null;
    if (deg == 0 && fullW > 0) {
      small = bitmap;
      upright = bitmap; // already upright; used only for letterboxing proportions
      wu = fullW;
      hu = fullH;
    } else {
      wu = (deg == 90 || deg == 270) ? hs : ws;
      hu = (deg == 90 || deg == 270) ? ws : hs;
      float sc = Math.min(1f, 512f / Math.max(ws, hs));
      small = Bitmap.createScaledBitmap(bitmap, Math.round(ws * sc), Math.round(hs * sc), true);
      Matrix m = new Matrix();
      m.postRotate(deg);
      upright = Bitmap.createBitmap(small, 0, 0, small.getWidth(), small.getHeight(), m, true);
    }

    // Letterbox against the FULL-resolution upright dimensions (quarter cells index full-res
    // pixels); the possibly-smaller bitmap is stretched into the content box.
    int side = 256;
    float k = side / (float) Math.max(wu, hu);
    int cw = Math.max(1, Math.round(wu * k)), ch = Math.max(1, Math.round(hu * k));
    int ox = (side - cw) / 2, oy = (side - ch) / 2;
    Bitmap content = Bitmap.createScaledBitmap(upright, cw, ch, true);
    if (upright != bitmap) upright.recycle();
    if (small != null && small != bitmap && small != upright) small.recycle();
    Bitmap square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
    Canvas cv = new Canvas(square);
    cv.drawARGB(255, 0, 0, 0);
    cv.drawBitmap(content, ox, oy, null);
    content.recycle();

    ImageSegmenterResult result;
    try (MPImage mp = new BitmapImageBuilder(square).build()) {
      result = s.segment(mp);
    } catch (Throwable t) {
      square.recycle();
      return null;
    } finally {
      if (!square.isRecycled()) square.recycle();
    }

    float[] p = extractPerson(result);
    if (p == null) return null;

    // Crop the content box out of the square canvas mask and resize to the upright quarter grid.
    int quW = (wu + 3) / 4, quH = (hu + 3) / 4;
    float[] out = new float[quW * quH];
    int mw = 256; // model square canvas
    float fg = 0;
    for (int y = 0; y < quH; y++) {
      float my = oy + (y * 4f + 2f) / hu * ch - 0.5f;
      int y0 = (int) Math.floor(my);
      float fy = my - y0;
      int yc0 = y0 < 0 ? 0 : y0 >= mw - 1 ? mw - 1 : y0;
      int yc1 = yc0 >= mw - 1 ? yc0 : yc0 + 1;
      for (int x = 0; x < quW; x++) {
        float mx = ox + (x * 4f + 2f) / wu * cw - 0.5f;
        int x0 = (int) Math.floor(mx);
        float fx = mx - x0;
        int xc0 = x0 < 0 ? 0 : x0 >= mw - 1 ? mw - 1 : x0;
        int xc1 = xc0 >= mw - 1 ? xc0 : xc0 + 1;
        float v =
            p[yc0 * mw + xc0] * (1 - fx) * (1 - fy)
                + p[yc0 * mw + xc1] * fx * (1 - fy)
                + p[yc1 * mw + xc0] * (1 - fx) * fy
                + p[yc1 * mw + xc1] * fx * fy;
        out[y * quW + x] = v;
        fg += v;
      }
    }
    fg /= out.length;
    // Reject degenerate masks (model glitch / empty scene).
    return fg > 0.01f && fg < 0.9f ? out : null;
  }

  /** The person channel of the segmentation result as 256x256 floats, or null. */
  private static float[] extractPerson(ImageSegmenterResult result) {
    try {
      List<MPImage> masks = result.confidenceMasks().orElse(null);
      if (masks == null || masks.isEmpty()) return null;
      // The selfie model's person class is the last channel.
      MPImage mp = masks.get(masks.size() - 1);
      ByteBuffer buf = ByteBufferExtractor.extract(mp);
      int n = mp.getWidth() * mp.getHeight();
      if (mp.getWidth() != 256) return null;
      FloatBuffer fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer();
      float[] p = new float[n];
      fb.get(p);
      return p;
    } catch (Throwable t) {
      return null;
    }
  }
}
