package dev.grain.photo;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.exifinterface.media.ExifInterface;
import dev.grain.photo.develop.CubeLut;
import dev.grain.photo.develop.FujiRecipe;
import dev.grain.photo.develop.RawDeveloper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;
import org.json.JSONObject;

/** Runs the RAW engine for one capture and encodes the result as an EXIF-tagged JPEG. */
final class RawDevelopment {
  static final String SOFTWARE = "Grain Photo 0.2.2";

  static final class Output {
    final byte[] jpeg;
    final JSONObject stats;

    Output(byte[] jpeg, JSONObject stats) {
      this.jpeg = jpeg;
      this.stats = stats;
    }
  }

  private RawDevelopment() {}

  static Output develop(
      Context context, GrainCamera.RawCapture capture, FujiRecipe recipe, CubeLut lut, float ev, long seed)
      throws Exception {
    final Bitmap bitmap =
        Bitmap.createBitmap(capture.frame.width, capture.frame.height, Bitmap.Config.ARGB_8888);
    try {
      RawDeveloper.Options options = new RawDeveloper.Options();
      options.exposureEv = ev;
      options.grainSeed = seed;
      options.threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
      Map<String, Object> stats =
          RawDeveloper.develop(
              capture.frame,
              recipe,
              lut,
              options,
              (y, rows, argb, width) -> {
                synchronized (bitmap) {
                  bitmap.setPixels(argb, 0, width, 0, y, width, rows);
                }
              });
      long t = System.nanoTime();
      ByteArrayOutputStream encoded = new ByteArrayOutputStream(8 << 20);
      if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, encoded))
        throw new IllegalStateException("JPEG encoding failed");
      bitmap.recycle();
      File tmp = File.createTempFile("develop", ".jpg", context.getCacheDir());
      try {
        try (FileOutputStream out = new FileOutputStream(tmp)) {
          encoded.writeTo(out);
        }
        encoded = null;
        ExifInterface exif = new ExifInterface(tmp);
        if (capture.phoneJpeg != null)
          try (InputStream in = new ByteArrayInputStream(capture.phoneJpeg)) {
            ExifInterface phone = new ExifInterface(in);
            String[] tags = {
              ExifInterface.TAG_MAKE,
              ExifInterface.TAG_MODEL,
              ExifInterface.TAG_DATETIME,
              ExifInterface.TAG_DATETIME_ORIGINAL,
              ExifInterface.TAG_DATETIME_DIGITIZED,
              ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
              ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
              ExifInterface.TAG_EXPOSURE_TIME,
              ExifInterface.TAG_F_NUMBER,
              ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
              ExifInterface.TAG_FOCAL_LENGTH,
              ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM
            };
            for (String tag : tags) {
              String value = phone.getAttribute(tag);
              if (value != null) exif.setAttribute(tag, value);
            }
          } catch (Exception ignored) {
          }
        exif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            String.valueOf(RawSupport.exifOrientation(capture.orientationDegrees)));
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, SOFTWARE);
        exif.setAttribute(
            ExifInterface.TAG_IMAGE_DESCRIPTION, recipe.name + " (" + recipe.filmLabel + " LUT)");
        exif.saveAttributes();
        byte[] jpeg = new byte[(int) tmp.length()];
        try (InputStream in = new FileInputStream(tmp)) {
          int n = 0, r;
          while (n < jpeg.length && (r = in.read(jpeg, n, jpeg.length - n)) > 0) n += r;
        }
        JSONObject json = new JSONObject(stats);
        json.put("encodeMs", (System.nanoTime() - t) / 1_000_000);
        json.put("jpegQuality", 95);
        return new Output(jpeg, json);
      } finally {
        if (!tmp.delete() && tmp.exists()) android.util.Log.w("Grain", "Temp JPEG kept");
      }
    } finally {
      if (!bitmap.isRecycled()) bitmap.recycle();
    }
  }
}
