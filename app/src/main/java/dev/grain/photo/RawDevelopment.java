package dev.grain.photo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.media.FaceDetector;
import androidx.exifinterface.media.ExifInterface;
import dev.grain.photo.develop.CubeLut;
import dev.grain.photo.develop.Faces;
import dev.grain.photo.develop.FujiRecipe;
import dev.grain.photo.develop.RawDeveloper;
import dev.grain.photo.develop.RawFrame;
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
  static final String SOFTWARE = "Grain Photo " + BuildConfig.VERSION_NAME;

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
      Context context,
      GrainCamera.RawCapture capture,
      FujiRecipe recipe,
      boolean tuned,
      CubeLut lut,
      float ev,
      long seed,
      boolean faceExposure)
      throws Exception {
    String faceSearch = null;
    RawFrame frame = capture.frame;
    if (faceExposure
        && capture.phoneJpeg != null
        && Faces.pick(frame.faces, frame.faceScores, frame.width, frame.height) < 0) {
      long t = System.nanoTime();
      try {
        int found = findFacesInJpeg(capture);
        faceSearch = found + " face(s) in the phone JPEG";
      } catch (Exception e) {
        faceSearch = "phone JPEG face search failed: " + e.getMessage();
      }
      faceSearch += ", " + (System.nanoTime() - t) / 1_000_000 + " ms";
    }
    final Bitmap bitmap =
        Bitmap.createBitmap(capture.frame.width, capture.frame.height, Bitmap.Config.ARGB_8888);
    try {
      RawDeveloper.Options options = new RawDeveloper.Options();
      options.faceExposure = faceExposure;
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
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            recipe.name + " (" + recipe.filmLabel + " LUT" + (tuned ? ", tuned" : "") + ")");
        exif.saveAttributes();
        byte[] jpeg = new byte[(int) tmp.length()];
        try (InputStream in = new FileInputStream(tmp)) {
          int n = 0, r;
          while (n < jpeg.length && (r = in.read(jpeg, n, jpeg.length - n)) > 0) n += r;
        }
        JSONObject json = new JSONObject(stats);
        if (faceSearch != null) json.put("faceSearch", faceSearch);
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

  /**
   * Face exposure when the camera reported no usable face: android.media.FaceDetector (frontal
   * faces) on the phone's reference JPEG of the same exposure, downsampled and turned upright,
   * mapped back to RAW pixels. Returns the number of faces stored in the frame.
   */
  static int findFacesInJpeg(GrainCamera.RawCapture capture) throws Exception {
    byte[] jpeg = capture.phoneJpeg;
    RawFrame frame = capture.frame;
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 0;
    int exifDegrees;
    try (InputStream in = new ByteArrayInputStream(jpeg)) {
      exifDegrees = new ExifInterface(in).getRotationDegrees();
    }
    // The HAL either turned the pixels by the requested orientation or left the turn to EXIF.
    int pixelDegrees = ((capture.orientationDegrees - exifDegrees) % 360 + 360) % 360;
    boolean turned = pixelDegrees == 90 || pixelDegrees == 270;
    double aspect = turned ? bounds.outHeight / (double) bounds.outWidth : bounds.outWidth / (double) bounds.outHeight;
    double rawAspect = frame.width / (double) frame.height;
    if (Math.abs(aspect - rawAspect) > 0.01 * rawAspect) return 0; // not the RAW's field of view
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = 1;
    while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= 900)
      options.inSampleSize *= 2;
    options.inPreferredConfig = Bitmap.Config.RGB_565;
    Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
    if (decoded == null) return 0;
    int sensorW = turned ? decoded.getHeight() : decoded.getWidth();
    int sensorH = turned ? decoded.getWidth() : decoded.getHeight();
    // What EXIF still asks for, plus the device rotation the JPEG request did not include.
    int turn = ((exifDegrees + capture.uprightDegrees - capture.orientationDegrees) % 360 + 360) % 360;
    Bitmap upright = decoded;
    if (turn != 0) {
      Matrix m = new Matrix();
      m.postRotate(turn);
      upright = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, false);
      if (upright != decoded) decoded.recycle();
    }
    Bitmap search = upright;
    int width = upright.getWidth() & ~1; // FaceDetector needs an even width
    if (width != upright.getWidth() || upright.getConfig() != Bitmap.Config.RGB_565) {
      Bitmap even = Bitmap.createBitmap(upright, 0, 0, width, upright.getHeight());
      search = even.copy(Bitmap.Config.RGB_565, false);
      if (even != upright && even != search) even.recycle();
      if (upright != search) upright.recycle();
    }
    try {
      FaceDetector.Face[] found = new FaceDetector.Face[4];
      int n = new FaceDetector(search.getWidth(), search.getHeight(), found.length).findFaces(search, found);
      int[] rects = new int[4 * found.length], scores = new int[found.length];
      int kept = 0;
      PointF mid = new PointF();
      for (int i = 0; i < n; i++) {
        FaceDetector.Face face = found[i];
        if (face == null || face.confidence() < FaceDetector.Face.CONFIDENCE_THRESHOLD) continue;
        face.getMidPoint(mid);
        double[] box = Faces.boxFromEyes(mid.x, mid.y, face.eyesDistance());
        int[] raw =
            Faces.uprightToRaw(
                box[0], box[1], box[2], box[3], capture.uprightDegrees, sensorW, sensorH,
                frame.width, frame.height);
        if (raw == null) continue;
        System.arraycopy(raw, 0, rects, 4 * kept, 4);
        // FaceDetector's 0.4 threshold..1 onto Camera2's recommended 50..100
        float c = (face.confidence() - FaceDetector.Face.CONFIDENCE_THRESHOLD) / (1 - FaceDetector.Face.CONFIDENCE_THRESHOLD);
        scores[kept++] = Math.round(50 + 50 * Math.max(0, Math.min(1, c)));
      }
      if (kept > 0) {
        frame.faces = java.util.Arrays.copyOf(rects, 4 * kept);
        frame.faceScores = java.util.Arrays.copyOf(scores, kept);
        frame.faceSource = "phone JPEG (android.media.FaceDetector)";
      }
      return kept;
    } finally {
      search.recycle();
    }
  }
}
