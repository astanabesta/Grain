package dev.grain.photo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.media.FaceDetector;
import android.os.Build;
import androidx.exifinterface.media.ExifInterface;
import dev.grain.photo.develop.AppleRender;
import dev.grain.photo.develop.CubeLut;
import dev.grain.photo.develop.Faces;
import dev.grain.photo.develop.FujiRecipe;
import dev.grain.photo.develop.RawDeveloper;
import dev.grain.photo.develop.RawFrame;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import org.json.JSONObject;

/** Runs the RAW engine for one capture and encodes the result as an EXIF-tagged JPEG file. */
final class RawDevelopment {
  static final String SOFTWARE = "Grain Photo " + BuildConfig.VERSION_NAME;

  static final class Output {
    final File jpegFile;
    final JSONObject stats;

    Output(File jpegFile, JSONObject stats) {
      this.jpegFile = jpegFile;
      this.stats = stats;
    }
  }

  private RawDevelopment() {}

  /** Portrait-mode request: apertureF = 0 disables it; otherwise the f-number to simulate. */
  static final class Portrait {
    final float apertureF;

    Portrait(float apertureF) {
      this.apertureF = apertureF;
    }
  }

  /** Downscaled (<=640 px long edge), gravity-upright copy of the developed frame for the NN models. */
  private static android.graphics.Bitmap uprightSmall(android.graphics.Bitmap src, int degCw) {
    float sc = Math.min(1f, 640f / Math.max(src.getWidth(), src.getHeight()));
    android.graphics.Matrix m = new android.graphics.Matrix();
    m.postScale(sc, sc);
    m.postRotate(degCw);
    try {
      android.graphics.Bitmap out =
          android.graphics.Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
      return out != src ? out : out.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
    } catch (OutOfMemoryError | RuntimeException e) {
      return null;
    }
  }

  static Output develop(
      Context context,
      GrainCamera.RawCapture capture,
      FujiRecipe recipe,
      boolean tuned,
      CubeLut lut,
      float ev,
      long seed,
      boolean faceExposure,
      boolean releaseFrameAfterDevelop,
      Portrait portrait)
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
    final int outW = frame.width, outH = frame.height;
    final int frameIso = frame.iso;
    final long frameExposureNs = frame.exposureNs;
    final int[] frameFaces = frame.faces;
    final Bitmap bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
    Bitmap gainmapBmp = null;
    File tmp = null;
    boolean success = false;
    try {
      boolean apple = recipe.apple();
      RawDeveloper.Options options = new RawDeveloper.Options();
      options.faceExposure = faceExposure;
      options.exposureEv = ev;
      options.grainSeed = seed;
      options.threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
      options.outputDisplayP3 = false;
      options.computeGainmap = apple && Build.VERSION.SDK_INT >= 34;
      Map<String, Object> stats =
          RawDeveloper.develop(
              frame,
              recipe,
              lut,
              options,
              (y, rows, argb, width) -> {
                synchronized (bitmap) {
                  bitmap.setPixels(argb, 0, width, 0, y, width, rows);
                }
              });
      // Release the 16-bit Bayer array before starting JPEG compression (saves ~225 MB at 112 MP).
      frame = null;
      if (releaseFrameAfterDevelop) capture.releaseFrame();

      if (apple) {
        if (options.outputDisplayP3) UltraHdrJpeg.tagDisplayP3(bitmap);
        gainmapBmp =
            UltraHdrJpeg.attachGainmapIfSupported(
                bitmap,
                options.outGainmap,
                options.outGainmapW,
                options.outGainmapH,
                AppleRender.GAINMAP_RATIO_MAX);
        options.outGainmap = null;
      }

      // Portrait mode (people + held subjects sharp, natural lens-style background bokeh).
      if (portrait != null && portrait.apertureF > 0.1f) {
        long tp = System.nanoTime();
        if ((long) outW * outH > 34_000_000L) {
          stats.put("portrait", "skipped (hi-res frame)");
        } else {
          int qw = (outW + 3) / 4, qh = (outH + 3) / 4;
          int deg = capture.orientationDegrees;
          int uw = (deg % 180 == 0) ? outW : outH, uh = (deg % 180 == 0) ? outH : outW;
          int quW = (uw + 3) / 4, quH = (uh + 3) / 4;
          android.graphics.Bitmap up = uprightSmall(bitmap, deg);
          float[] maskQ = null, depthQ = null;
          String maskSrc = null;
          boolean depthOn = false;
          try {
            long tm = System.nanoTime();
            float[] alphaU = PortraitMatting.matteUprightQuarter(context, up, uw, uh, quW, quH);
            if (alphaU != null) {
              maskQ =
                  dev.grain.photo.develop.PortraitBokeh.sampleUprightMaskToSensor(
                      alphaU, quW, quH, outW, outH, deg);
              maskSrc = "alpha matte";
            }
            stats.put("portraitMattingMs", (System.nanoTime() - tm) / 1_000_000);
          } catch (Throwable t2) {
            stats.put("portraitMatting", "failed: " + t2.getMessage());
          }
          if (maskQ == null) {
            try {
              float[] maskU = PortraitSegmenter.maskUpright(context, up, 0, uw, uh);
              if (maskU != null) {
                maskQ =
                    dev.grain.photo.develop.PortraitBokeh.sampleUprightMaskToSensor(
                        maskU, quW, quH, outW, outH, deg);
                maskSrc = "person segmentation";
              }
            } catch (Throwable t2) {
              stats.put("portraitSegmenter", "failed: " + t2.getMessage());
            }
          }
          try {
            long td = System.nanoTime();
            float[] depthU = PortraitDepth.depthUprightQuarter(context, up, uw, uh, quW, quH);
            if (depthU != null) {
              depthQ =
                  dev.grain.photo.develop.PortraitBokeh.sampleUprightMaskToSensor(
                      depthU, quW, quH, outW, outH, deg);
              depthOn = true;
            }
            stats.put("portraitDepthMs", (System.nanoTime() - td) / 1_000_000);
            if (!depthOn) stats.put("portraitDepthNull", String.valueOf(PortraitDepth.lastFailure));
          } catch (Throwable t2) {
            stats.put("portraitDepth", "failed: " + t2.getMessage());
          }
          if (up != null) up.recycle();
          if (maskQ == null) {
            int bestFace = Faces.pick(frameFaces, null, outW, outH);
            if (bestFace >= 0) {
              int[] rect = {
                frameFaces[bestFace * 4],
                frameFaces[bestFace * 4 + 1],
                frameFaces[bestFace * 4 + 2],
                frameFaces[bestFace * 4 + 3]
              };
              maskQ = dev.grain.photo.develop.PortraitBokeh.faceEllipseMask(rect, outW, outH);
              maskSrc = "face silhouette";
            }
          }
          if (maskQ != null) {
            // Hard guard: a broken model can never blur the frame wholesale. If the person-core
            // coverage is implausible, skip the bokeh entirely (photo stays developed normally).
            int core = 0;
            for (float v : maskQ) if (v > 0.65f) core++;
            float cover = core / (float) maskQ.length;
            if (cover < 0.003f || cover > 0.85f) {
              stats.put(
                  "portraitGuard",
                  String.format(java.util.Locale.US, "implausible mask coverage %.3f", cover));
              maskQ = null;
            }
          }
          if (maskQ != null && frameFaces != null) {
            int bf = Faces.pick(frameFaces, null, outW, outH);
            if (bf >= 0) {
              int[] fr = {
                frameFaces[bf * 4], frameFaces[bf * 4 + 1],
                frameFaces[bf * 4 + 2], frameFaces[bf * 4 + 3]
              };
              dev.grain.photo.develop.PortraitBokeh.applyHairAnchor(maskQ, qw, qh, fr, outW, outH);
              maskSrc += " + hair-anchor";
            }
          }
          if (maskQ != null) {
            int[] px = new int[outW * outH];
            bitmap.getPixels(px, 0, outW, 0, 0, outW, outH);
            float rUsed =
                dev.grain.photo.develop.PortraitBokeh.apply(
                    px, outW, outH, maskQ, qw, qh, portrait.apertureF, px, depthQ);
            bitmap.setPixels(px, 0, outW, 0, 0, outW, outH);
            stats.put(
                "portrait",
                "f/" + portrait.apertureF + " · " + maskSrc + (depthOn ? " + depth" : "")
                    + " · disc " + Math.round(rUsed) + "px");
          } else {
            stats.put("portrait", "no subject found");
          }
        }
        stats.put("portraitMs", (System.nanoTime() - tp) / 1_000_000);
      }

      long t = System.nanoTime();
      tmp = File.createTempFile("develop-", ".jpg", context.getCacheDir());
      try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), 65536)) {
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
          throw new IllegalStateException("JPEG encoding failed");
      }
      boolean wroteGainmap = gainmapBmp != null;
      // Free the native ARGB_8888 pixel buffer (~448 MB at 112 MP) before EXIF rewrite and save.
      bitmap.recycle();
      if (gainmapBmp != null) {
        gainmapBmp.recycle();
        gainmapBmp = null;
      }

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
      if (exif.getAttribute(ExifInterface.TAG_MAKE) == null && Build.MANUFACTURER != null)
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER);
      if (exif.getAttribute(ExifInterface.TAG_MODEL) == null && Build.MODEL != null)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL);
      if (exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY) == null && frameIso > 0)
        exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, String.valueOf(frameIso));
      if (exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) == null && frameExposureNs > 0)
        exif.setAttribute(
            ExifInterface.TAG_EXPOSURE_TIME,
            String.format(java.util.Locale.US, "%.6f", frameExposureNs / 1e9));
      exif.setAttribute(
          ExifInterface.TAG_ORIENTATION,
          String.valueOf(RawSupport.exifOrientation(capture.orientationDegrees)));
      exif.setAttribute(ExifInterface.TAG_SOFTWARE, SOFTWARE);
      exif.setAttribute(
          ExifInterface.TAG_IMAGE_DESCRIPTION,
          recipe.name
              + " ("
              + recipe.filmLabel
              + (apple ? ", Vibrant sRGB + Ultra HDR" : " LUT")
              + (tuned ? ", tuned" : "")
              + ")");
      exif.saveAttributes();
      if (wroteGainmap) {
        boolean repaired = UltraHdrJpeg.repairMpfAfterExifSave(tmp);
        stats.put("ultraHdrMpfRepaired", repaired);
      }

      JSONObject json = new JSONObject(stats);
      if (faceSearch != null) json.put("faceSearch", faceSearch);
      json.put("encodeMs", (System.nanoTime() - t) / 1_000_000);
      json.put("jpegQuality", 95);
      success = true;
      return new Output(tmp, json);
    } finally {
      if (!bitmap.isRecycled()) bitmap.recycle();
      if (gainmapBmp != null && !gainmapBmp.isRecycled()) gainmapBmp.recycle();
      if (!success && tmp != null && tmp.exists() && !tmp.delete()) {
        android.util.Log.w("Grain", "Temp JPEG kept");
      }
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
